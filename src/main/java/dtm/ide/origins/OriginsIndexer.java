package dtm.ide.origins;

import dtm.di.annotations.Async;
import dtm.ide.project.PhpProjectConventions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class OriginsIndexer {

    private static final Pattern MODULE_START = Pattern.compile("@([A-Za-z_][\\w]*)\\s*\\{");
    private static final Pattern PROPERTY = Pattern.compile("(?m)^\\s*([\\w.]+)\\s*=\\s*([^\\r\\n}]+)");
    private static final Pattern NAMESPACE = Pattern.compile("(?m)^\\s*namespace\\s+([^;]+);");
    private static final Pattern CLASS = Pattern.compile("\\bclass\\s+([A-Za-z_][\\w]*)");
    private static final Pattern CONTROLLER = Pattern.compile(
            "#\\[Controller(?:\\(\\s*[\"']([^\"']*)[\"']\\s*\\))?\\]");
    private static final Pattern ENDPOINT = Pattern.compile(
            "#\\[(Get|Post|Put|Delete|Patch)\\s*\\(\\s*[\"']([^\"']*)[\"']\\s*\\)\\][\\s\\S]{0,1500}?"
                    + "function\\s+([A-Za-z_][\\w]*)\\s*\\(");

    @Async
    public CompletableFuture<OriginsSnapshot> rebuild(Path projectRoot) {
        return CompletableFuture.completedFuture(build(projectRoot));
    }

    public OriginsSnapshot build(Path projectRoot) {
        Path root = PhpProjectConventions.normalize(projectRoot);
        if (root == null || !Files.isDirectory(root)) {
            return OriginsSnapshot.empty(root);
        }
        List<String> problems = new ArrayList<>();
        Map<String, OriginsModule> modules = parseModules(root, problems);
        List<OriginsEndpoint> endpoints = new ArrayList<>();
        scanPhpRoots(root, modules, endpoints, problems);
        return new OriginsSnapshot(root, modules, endpoints, problems);
    }

    private Map<String, OriginsModule> parseModules(Path root, List<String> problems) {
        Path config = root.resolve("modules.config");
        if (!Files.isRegularFile(config)) {
            return Map.of();
        }
        try {
            String source = Files.readString(config);
            Map<String, OriginsModule> modules = new LinkedHashMap<>();
            Matcher matcher = MODULE_START.matcher(source);
            while (matcher.find()) {
                String name = matcher.group(1);
                if ("modules".equalsIgnoreCase(name) || "global".equalsIgnoreCase(name)) {
                    continue;
                }
                int close = matchingBrace(source, matcher.end() - 1);
                if (close < 0) {
                    problems.add("Bloco do módulo '" + name + "' não foi fechado.");
                    continue;
                }
                String block = source.substring(matcher.end(), close);
                Map<String, String> properties = new LinkedHashMap<>();
                Matcher property = PROPERTY.matcher(block);
                while (property.find()) {
                    properties.put(property.group(1), property.group(2).trim());
                }
                modules.put(name, new OriginsModule(name, root.resolve("src").resolve(name), properties));
            }
            return modules;
        } catch (IOException e) {
            problems.add("Falha ao ler modules.config: " + e.getMessage());
            return Map.of();
        }
    }

    private void scanPhpRoots(Path root, Map<String, OriginsModule> modules,
                              List<OriginsEndpoint> endpoints, List<String> problems) {
        List<Path> roots = new ArrayList<>();
        Path src = root.resolve("src");
        if (Files.isDirectory(src)) roots.add(src);
        if (roots.isEmpty()) roots.add(root);

        for (Path scanRoot : roots) {
            try (Stream<Path> stream = Files.walk(scanRoot)) {
                stream.filter(Files::isRegularFile)
                        .filter(PhpProjectConventions::isPhp)
                        .filter(path -> !containsIgnoredSegment(root, path))
                        .forEach(path -> parsePhp(path, root, modules, endpoints, problems));
            } catch (IOException e) {
                problems.add("Falha ao indexar " + scanRoot + ": " + e.getMessage());
            }
        }
    }

    private void parsePhp(Path file, Path root, Map<String, OriginsModule> modules,
                          List<OriginsEndpoint> endpoints, List<String> problems) {
        try {
            String source = Files.readString(file);
            Matcher controller = CONTROLLER.matcher(source);
            if (!controller.find()) return;
            Matcher classMatcher = CLASS.matcher(source);
            if (!classMatcher.find(controller.end())) return;

            String moduleName = moduleOf(file, root);
            String controllerName = classMatcher.group(1);
            String controllerPath = controller.group(1) == null ? "" : controller.group(1);
            Matcher endpoint = ENDPOINT.matcher(source);
            endpoint.region(classMatcher.end(), source.length());
            while (endpoint.find()) {
                String method = endpoint.group(1).toUpperCase();
                String route = endpoint.group(2);
                String handler = endpoint.group(3);
                String resolved = resolveRoute(controllerPath + route, handler, moduleName, modules);
                endpoints.add(new OriginsEndpoint(
                        moduleName, controllerName, handler, method, resolved,
                        file.toAbsolutePath().normalize(), lineOf(source, endpoint.start())
                ));
            }
        } catch (Exception e) {
            problems.add("Falha ao analisar " + file.getFileName() + ": " + e.getMessage());
        }
    }

    private static String resolveRoute(String route, String handler, String moduleName,
                                       Map<String, OriginsModule> modules) {
        OriginsModule module = modules.get(moduleName);
        String resolved = route;
        if (module != null) {
            resolved = replaceLiteral(resolved, "{Module.current.area}", safeProperty(module, "area"));
            resolved = replaceLiteral(resolved, "{Module.current.publicArea}", safeProperty(module, "publicArea"));
        }
        resolved = replaceLiteral(resolved, "[action]", handler);
        while (resolved.contains("//")) {
            resolved = replaceLiteral(resolved, "//", "/");
        }
        if (resolved.isBlank()) return "/";
        return resolved.startsWith("/") ? resolved : "/" + resolved;
    }

    private static String safeProperty(OriginsModule module, String name) {
        String value = module.property(name);
        return value.length() > 4096 ? value.substring(0, 4096) : value;
    }

    private static String replaceLiteral(String source, String target, String replacement) {
        int at = source.indexOf(target);
        if (at < 0) return source;
        String safeReplacement = replacement == null ? "" : replacement;
        StringBuilder output = new StringBuilder(source.length() - target.length() + safeReplacement.length());
        int previous = 0;
        while (at >= 0) {
            output.append(source, previous, at).append(safeReplacement);
            previous = at + target.length();
            at = source.indexOf(target, previous);
        }
        return output.append(source, previous, source.length()).toString();
    }

    private static String moduleOf(Path file, Path root) {
        Path src = root.resolve("src").toAbsolutePath().normalize();
        Path normalized = file.toAbsolutePath().normalize();
        if (normalized.startsWith(src)) {
            Path relative = src.relativize(normalized);
            if (relative.getNameCount() > 1) return relative.getName(0).toString();
        }
        Matcher namespace = NAMESPACE.matcher(readQuietly(file));
        if (namespace.find()) {
            return namespace.group(1).split("\\\\")[0];
        }
        return "Application";
    }

    private static String readQuietly(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException ignored) {
            return "";
        }
    }

    private static boolean containsIgnoredSegment(Path root, Path path) {
        Path relative;
        try {
            relative = root.relativize(path);
        } catch (Exception e) {
            return false;
        }
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals("vendor") || name.equals("runtime") || name.equals("log")
                    || name.equals(".git") || name.equals("node_modules")) {
                return true;
            }
        }
        return false;
    }

    private static int matchingBrace(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            else if (source.charAt(i) == '}' && --depth == 0) return i;
        }
        return -1;
    }

    private static int lineOf(String source, int offset) {
        int line = 1;
        for (int i = 0; i < Math.min(source.length(), offset); i++) {
            if (source.charAt(i) == '\n') line++;
        }
        return line;
    }
}
