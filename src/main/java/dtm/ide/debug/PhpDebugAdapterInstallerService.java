package dtm.ide.debug;

import dtm.di.annotations.Async;
import dtm.ide.lsp.PhpToolchainService;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class PhpDebugAdapterInstallerService {
    public static final String VERSION = "1.40.1";
    static final URI PACKAGE = URI.create(
            "https://marketplace.visualstudio.com/_apis/public/gallery/publishers/xdebug/"
                    + "vsextensions/php-debug/" + VERSION + "/vspackage");

    @Async
    public CompletableFuture<List<String>> ensure(Path resourcesRoot, PhpToolchainService toolchain) {
        try {
            Path node = toolchain.findNode();
            Path installation = resourcesRoot.resolve("php").resolve("debug-adapter-" + VERSION);
            Path entry = installation.resolve("extension").resolve("out").resolve("phpDebug.js");
            if (!Files.isRegularFile(entry)) {
                Files.createDirectories(installation);
                Path archive = installation.resolve("php-debug-" + VERSION + ".vsix");
                download(archive);
                extract(archive, installation);
            }
            if (!Files.isRegularFile(entry)) {
                throw new IOException("O pacote Xdebug PHP Debug não contém extension/out/phpDebug.js.");
            }
            return CompletableFuture.completedFuture(List.of(node.toString(), entry.toString()));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static void download(Path archive) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(PACKAGE)
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "Orion-PHP-Adapter")
                .GET().build();
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(archive));
        if (response.statusCode() / 100 != 2 || Files.size(archive) < 100_000) {
            Files.deleteIfExists(archive);
            throw new IOException("Falha ao baixar o adaptador PHP Debug: HTTP " + response.statusCode());
        }
    }

    private static void extract(Path archive, Path destination) throws IOException {
        try (InputStream input = Files.newInputStream(archive);
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = destination.resolve(entry.getName().replace('/', java.io.File.separatorChar))
                        .normalize();
                if (!target.startsWith(destination.normalize())) {
                    throw new IOException("Entrada VSIX inválida: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
    }
}
