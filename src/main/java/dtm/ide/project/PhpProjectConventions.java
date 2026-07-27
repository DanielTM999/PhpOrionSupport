package dtm.ide.project;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

public final class PhpProjectConventions {

    public static final String PROJECT_TYPE = "PHP";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> PHP_EXTENSIONS = Set.of("php", "phtml", "inc", "module");
    private static final Set<String> CONFIG_FILES = Set.of(
            "composer.json", "composer.lock", "modules.config", "serverroutes.json",
            ".env", ".htaccess", "phpunit.xml", "phpunit.xml.dist"
    );
    private static final Set<String> IGNORED_FOLDERS = Set.of(
            ".git", ".idea", ".orion", "node_modules", "runtime", "log"
    );

    private PhpProjectConventions() {
    }

    public static boolean supports(Path path) {
        return describe(path) != null;
    }

    public static PhpProjectDescriptor describe(Path input) {
        Path root = projectRoot(input);
        if (root == null || !Files.isDirectory(root)) {
            return null;
        }

        List<Path> composerRoots = discoverComposerRoots(root);
        boolean composerProject = Files.isRegularFile(root.resolve("composer.json"));
        boolean origins = composerRoots.stream().anyMatch(PhpProjectConventions::isOriginsRoot)
                || isOriginsRoot(root);
        boolean hasPhp = hasPhpNearRoot(root);

        if (!composerProject && composerRoots.isEmpty() && !hasPhp) {
            return null;
        }

        PhpProjectKind kind;
        if (!composerProject && composerRoots.size() > 1) {
            kind = PhpProjectKind.PHP_WORKSPACE;
        } else if (origins && isApplicationRoot(root)) {
            kind = PhpProjectKind.ORIGINS_APPLICATION;
        } else if (isApplicationRoot(root)) {
            kind = PhpProjectKind.PHP_APPLICATION;
        } else {
            kind = PhpProjectKind.PHP_LIBRARY;
        }
        return new PhpProjectDescriptor(root, kind, composerProject, origins, composerRoots);
    }

    public static Path projectRoot(Path input) {
        if (input == null) {
            return null;
        }
        Path normalized = normalize(input);
        return Files.isDirectory(normalized) ? normalized : normalized.getParent();
    }

    public static boolean handlesPath(Path path) {
        if (path == null) {
            return true;
        }
        if (Files.isDirectory(path)) {
            return true;
        }
        String fileName = fileName(path);
        return isPhp(path) || CONFIG_FILES.contains(fileName);
    }

    public static boolean isPhp(Path path) {
        return PHP_EXTENSIONS.contains(extensionOf(path));
    }

    public static boolean isComposerFile(Path path) {
        String name = fileName(path);
        return "composer.json".equals(name) || "composer.lock".equals(name);
    }

    public static boolean isOriginsConfig(Path path) {
        String name = fileName(path);
        return "modules.config".equals(name) || "serverroutes.json".equals(name);
    }

    public static boolean isIgnoredFolder(Path path) {
        return path != null && path.getFileName() != null
                && IGNORED_FOLDERS.contains(path.getFileName().toString().toLowerCase(Locale.ROOT));
    }

    public static List<Path> discoverComposerRoots(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        Set<Path> roots = new HashSet<>();
        if (Files.isRegularFile(root.resolve("composer.json"))) {
            roots.add(normalize(root));
        }
        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .filter(child -> !isIgnoredFolder(child))
                    .filter(child -> Files.isRegularFile(child.resolve("composer.json")))
                    .map(PhpProjectConventions::normalize)
                    .forEach(roots::add);
        } catch (IOException ignored) {
        }
        return roots.stream().sorted(Comparator.comparing(Path::toString)).toList();
    }

    public static boolean isOriginsRoot(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return false;
        }
        if (Files.isRegularFile(root.resolve("modules.config"))) {
            return true;
        }
        Path composer = root.resolve("composer.json");
        if (Files.isRegularFile(composer)) {
            try {
                JsonNode json = MAPPER.readTree(Files.readString(composer));
                if (containsOriginsPackage(json.path("require"))
                        || containsOriginsPackage(json.path("require-dev"))) {
                    return true;
                }
                String packageName = json.path("name").asText("");
                if (packageName.equalsIgnoreCase("danieltm/origins")
                        || packageName.equalsIgnoreCase("danieltm/template_viewer")
                        || packageName.equalsIgnoreCase("danieltm/validation-io")) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        Path index = root.resolve("index.php");
        if (Files.isRegularFile(index)) {
            try {
                String content = Files.readString(index);
                return content.contains("Origin::initialize") || content.contains("Daniel\\Origins");
            } catch (IOException ignored) {
            }
        }
        return false;
    }

    private static boolean containsOriginsPackage(JsonNode node) {
        return node != null && node.isObject() && (
                node.has("danieltm/origins")
                        || node.has("danieltm/template_viewer")
                        || node.has("danieltm/validation-io")
        );
    }

    private static boolean isApplicationRoot(Path root) {
        return Files.isRegularFile(root.resolve("index.php"))
                || Files.isDirectory(root.resolve("public"))
                || Files.isRegularFile(root.resolve(".htaccess"));
    }

    private static boolean hasPhpNearRoot(Path root) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(root);
        Path src = root.resolve("src");
        if (Files.isDirectory(src)) {
            candidates.add(src);
        }
        for (Path candidate : candidates) {
            try (Stream<Path> stream = Files.walk(candidate, candidate.equals(root) ? 2 : 3)) {
                if (stream.filter(Files::isRegularFile).anyMatch(PhpProjectConventions::isPhp)) {
                    return true;
                }
            } catch (IOException ignored) {
            }
        }
        return false;
    }

    public static String extensionOf(Path path) {
        String name = fileName(path);
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);
    }

    private static String fileName(Path path) {
        return path == null || path.getFileName() == null
                ? ""
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
    }

    public static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }
}
