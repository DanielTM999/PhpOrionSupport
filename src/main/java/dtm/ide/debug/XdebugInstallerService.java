package dtm.ide.debug;

import dtm.di.annotations.Async;
import dtm.ide.lsp.PhpToolchainService;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XdebugInstallerService {
    private static final URI DOWNLOAD_PAGE = URI.create("https://xdebug.org/download");
    private static final Pattern LINK = Pattern.compile(
            "href=[\"']([^\"']*php_xdebug-[^\"']+\\.dll)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION = Pattern.compile("(?m)^PHP Version\\s*=>\\s*([^\\r\\n]+)");
    private static final Pattern XDEBUG_VERSION = Pattern.compile(
            "(?im)^xdebug(?: support)?\\s*=>\\s*(?:enabled|version\\s*)?\\s*([^\\r\\n]*)");
    private static final String BEGIN = "; BEGIN ORION XDEBUG";
    private static final String END = "; END ORION XDEBUG";

    @Async
    public CompletableFuture<XdebugStatus> inspect(Path php) {
        try {
            return CompletableFuture.completedFuture(inspectNow(php));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Async
    public CompletableFuture<XdebugInstallResult> install(Path php, Path resourcesRoot, int port) {
        try {
            XdebugStatus before = inspectNow(php);
            if (before.loaded()) {
                return CompletableFuture.completedFuture(
                        new XdebugInstallResult(false, before.summary(), null, before));
            }
            if (!before.installable()) {
                throw new IOException(before.summary());
            }

            URI download = selectDownload(before);
            Path downloads = resourcesRoot.resolve("php").resolve("xdebug");
            Files.createDirectories(downloads);
            String name = Path.of(download.getPath()).getFileName().toString();
            Path staged = downloads.resolve(name);
            download(download, staged);

            Path target = before.extensionDirectory().resolve("php_xdebug-orion.dll").normalize();
            if (!target.startsWith(before.extensionDirectory().normalize())) {
                throw new IOException("Destino de extensão Xdebug inválido.");
            }
            validateDll(before.php(), staged);
            Files.createDirectories(before.extensionDirectory());
            Files.copy(staged, target, StandardCopyOption.REPLACE_EXISTING);

            Path backup = backup(before.loadedIni());
            try {
                configure(before.loadedIni(), target, port);
                XdebugStatus after = inspectNow(php);
                if (!after.loaded()) {
                    Files.copy(backup, before.loadedIni(), StandardCopyOption.REPLACE_EXISTING);
                    throw new IOException("O PHP não carregou o Xdebug; o php.ini anterior foi restaurado.");
                }
                return CompletableFuture.completedFuture(new XdebugInstallResult(
                        true, "Xdebug instalado e validado. Reinicie Apache/PHP para aplicar em processos ativos.",
                        backup, after));
            } catch (Exception e) {
                if (Files.isRegularFile(backup)) {
                    Files.copy(backup, before.loadedIni(), StandardCopyOption.REPLACE_EXISTING);
                }
                throw e;
            }
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    XdebugStatus inspectNow(Path php) throws IOException, InterruptedException {
        if (php == null || !Files.isRegularFile(php)) {
            throw new IOException("Executável PHP não encontrado.");
        }
        String info = execute(php, "-i");
        String modules = execute(php, "-m");
        String version = group(VERSION, info);
        Path ini = pathValue(info, "Loaded Configuration File");
        Path extensions = pathValue(info, "extension_dir");
        boolean threadSafe = value(info, "Thread Safety").equalsIgnoreCase("enabled");
        String architecture = value(info, "Architecture");
        String compiler = value(info, "Compiler");
        boolean loaded = modules.lines().anyMatch(line -> line.strip().equalsIgnoreCase("xdebug"));
        String xdebugVersion = loaded ? group(XDEBUG_VERSION, info).strip() : "";
        return new XdebugStatus(php.toAbsolutePath().normalize(), version, ini, extensions,
                threadSafe, architecture, compiler, loaded, xdebugVersion);
    }

    private URI selectDownload(XdebugStatus status) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(DOWNLOAD_PAGE)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Orion-PHP-Adapter")
                .GET().build();
        HttpResponse<String> response = client().send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IOException("xdebug.org retornou HTTP " + response.statusCode());
        }

        String phpLine = majorMinor(status.phpVersion());
        String ts = status.threadSafe() ? "-ts-" : "-nts-";
        String arch = status.architecture().toLowerCase(Locale.ROOT).contains("64") ? "x86_64" : "x86";
        String vs = visualStudioTag(status.compiler());
        List<URI> candidates = new ArrayList<>();
        Matcher matcher = LINK.matcher(response.body());
        while (matcher.find()) {
            String href = matcher.group(1).replace("&amp;", "&");
            String lower = href.toLowerCase(Locale.ROOT);
            if (lower.contains("-" + phpLine + "-") && lower.contains(ts)
                    && lower.contains("-" + arch + ".dll")
                    && (vs.isBlank() || lower.contains("-" + vs + "-"))) {
                candidates.add(DOWNLOAD_PAGE.resolve(href));
            }
        }
        return candidates.stream()
                .sorted(Comparator.comparing(URI::toString).reversed())
                .findFirst()
                .orElseThrow(() -> new IOException(
                        "Não há DLL oficial compatível para PHP " + phpLine + " "
                                + (status.threadSafe() ? "TS" : "NTS") + " " + arch + "."));
    }

    private void download(URI uri, Path destination) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "Orion-PHP-Adapter")
                .GET().build();
        HttpResponse<Path> response = client().send(request,
                HttpResponse.BodyHandlers.ofFile(destination));
        if (response.statusCode() / 100 != 2 || Files.size(destination) < 100_000) {
            Files.deleteIfExists(destination);
            throw new IOException("Falha ao baixar Xdebug: HTTP " + response.statusCode());
        }
    }

    private static void validateDll(Path php, Path dll) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(php.toString(), "-n",
                "-dzend_extension=" + dll, "-r", "echo phpversion('xdebug') ?: 'missing';")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (process.waitFor() != 0 || output.toLowerCase(Locale.ROOT).contains("missing")
                || output.toLowerCase(Locale.ROOT).contains("warning")) {
            throw new IOException("A DLL baixada é incompatível: " + output);
        }
    }

    private static Path backup(Path ini) throws IOException {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        Path backup = ini.resolveSibling(ini.getFileName() + ".orion-backup-" + stamp);
        Files.copy(ini, backup);
        return backup;
    }

    private static void configure(Path ini, Path dll, int port) throws IOException {
        String source = Files.readString(ini);
        int begin = source.indexOf(BEGIN);
        if (begin >= 0) {
            int end = source.indexOf(END, begin);
            source = end < 0 ? source.substring(0, begin)
                    : source.substring(0, begin) + source.substring(end + END.length());
        }
        String block = System.lineSeparator() + BEGIN + System.lineSeparator()
                + "zend_extension=\"" + dll.toString().replace("\\", "/") + "\"" + System.lineSeparator()
                + "xdebug.mode=debug,develop" + System.lineSeparator()
                + "xdebug.start_with_request=trigger" + System.lineSeparator()
                + "xdebug.client_host=127.0.0.1" + System.lineSeparator()
                + "xdebug.client_port=" + Math.clamp(port, 1, 65535) + System.lineSeparator()
                + "xdebug.discover_client_host=0" + System.lineSeparator()
                + END + System.lineSeparator();
        Files.writeString(ini, source.stripTrailing() + block, StandardCharsets.UTF_8);
    }

    private static String execute(Path executable, String argument) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(executable.toString(), argument)
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IOException(output.strip());
        return output;
    }

    private static Path pathValue(String source, String key) {
        String value = value(source, key);
        if (value.isBlank() || value.equalsIgnoreCase("(none)") || value.equalsIgnoreCase("no value")) return null;
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String value(String source, String key) {
        Pattern pattern = Pattern.compile("(?m)^" + Pattern.quote(key)
                + "\\s*=>\\s*([^\\r\\n]+?)(?:\\s*=>.*)?$");
        return group(pattern, source).strip();
    }

    private static String group(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source == null ? "" : source);
        return matcher.find() && matcher.groupCount() > 0 ? matcher.group(1) : "";
    }

    private static String majorMinor(String version) {
        Matcher matcher = Pattern.compile("(\\d+\\.\\d+)").matcher(version == null ? "" : version);
        return matcher.find() ? matcher.group(1) : version;
    }

    private static String visualStudioTag(String compiler) {
        String value = compiler == null ? "" : compiler;
        if (value.contains("2022")) return "vs17";
        if (value.contains("2019")) return "vs16";
        if (value.contains("2017")) return "vc15";
        return "";
    }

    private static HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
