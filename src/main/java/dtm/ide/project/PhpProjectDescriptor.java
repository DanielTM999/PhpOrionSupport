package dtm.ide.project;

import java.nio.file.Path;
import java.util.List;

public record PhpProjectDescriptor(
        Path root,
        PhpProjectKind kind,
        boolean composerProject,
        boolean origins,
        List<Path> composerRoots
) {
    public PhpProjectDescriptor {
        composerRoots = composerRoots == null ? List.of() : List.copyOf(composerRoots);
    }
}
