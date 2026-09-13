package dtm.ide.wizard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class PhpProjectScaffolder {

    static final String DEFAULT_VERSION = "1.0.0";
    static final String DEFAULT_PHP_VERSION = "8.3";
    static final String DEFAULT_LICENSE = "MIT";
    static final String ORIGINS_PACKAGE = "danieltm/origins";

    private PhpProjectScaffolder() {
    }

    record Dependency(String name, String constraint, boolean development) {
        Dependency {
            name = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
            constraint = constraint == null || constraint.isBlank() ? "*" : constraint.trim();
        }
    }

    record ProjectRequest(Path directory, PhpTemplate template, String vendor, String packageName,
                          String version, String description, String namespace, String phpVersion,
                          String license, List<String> modules, List<Dependency> dependencies) {

        ProjectRequest {
            if (directory == null || template == null) {
                throw new IllegalArgumentException("directory and template are required");
            }
            String folder = folderNameOf(directory);
            vendor = blankTo(vendor, "vendor");
            packageName = blankTo(packageName, kebab(folder));
            version = blankTo(version, DEFAULT_VERSION);
            description = description == null ? "" : description.trim();
            namespace = blankTo(namespace, pascal(vendor) + "\\" + pascal(folder));
            phpVersion = blankTo(phpVersion, DEFAULT_PHP_VERSION);
            license = blankTo(license, DEFAULT_LICENSE);
            modules = modules == null || modules.isEmpty() ? List.of("App") : List.copyOf(modules);
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }

        String fullPackageName() {
            return vendor.toLowerCase(Locale.ROOT) + "/" + packageName.toLowerCase(Locale.ROOT);
        }

        String primaryTypeName() {
            return pascal(packageName);
        }

        String namespacePrefix() {
            return namespace.endsWith("\\") ? namespace : namespace + "\\";
        }
    }

    static Path create(ProjectRequest request) throws IOException {
        Path directory = request.directory();
        Files.createDirectories(directory);
        switch (request.template()) {
            case ORIGINS_APPLICATION -> createOrigins(request);
            case COMPOSER_APPLICATION, COMPOSER_LIBRARY -> createComposer(request);
            case PLAIN_PHP -> createPlain(request);
            default -> throw new IllegalStateException(
                    "Template " + request.template() + " nao e gerado localmente.");
        }
        return directory;
    }

    private static void createComposer(ProjectRequest request) throws IOException {
        Path directory = request.directory();
        write(directory.resolve("composer.json"), composerJson(request));
        write(directory.resolve("phpunit.xml"), phpUnitXml());
        write(directory.resolve(".gitignore"), gitIgnore(request.template()));

        String type = request.primaryTypeName();
        Path source = directory.resolve("src");
        Files.createDirectories(source);
        write(source.resolve(type + ".php"), mainClass(request, type));

        Path tests = directory.resolve("tests");
        Files.createDirectories(tests);
        write(tests.resolve(type + "Test.php"), testClass(request, type));

        if (request.template().hasEntryPoint()) {
            Path publicDirectory = directory.resolve("public");
            Files.createDirectories(publicDirectory);
            write(publicDirectory.resolve("index.php"), publicIndex(request, type));
        }
    }

    private static void createOrigins(ProjectRequest request) throws IOException {
        Path directory = request.directory();
        write(directory.resolve("composer.json"), composerJson(request));
        write(directory.resolve(".gitignore"), gitIgnore(request.template()));
        write(directory.resolve("modules.config"), modulesConfig(request));
        write(directory.resolve("serverroutes.json"), serverRoutes(request));
        write(directory.resolve("index.php"), originsIndex(request));
        write(directory.resolve(".htaccess"), htaccess());

        String module = pascal(request.modules().getFirst());
        Path controllers = directory.resolve("src").resolve(module).resolve("Controllers");
        Files.createDirectories(controllers);
        write(controllers.resolve("HomeController.php"), originsController(request, module));
    }

    private static void createPlain(ProjectRequest request) throws IOException {
        Path directory = request.directory();
        Files.createDirectories(directory.resolve("src"));
        write(directory.resolve("index.php"), plainIndex(request));
        write(directory.resolve(".gitignore"), gitIgnore(request.template()));
    }

    static String composerJson(ProjectRequest request) {
        Map<String, String> require = new LinkedHashMap<>();
        require.put("php", ">=" + request.phpVersion());
        if (request.template().isOrigins()) {
            require.put(ORIGINS_PACKAGE, "*");
        }
        Map<String, String> requireDev = new LinkedHashMap<>();
        if (!request.template().isOrigins()) {
            requireDev.put("phpunit/phpunit", "^11.0");
        }
        for (Dependency dependency : request.dependencies()) {
            if (dependency.name().isBlank()) {
                continue;
            }
            if (dependency.development()) {
                requireDev.put(dependency.name(), dependency.constraint());
            } else {
                require.put(dependency.name(), dependency.constraint());
            }
        }

        String prefix = escapeJson(request.namespacePrefix());
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("    \"name\": \"").append(request.fullPackageName()).append("\",\n");
        json.append("    \"description\": \"").append(escapeJson(request.description())).append("\",\n");
        json.append("    \"type\": \"").append(request.template().defaultComposerType()).append("\",\n");
        json.append("    \"license\": \"").append(escapeJson(request.license())).append("\",\n");
        json.append("    \"version\": \"").append(escapeJson(request.version())).append("\",\n");
        json.append("    \"require\": ").append(jsonObject(require, 1)).append(",\n");
        if (!requireDev.isEmpty()) {
            json.append("    \"require-dev\": ").append(jsonObject(requireDev, 1)).append(",\n");
        }
        json.append("    \"autoload\": {\n");
        json.append("        \"psr-4\": {\n");
        json.append("            \"").append(prefix).append("\": \"src/\"\n");
        json.append("        }\n");
        json.append("    },\n");
        if (!request.template().isOrigins()) {
            json.append("    \"autoload-dev\": {\n");
            json.append("        \"psr-4\": {\n");
            json.append("            \"").append(escapeJson(request.namespacePrefix() + "Tests\\"))
                    .append("\": \"tests/\"\n");
            json.append("        }\n");
            json.append("    },\n");
            json.append("    \"scripts\": {\n");
            json.append("        \"test\": \"phpunit\"\n");
            json.append("    },\n");
        }
        json.append("    \"config\": {\n");
        json.append("        \"sort-packages\": true\n");
        json.append("    },\n");
        json.append("    \"minimum-stability\": \"stable\",\n");
        json.append("    \"prefer-stable\": true\n");
        json.append("}\n");
        return json.toString();
    }

    private static String jsonObject(Map<String, String> values, int indentLevel) {
        if (values.isEmpty()) {
            return "{}";
        }
        String indent = "    ".repeat(indentLevel + 1);
        String closing = "    ".repeat(indentLevel);
        StringBuilder builder = new StringBuilder("{\n");
        List<String> keys = new ArrayList<>(values.keySet());
        for (int index = 0; index < keys.size(); index++) {
            String key = keys.get(index);
            builder.append(indent).append('"').append(escapeJson(key)).append("\": \"")
                    .append(escapeJson(values.get(key))).append('"');
            builder.append(index == keys.size() - 1 ? "\n" : ",\n");
        }
        return builder.append(closing).append('}').toString();
    }

    private static String mainClass(ProjectRequest request, String type) {
        return """
                <?php

                declare(strict_types=1);

                namespace %s;

                final class %s
                {
                    public function name(): string
                    {
                        return '%s';
                    }
                }
                """.formatted(request.namespace(), type, request.fullPackageName());
    }

    private static String testClass(ProjectRequest request, String type) {
        return """
                <?php

                declare(strict_types=1);

                namespace %sTests;

                use %s\\%s;
                use PHPUnit\\Framework\\TestCase;

                final class %sTest extends TestCase
                {
                    public function testProjetoCompilaERoda(): void
                    {
                        self::assertSame('%s', (new %s())->name());
                    }
                }
                """.formatted(request.namespacePrefix(), request.namespace(), type, type,
                request.fullPackageName(), type);
    }

    private static String publicIndex(ProjectRequest request, String type) {
        return """
                <?php

                declare(strict_types=1);

                require __DIR__ . '/../vendor/autoload.php';

                use %s\\%s;

                echo (new %s())->name();
                """.formatted(request.namespace(), type, type);
    }

    private static String plainIndex(ProjectRequest request) {
        return """
                <?php

                declare(strict_types=1);

                echo 'Ola, mundo! Projeto %s.';
                """.formatted(request.packageName());
    }

    private static String originsIndex(ProjectRequest request) {
        return """
                <?php

                declare(strict_types=1);

                require __DIR__ . '/vendor/autoload.php';

                use Daniel\\Origins\\Origin;

                Origin::initialize(__DIR__);
                """;
    }

    private static String modulesConfig(ProjectRequest request) {
        StringBuilder builder = new StringBuilder();
        for (String module : request.modules()) {
            String name = pascal(module);
            builder.append('@').append(name).append(" {\n");
            builder.append("    namespace = ").append(request.namespacePrefix()).append(name).append('\n');
            builder.append("    path = src/").append(name).append('\n');
            builder.append("    enabled = true\n");
            builder.append("}\n\n");
        }
        return builder.toString();
    }

    private static String serverRoutes(ProjectRequest request) {
        return """
                {
                    "routes": [
                        {
                            "path": "/",
                            "method": "GET",
                            "handler": "%sControllers\\\\HomeController::index"
                        }
                    ]
                }
                """.formatted(escapeJson(request.namespacePrefix()
                + pascal(request.modules().getFirst()) + "\\"));
    }

    private static String originsController(ProjectRequest request, String module) {
        return """
                <?php

                declare(strict_types=1);

                namespace %s%s\\Controllers;

                use Daniel\\Origins\\Attributes\\Controller;
                use Daniel\\Origins\\Attributes\\Get;

                #[Controller("/")]
                final class HomeController
                {
                    #[Get("/")]
                    public function index(): string
                    {
                        return 'Origins rodando em %s.';
                    }
                }
                """.formatted(request.namespacePrefix(), module, request.fullPackageName());
    }

    private static String htaccess() {
        return """
                RewriteEngine On
                RewriteCond %{REQUEST_FILENAME} !-f
                RewriteCond %{REQUEST_FILENAME} !-d
                RewriteRule ^(.*)$ index.php [QSA,L]
                """;
    }

    private static String phpUnitXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <phpunit xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         bootstrap="vendor/autoload.php"
                         colors="true">
                    <testsuites>
                        <testsuite name="default">
                            <directory>tests</directory>
                        </testsuite>
                    </testsuites>
                    <source>
                        <include>
                            <directory>src</directory>
                        </include>
                    </source>
                </phpunit>
                """;
    }

    private static String gitIgnore(PhpTemplate template) {
        StringBuilder builder = new StringBuilder("/.orion/\n.env\n.DS_Store\n");
        if (template.usesComposer()) {
            builder.append("/vendor/\n.phpunit.result.cache\n.phpunit.cache/\n");
            if (template.isLibrary()) {
                builder.append("composer.lock\n");
            }
        }
        if (template.isOrigins()) {
            builder.append("/log/\n/runtime/\n");
        }
        return builder.toString();
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    static String kebab(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return trimDashes(normalized);
    }

    static String pascal(String value) {
        if (value == null || value.isBlank()) {
            return "App";
        }
        StringBuilder builder = new StringBuilder();
        for (String part : value.trim().split("[^A-Za-z0-9]+")) {
            if (part.isBlank()) {
                continue;
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            builder.append(part.length() > 1 ? part.substring(1) : "");
        }
        return builder.isEmpty() ? "App" : builder.toString();
    }

    private static String trimDashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '-') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '-') {
            end--;
        }
        return value.substring(start, end);
    }

    private static String folderNameOf(Path directory) {
        Path name = directory.getFileName();
        return name == null ? "app" : name.toString();
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(current);
            }
        }
        return builder.toString();
    }
}
