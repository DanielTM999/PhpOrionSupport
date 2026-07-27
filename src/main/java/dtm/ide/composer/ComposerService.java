package dtm.ide.composer;

import dtm.di.annotations.Async;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ComposerService {

    @Async
    public CompletableFuture<Integer> execute(Path composer, Path workingDirectory,
                                              OutputStream output, String... arguments) {
        try {
            if (composer == null) throw new IllegalStateException("Composer não foi encontrado.");
            List<String> command = new ArrayList<>();
            command.add(composer.toString());
            command.addAll(List.of(arguments));
            Process process = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().transferTo(output);
            int exit = process.waitFor();
            output.write(("\nComposer finalizado com código " + exit + ".\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            return CompletableFuture.completedFuture(exit);
        } catch (Exception e) {
            try {
                output.write(("\nFalha ao executar Composer: " + e.getMessage() + "\n")
                        .getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (Exception ignored) {
            }
            return CompletableFuture.failedFuture(e);
        }
    }
}
