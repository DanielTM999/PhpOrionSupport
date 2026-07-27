package dtm.ide.run;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.api.extension.runconfig.RunProcessHandle;
import dtm.ide.lsp.PhpToolchainService;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PhpRunSupport {
    public static final String TYPE_XAMPP = "php.xampp";
    public static final String TYPE_BUILTIN = "php.builtin";
    public static final String TYPE_CLI = "php.cli";
    public static final String TYPE_XDEBUG_LISTEN = "php.xdebug.listen";
    public static final String TYPE_XDEBUG_CLI = "php.xdebug.cli";
    public static final String TYPE_XDEBUG_BUILTIN = "php.xdebug.builtin";

    private final Map<String, Process> ownedProcesses = new ConcurrentHashMap<>();
    private volatile Path activeFile;

    public void setActiveFile(Path activeFile) {
        this.activeFile = activeFile;
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
        Process process = ownedProcesses.remove(configuration.getType());
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }

    public void stopAll() {
        ownedProcesses.values().forEach(process -> {
            if (process.isAlive()) process.destroy();
        });
        ownedProcesses.clear();
    }

    private RunProcessHandle launchXampp() throws Exception {
        if (isApacheRunning()) {
            return messageHandle("Apache/XAMPP já está em execução. A Orion apenas se anexou ao servidor.\n");
        }
        Path apache = Path.of(System.getProperty("orion.xampp.root", "C:\\xampp"))
                .resolve("apache").resolve("bin").resolve("httpd.exe");
        if (!Files.isRegularFile(apache)) {
            throw new IllegalStateException("Apache do XAMPP não foi encontrado em " + apache);
        }
        Process process = new ProcessBuilder(apache.toString())
                .directory(apache.getParent().toFile())
                .redirectErrorStream(true)
                .start();
        ownedProcesses.put(TYPE_XAMPP, process);
        return RunProcessHandle.ofProcess(process);
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
        return ProcessHandle.allProcesses().anyMatch(handle -> handle.info().command()
                .map(command -> command.toLowerCase().endsWith("httpd.exe")).orElse(false));
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
}
