package dtm.ide.origins;

import java.nio.file.Path;
import java.util.Map;

public record OriginsModule(String name, Path root, Map<String, String> properties) {
    public OriginsModule {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public String property(String name) {
        return properties.getOrDefault(name, "");
    }
}
