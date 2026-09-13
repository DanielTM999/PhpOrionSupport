package dtm.ide.composer;

import dtm.ide.lsp.PhpToolchainService;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public class ComposerInstallerService {

    public static final String VERSION = "2.8.4";
    static final URI DOWNLOAD = URI.create(
            "https://getcomposer.org/download/" + VERSION + "/composer.phar");
    private static final long MINIMUM_SIZE = 500_000L;

    public List<String> ensure(Path resourcesRoot, PhpToolchainService toolchain) throws IOException {
        Path systemComposer = toolchain.findComposer();
        if (systemComposer != null) {
            return List.of(systemComposer.toString());
        }
        Path php = toolchain.findPhp(null);
        Path phar = ensurePhar(resourcesRoot);
        return List.of(php.toString(), phar.toString());
    }

    public Path ensurePhar(Path resourcesRoot) throws IOException {
        if (resourcesRoot == null) {
            throw new IOException("Pasta de recursos do plugin indisponível.");
        }
        Path installation = resourcesRoot.resolve("php").resolve("composer-" + VERSION);
        Path phar = installation.resolve("composer.phar");
        if (isUsable(phar)) {
            return phar;
        }
        Files.createDirectories(installation);
        download(phar);
        if (!isUsable(phar)) {
            Files.deleteIfExists(phar);
            throw new IOException("Composer " + VERSION + " não pôde ser instalado.");
        }
        return phar;
    }

    private static boolean isUsable(Path phar) {
        try {
            return Files.isRegularFile(phar) && Files.size(phar) >= MINIMUM_SIZE;
        } catch (IOException e) {
            return false;
        }
    }

    private static void download(Path phar) throws IOException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(DOWNLOAD)
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "PhpOrionSupport/1.0")
                .GET()
                .build();
        try {
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(phar));
            if (response.statusCode() / 100 != 2) {
                Files.deleteIfExists(phar);
                throw new IOException("Falha ao baixar o Composer: HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Files.deleteIfExists(phar);
            throw new IOException("Download do Composer interrompido.", e);
        }
    }
}
