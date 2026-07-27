package dtm.ide.lsp;

import dtm.di.annotations.Async;
import dtm.ide.project.PhpProjectConventions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class PhpToolchainService {
    public static final String INTELEPHENSE_VERSION = "1.18.5";

    @Async
    public CompletableFuture<List<String>> ensureIntelephense(Path resourcesRoot) {
        try {
            Path node = findNode();
            if (node == null) throw new IllegalStateException("Node.js 20+ não foi encontrado no PATH.");
            Path installation = resourcesRoot.resolve("php").resolve("intelephense-" + INTELEPHENSE_VERSION);
            Path entry = installation.resolve("node_modules").resolve("intelephense")
                    .resolve("lib").resolve("intelephense.js");
            if (!Files.isRegularFile(entry)) {
                Files.createDirectories(installation);
                Path npm = findExecutable(isWindows() ? "npm.cmd" : "npm");
                if (npm == null) throw new IllegalStateException("npm não foi encontrado no PATH.");
                Process process = new ProcessBuilder(npm.toString(), "install", "--no-audit", "--no-fund",
                        "--prefix", installation.toString(), "intelephense@" + INTELEPHENSE_VERSION)
                        .redirectErrorStream(true)
                        .start();
                process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                if (process.waitFor() != 0 || !Files.isRegularFile(entry)) {
                    throw new IllegalStateException("Falha ao instalar Intelephense " + INTELEPHENSE_VERSION + ".");
                }
            }
            return CompletableFuture.completedFuture(List.of(node.toString(), entry.toString(), "--stdio"));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public Path findPhp(Path projectRoot) {
        List<Path> candidates = new ArrayList<>();
        String configured = System.getProperty("orion.php.executable", "");
        if (!configured.isBlank()) candidates.add(Path.of(configured));
        String xampp = System.getProperty("orion.xampp.root", "C:\\xampp");
        if (!xampp.isBlank()) candidates.add(Path.of(xampp).resolve("php").resolve("php.exe"));
        candidates.add(Path.of("C:\\php\\php_8.3_nts\\php.exe"));
        Path pathPhp = findExecutable(isWindows() ? "php.exe" : "php");
        if (pathPhp != null) candidates.add(pathPhp);
        return candidates.stream().filter(Files::isRegularFile).findFirst()
                .orElseThrow(() -> new IllegalStateException("PHP não foi encontrado."));
    }

    public Path findComposer() {
        String configured = System.getProperty("orion.composer.executable", "");
        if (!configured.isBlank() && Files.isRegularFile(Path.of(configured))) return Path.of(configured);
        Path composer = findExecutable(isWindows() ? "composer.bat" : "composer");
        if (composer != null) return composer;
        Path known = Path.of("C:\\composer\\composer.bat");
        return Files.isRegularFile(known) ? known : null;
    }

    public Path findNode() {
        Path node = findExecutable(isWindows() ? "node.exe" : "node");
        if (node == null) throw new IllegalStateException("Node.js 20+ não foi encontrado no PATH.");
        return node;
    }

    static Path findExecutable(String name) {
        String pathValue = System.getenv("PATH");
        if (pathValue == null) return null;
        for (String directory : pathValue.split(java.io.File.pathSeparator)) {
            try {
                Path candidate = Path.of(directory).resolve(name);
                if (Files.isRegularFile(candidate)) return PhpProjectConventions.normalize(candidate);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
