package dtm.ide.origins;

import java.nio.file.Path;

public record OriginsEndpoint(
        String module,
        String controller,
        String handler,
        String httpMethod,
        String route,
        Path file,
        int line
) {
}
