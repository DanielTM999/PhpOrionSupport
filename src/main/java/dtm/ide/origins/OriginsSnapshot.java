package dtm.ide.origins;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record OriginsSnapshot(
        Path projectRoot,
        Map<String, OriginsModule> modules,
        List<OriginsEndpoint> endpoints,
        List<String> problems
) {
    public OriginsSnapshot {
        modules = modules == null ? Map.of() : Map.copyOf(modules);
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    public static OriginsSnapshot empty(Path root) {
        return new OriginsSnapshot(root, Map.of(), List.of(), List.of());
    }
}
