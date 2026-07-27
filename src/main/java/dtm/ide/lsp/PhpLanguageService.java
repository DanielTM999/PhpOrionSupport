package dtm.ide.lsp;

import dtm.di.annotations.Async;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class PhpLanguageService {
    private final PhpLanguageServerProcess process;

    public PhpLanguageService(Consumer<Path> diagnosticsListener) {
        this.process = new PhpLanguageServerProcess(diagnosticsListener);
    }

    @Async
    public CompletableFuture<Void> start(List<String> command, Path projectRoot, Path storage) {
        try {
            Files.createDirectories(storage);
            process.start(command, projectRoot, projectRoot, storage);
            if (!process.isRunning()) {
                return CompletableFuture.failedFuture(new IllegalStateException(process.lastError()));
            }
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Async
    public CompletableFuture<Void> stopAsync() {
        process.stop();
        return CompletableFuture.completedFuture(null);
    }

    public PhpLanguageServerProcess process() {
        return process;
    }
}
