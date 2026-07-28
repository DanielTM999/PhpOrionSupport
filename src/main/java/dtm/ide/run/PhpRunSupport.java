package dtm.ide.run;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.api.extension.runconfig.RunProcessHandle;
import dtm.ide.lsp.PhpToolchainService;
import dtm.stools.component.inputfields.textfield.PathTextField;
import dtm.stools.component.popup.ModernComponentDialog;
import dtm.stools.component.popup.ModernDialog;

import javax.swing.SwingUtilities;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class PhpRunSupport {
    public static final String TYPE_XAMPP = "php.xampp";
    public static final String TYPE_BUILTIN = "php.builtin";
    public static final String TYPE_CLI = "php.cli";
    public static final String TYPE_XDEBUG_LISTEN = "php.xdebug.listen";
    public static final String TYPE_XDEBUG_CLI = "php.xdebug.cli";
    public static final String TYPE_XDEBUG_BUILTIN = "php.xdebug.builtin";

    private final Map<String, Process> ownedProcesses = new ConcurrentHashMap<>();
    private volatile Path activeFile;

    private volatile Supplier<String> xamppRootReader;
    private volatile Consumer<String> xamppRootWriter;
    private volatile Supplier<ModernComponentDialog.ModernComponentDialogBuilder<String>> xamppDialogFactory;

    // Estado do servidor XAMPP em execução. O xampp_start é apenas um lançador que encerra
    // imediatamente, então NÃO dá para basear o "está vivo" no processo lançador nem só na
    // detecção de PID (que pode falhar). A fonte de verdade é a flag xamppRunning: ela mantém
    // o run vivo (botão parar habilitado) até que nós mesmos paremos o XAMPP.
    private volatile Path xamppRootInUse;
    private volatile long xamppServerPid;
    private volatile boolean xamppStartedByUs;
    private final AtomicBoolean xamppRunning = new AtomicBoolean(false);
    private final AtomicBoolean xamppStopping = new AtomicBoolean(false);
    private volatile LiveInputStream xamppOutput;

    public void setActiveFile(Path activeFile) {
        this.activeFile = activeFile;
    }

    /**
     * Liga o suporte de execução ao armazenamento persistente (props) e à fábrica de diálogos
     * modernos do adapter. O {@code dialogFactory} deve ser a referência de método
     * {@code adapter::createModernComponentDialogBuilder}.
     */
    public void configureXampp(Supplier<String> rootReader, Consumer<String> rootWriter,
                               Supplier<ModernComponentDialog.ModernComponentDialogBuilder<String>> dialogFactory) {
        this.xamppRootReader = rootReader;
        this.xamppRootWriter = rootWriter;
        this.xamppDialogFactory = dialogFactory;
    }

    public List<RunConfigurationData> configurations(Path root) {
        int serverPort = integerSystemProperty("orion.php.serverPort", 8080);
        int debugPort = integerSystemProperty("orion.php.xdebugPort", 9003);
        return List.of(
                configuration(TYPE_XAMPP, "PHP: XAMPP", Map.of("url", "http://localhost/")),
                configuration(TYPE_BUILTIN, "PHP: Servidor embutido",
                        Map.of("host", "localhost", "port", serverPort)),
                configuration(TYPE_CLI, "PHP: Arquivo atual", Map.of()),
                configuration(TYPE_XDEBUG_LISTEN, "PHP: Escutar Xdebug", Map.of("port", debugPort)),
                configuration(TYPE_XDEBUG_CLI, "PHP: Debug do arquivo atual", Map.of("port", debugPort)),
                configuration(TYPE_XDEBUG_BUILTIN, "PHP: Servidor embutido com Xdebug",
                        Map.of("host", "localhost", "port", serverPort, "xdebugPort", debugPort))
        );
    }

    public RunProcessHandle launch(RunConfigurationData configuration, Path root,
                                   PhpToolchainService toolchain, boolean debug) throws Exception {
        String type = configuration.getType();
        return switch (type) {
            case TYPE_XAMPP -> launchXampp();
            case TYPE_BUILTIN -> launchBuiltIn(configuration, root, toolchain.findPhp(root), debug);
            case TYPE_CLI -> launchCli(configuration, root, toolchain.findPhp(root), debug);
            case TYPE_XDEBUG_CLI -> launchCli(configuration, root, toolchain.findPhp(root), true);
            case TYPE_XDEBUG_BUILTIN -> launchBuiltIn(configuration, root, toolchain.findPhp(root), true);
            case TYPE_XDEBUG_LISTEN -> messageHandle("Escutando conexões Xdebug na porta "
                    + intProperty(configuration, "port", 9003) + ".\n");
            default -> throw new IllegalArgumentException("Configuração PHP desconhecida: " + type);
        };
    }

    public void stop(RunConfigurationData configuration) {
        if (configuration == null) return;
        if (TYPE_XAMPP.equals(configuration.getType())) {
            stopXampp();
            return;
        }
        Process process = ownedProcesses.remove(configuration.getType());
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }

    public void stopAll() {
        // Ao fechar o projeto só paramos o XAMPP se fomos nós que o iniciamos (pela flag).
        if (xamppStartedByUs || ownedProcesses.containsKey(TYPE_XAMPP)) {
            stopXampp();
        }
        ownedProcesses.values().forEach(process -> {
            if (process.isAlive()) process.destroy();
        });
        ownedProcesses.clear();
    }

    /** Indica se o XAMPP está em execução sob controle do plugin (usado para habilitar o parar). */
    public boolean isXamppServerRunning() {
        return xamppRunning.get();
    }

    /** Verdadeiro somente quando fomos nós que iniciamos o XAMPP (controla o stop no fechar). */
    public boolean isXamppStartedByUs() {
        return xamppStartedByUs;
    }

    private RunProcessHandle launchXampp() throws Exception {
        Path root = resolveXamppRoot(true);
        if (root == null) {
            throw new IllegalStateException("Instalação do XAMPP não encontrada e nenhuma pasta foi informada.");
        }
        xamppRootInUse = root;

        LiveInputStream output = new LiveInputStream();
        this.xamppOutput = output;

        Long alreadyRunning = findApachePid();
        if (alreadyRunning != null) {
            // Já está no ar: apenas nos anexamos, mas o botão parar continua funcional.
            xamppServerPid = alreadyRunning;
            xamppStartedByUs = false;
            xamppRunning.set(true);
            output.write("Apache/XAMPP já está em execução (PID " + alreadyRunning
                    + "). A Orion anexou-se ao servidor.\n");
            return xamppHandle(output);
        }

        List<String> command = startCommand(root);
        Path executable = Path.of(command.get(0));
        if (!Files.isRegularFile(executable)) {
            throw new IllegalStateException("Executável de inicialização do XAMPP não foi encontrado em " + executable);
        }
        Process launcher = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        ownedProcesses.put(TYPE_XAMPP, launcher);
        xamppStartedByUs = true;
        xamppServerPid = 0L;
        xamppRunning.set(true);

        output.write("Iniciando XAMPP em " + root + " ...\n");
        // O lançador (xampp_start) encerra rápido; drenamos a saída dele sem fechar o stream
        // do run — o stream só encerra quando pararmos o XAMPP.
        pumpLauncherOutput(launcher, output);
        return xamppHandle(output);
    }

    private static void pumpLauncherOutput(Process launcher, LiveInputStream output) {
        Thread pump = new Thread(() -> {
            try (InputStream in = launcher.getInputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            } catch (Exception ignored) {
                // Fim da saída do lançador; o stream do run permanece aberto propositalmente.
            }
        }, "xampp-output-pump");
        pump.setDaemon(true);
        pump.start();
    }

    private RunProcessHandle xamppHandle(InputStream output) {
        return RunProcessHandle.builder()
                .output(output)
                .readonly(true)
                .alive(this::isXamppRunning)
                .terminate(this::stopXampp)
                .build();
    }

    /**
     * O run é considerado vivo enquanto a flag xamppRunning estiver ligada — ela só é desligada
     * quando nós paramos o XAMPP (botão parar ou fechamento do projeto). Isso mantém o botão de
     * parar habilitado mesmo com o lançador já encerrado.
     */
    private boolean isXamppRunning() {
        return xamppRunning.get();
    }

    private void stopXampp() {
        if (!xamppRunning.get() && !xamppStartedByUs && !ownedProcesses.containsKey(TYPE_XAMPP)) {
            return;
        }
        if (!xamppStopping.compareAndSet(false, true)) {
            return;
        }
        // Snapshot + atualização de estado imediatos (rápidos e seguros na EDT). O IDE já vê o run
        // como parado assim que a flag desliga.
        Process launcher = ownedProcesses.remove(TYPE_XAMPP);
        Path knownRoot = xamppRootInUse;
        long pid = xamppServerPid;
        LiveInputStream output = this.xamppOutput;

        xamppRunning.set(false);
        xamppRootInUse = null;
        xamppStartedByUs = false;
        xamppServerPid = 0L;
        this.xamppOutput = null;

        // O encerramento real chama xampp_stop e espera o processo: NUNCA na EDT (travaria a UI).
        Thread worker = new Thread(() -> {
            try {
                if (launcher != null && launcher.isAlive()) {
                    launcher.destroy();
                }
                Path root = knownRoot != null ? knownRoot : resolveXamppRoot(false);
                if (root != null) {
                    if (output != null) output.write("Parando XAMPP ...\n");
                    runStopCommand(root);
                }
                // Fallback: se o executável de stop não parou o Apache, encerramos pelo PID conhecido
                // ou por qualquer httpd/apache2 ainda em execução.
                long targetPid = pid;
                if (targetPid <= 0) {
                    Long detected = findApachePid();
                    if (detected != null) targetPid = detected;
                }
                if (targetPid > 0) {
                    ProcessHandle.of(targetPid).ifPresent(handle -> {
                        if (handle.isAlive()) handle.destroy();
                    });
                }
            } finally {
                if (output != null) output.close();
                xamppStopping.set(false);
            }
        }, "xampp-stop");
        worker.setDaemon(true);
        worker.start();
    }

    private void runStopCommand(Path root) {
        List<String> command = stopCommand(root);
        if (!Files.isRegularFile(Path.of(command.get(0)))) return;
        try {
            Process stopper = new ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            stopper.waitFor(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Encerramento é best-effort; o fallback por PID ainda pode agir.
        }
    }

    /**
     * Resolve a pasta raiz do XAMPP. A busca só acontece quando a pasta salva nas props não
     * contém mais os executáveis de start/stop (ex.: foram movidos ou excluídos). Ao encontrar,
     * o caminho é persistido para não precisar procurar novamente.
     */
    private Path resolveXamppRoot(boolean allowPrompt) {
        Path saved = savedXamppRoot();
        if (isValidXamppRoot(saved)) {
            return saved;
        }
        Path found = searchDefaultXamppRoots();
        if (found != null) {
            persistXamppRoot(found);
            return found;
        }
        if (!allowPrompt) {
            return null;
        }
        Path chosen = promptForXamppRoot(saved);
        if (chosen != null) {
            persistXamppRoot(chosen);
            return chosen;
        }
        return null;
    }

    private Path savedXamppRoot() {
        String value = xamppRootReader != null ? xamppRootReader.get()
                : System.getProperty("orion.xampp.root");
        if (value == null || value.isBlank()) return null;
        return Path.of(value.strip());
    }

    private void persistXamppRoot(Path root) {
        if (root == null) return;
        String value = root.toString();
        if (xamppRootWriter != null) {
            xamppRootWriter.accept(value);
        } else {
            System.setProperty("orion.xampp.root", value);
        }
    }

    private Path searchDefaultXamppRoots() {
        for (Path candidate : defaultXamppRoots()) {
            if (isValidXamppRoot(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<Path> defaultXamppRoots() {
        List<Path> candidates = new ArrayList<>();
        String home = System.getProperty("user.home", "");
        if (isWindows()) {
            candidates.add(Path.of("C:\\xampp"));
            candidates.add(Path.of("D:\\xampp"));
            candidates.add(Path.of("C:\\Program Files\\xampp"));
            candidates.add(Path.of("C:\\Program Files (x86)\\xampp"));
            if (!home.isBlank()) candidates.add(Path.of(home, "xampp"));
        } else if (isMac()) {
            candidates.add(Path.of("/Applications/XAMPP/xamppfiles"));
            candidates.add(Path.of("/Applications/XAMPP"));
        } else {
            candidates.add(Path.of("/opt/lampp"));
            candidates.add(Path.of("/opt/xampp"));
            if (!home.isBlank()) candidates.add(Path.of(home, "lampp"));
        }
        return candidates;
    }

    /** Uma pasta é uma instalação válida quando contém os executáveis de start e stop do XAMPP. */
    private static boolean isValidXamppRoot(Path root) {
        if (root == null) return false;
        for (Path executable : requiredExecutables(root)) {
            if (!Files.isRegularFile(executable)) return false;
        }
        return true;
    }

    private static List<Path> requiredExecutables(Path root) {
        if (isWindows()) {
            return List.of(root.resolve("xampp_start.exe"), root.resolve("xampp_stop.exe"));
        }
        // No Linux/macOS o script "lampp" (equivalente ao xampp) controla start/stop.
        return List.of(root.resolve("lampp"));
    }

    private static List<String> startCommand(Path root) {
        if (isWindows()) {
            return List.of(root.resolve("xampp_start.exe").toString());
        }
        return List.of(root.resolve("lampp").toString(), "start");
    }

    private static List<String> stopCommand(Path root) {
        if (isWindows()) {
            return List.of(root.resolve("xampp_stop.exe").toString());
        }
        return List.of(root.resolve("lampp").toString(), "stop");
    }

    private Path promptForXamppRoot(Path suggestion) {
        Supplier<ModernComponentDialog.ModernComponentDialogBuilder<String>> factory = xamppDialogFactory;
        if (factory == null) return null;

        String result = onEventDispatchThread(() -> {
            PathTextField field = new PathTextField(suggestion != null ? suggestion.toString() : "");
            field.setPlaceholder("Pasta onde o XAMPP está instalado");
            return factory.get()
                    .title("Localizar XAMPP")
                    .message("Não encontramos a instalação do XAMPP. Informe a pasta onde ele está instalado "
                            + "(a pasta que contém os executáveis " + describeExecutables() + ").")
                    .type(ModernDialog.Type.QUESTION)
                    .component(field)
                    .confirmText("Selecionar")
                    .cancelText("Cancelar")
                    .validateOnChange()
                    .disableConfirmWhenInvalid(true)
                    .onValidate(ctx -> {
                        Path path = readPath(ctx.component(PathTextField.class));
                        if (path == null) {
                            throw new IllegalArgumentException("Informe o caminho da instalação do XAMPP.");
                        }
                        if (!isValidXamppRoot(path)) {
                            throw new IllegalArgumentException(
                                    "A pasta informada não contém os executáveis do XAMPP (" + describeExecutables() + ").");
                        }
                    })
                    .result(ctx -> {
                        Path path = readPath(ctx.component(PathTextField.class));
                        return path == null ? null : path.toString();
                    })
                    .show();
        });

        if (result == null || result.isBlank()) return null;
        Path chosen = Path.of(result.strip());
        return isValidXamppRoot(chosen) ? chosen : null;
    }

    private static Path readPath(PathTextField field) {
        if (field == null) return null;
        String text = field.getText();
        if (text == null || text.isBlank()) return null;
        return Path.of(text.strip());
    }

    private static String describeExecutables() {
        return isWindows() ? "xampp_start.exe / xampp_stop.exe" : "lampp";
    }

    private static <T> T onEventDispatchThread(Supplier<T> action) {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.get();
        }
        AtomicReference<T> holder = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> holder.set(action.get()));
        } catch (Exception ignored) {
            return null;
        }
        return holder.get();
    }

    private static boolean isWindows() {
        return osName().contains("win");
    }

    private static boolean isMac() {
        String os = osName();
        return os.contains("mac") || os.contains("darwin");
    }

    private static String osName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }

    private RunProcessHandle launchBuiltIn(RunConfigurationData configuration, Path root,
                                           Path php, boolean debug) throws Exception {
        String host = stringProperty(configuration, "host", "localhost");
        int port = intProperty(configuration, "port", 8080);
        List<String> command = new java.util.ArrayList<>();
        command.add(php.toString());
        if (debug) {
            command.add("-dxdebug.mode=debug");
            command.add("-dxdebug.start_with_request=yes");
            command.add("-dxdebug.client_port=" + intProperty(configuration, "xdebugPort",
                    integerSystemProperty("orion.php.xdebugPort", 9003)));
        }
        command.add("-S");
        command.add(host + ":" + port);
        command.add("-t");
        command.add(root.toString());
        Process process = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        ownedProcesses.put(configuration.getType(), process);
        return RunProcessHandle.ofProcess(process);
    }

    private RunProcessHandle launchCli(RunConfigurationData configuration, Path root,
                                       Path php, boolean debug) throws Exception {
        Path file = activeFile;
        if (file == null || !Files.isRegularFile(file)) {
            file = root.resolve("index.php");
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("Nenhum arquivo PHP ativo ou index.php encontrado.");
        }
        List<String> command = new java.util.ArrayList<>();
        command.add(php.toString());
        if (debug) {
            command.add("-dxdebug.mode=debug");
            command.add("-dxdebug.start_with_request=yes");
            command.add("-dxdebug.client_port=" + intProperty(configuration, "port",
                    integerSystemProperty("orion.php.xdebugPort", 9003)));
        }
        command.add(file.toString());
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true);
        if (debug) builder.environment().put("XDEBUG_TRIGGER", "ORION");
        Process process = builder.start();
        ownedProcesses.put(configuration.getType(), process);
        return RunProcessHandle.ofProcess(process);
    }

    private static boolean isApacheRunning() {
        return findApachePid() != null;
    }

    /** Localiza o PID do processo do Apache (httpd/apache2) em execução, se houver. */
    private static Long findApachePid() {
        return ProcessHandle.allProcesses()
                .filter(handle -> handle.info().command().map(PhpRunSupport::isApacheCommand).orElse(false))
                .map(ProcessHandle::pid)
                .findFirst()
                .orElse(null);
    }

    private static boolean isApacheCommand(String command) {
        String lc = command.toLowerCase(Locale.ROOT);
        return lc.endsWith("httpd.exe")
                || lc.endsWith("/httpd") || lc.endsWith("\\httpd")
                || lc.endsWith("/apache2") || lc.endsWith("\\apache2");
    }

    private static RunConfigurationData configuration(String type, String title, Map<String, Object> properties) {
        return RunConfigurationData.builder()
                .type(type)
                .title(title)
                .properties(new LinkedHashMap<>(properties))
                .build();
    }

    private static RunProcessHandle messageHandle(String message) {
        return RunProcessHandle.outputOnly(new ByteArrayInputStream(message.getBytes(StandardCharsets.UTF_8)));
    }

    private static String stringProperty(RunConfigurationData data, String key, String fallback) {
        Object value = data.getProperties().get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static int intProperty(RunConfigurationData data, String key, int fallback) {
        Object value = data.getProperties().get(key);
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(value.toString()); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static int integerSystemProperty(String key, int fallback) {
        try {
            return Integer.parseInt(System.getProperty(key, Integer.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /**
     * Stream de saída controlado pelo ciclo de vida do XAMPP. Diferente do stream de um processo,
     * ele NÃO chega a EOF quando o lançador (xampp_start) encerra: {@link #read} bloqueia até haver
     * mais dados ou até {@link #close()} ser chamado ao pararmos o XAMPP. Assim o IDE mantém o run
     * ativo (e o botão parar habilitado) enquanto o servidor estiver no ar.
     */
    private static final class LiveInputStream extends InputStream {
        private final Object lock = new Object();
        private byte[] data = new byte[0];
        private int position;
        private boolean closed;

        void write(String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            write(bytes, 0, bytes.length);
        }

        void write(byte[] chunk, int offset, int length) {
            if (length <= 0) return;
            synchronized (lock) {
                if (closed) return;
                int remaining = data.length - position;
                byte[] merged = new byte[remaining + length];
                System.arraycopy(data, position, merged, 0, remaining);
                System.arraycopy(chunk, offset, merged, remaining, length);
                data = merged;
                position = 0;
                lock.notifyAll();
            }
        }

        @Override
        public int read() {
            synchronized (lock) {
                while (position >= data.length) {
                    if (closed) return -1;
                    if (!awaitData()) return -1;
                }
                return data[position++] & 0xff;
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (length == 0) return 0;
            synchronized (lock) {
                while (position >= data.length) {
                    if (closed) return -1;
                    if (!awaitData()) return -1;
                }
                int count = Math.min(length, data.length - position);
                System.arraycopy(data, position, buffer, offset, count);
                position += count;
                return count;
            }
        }

        @Override
        public int available() {
            synchronized (lock) {
                return data.length - position;
            }
        }

        @Override
        public void close() {
            synchronized (lock) {
                closed = true;
                lock.notifyAll();
            }
        }

        private boolean awaitData() {
            try {
                lock.wait();
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
