package dtm.ide.wizard;

import dtm.ide.project.PhpProjectConventions;
import dtm.ide.project.PhpProjectDescriptor;
import dtm.ide.project.PhpProjectKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpProjectScaffolderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void composerApplicationIsRecognizedByTheProjectDetector() throws IOException {
        Path project = create(PhpTemplate.COMPOSER_APPLICATION, "minha-app");

        PhpProjectDescriptor descriptor = PhpProjectConventions.describe(project);

        assertNotNull(descriptor, "o detector nao reconheceu o projeto gerado");
        assertEquals(PhpProjectKind.PHP_APPLICATION, descriptor.kind());
        assertTrue(Files.isRegularFile(project.resolve("public").resolve("index.php")));
        assertTrue(Files.isRegularFile(project.resolve("phpunit.xml")));
    }

    @Test
    void composerLibraryHasNoEntryPointAndIgnoresTheLockFile() throws IOException {
        Path project = create(PhpTemplate.COMPOSER_LIBRARY, "minha-lib");

        assertFalse(Files.exists(project.resolve("public")));
        assertTrue(Files.readString(project.resolve(".gitignore")).contains("composer.lock"));
        assertEquals("library", composerJson(project).path("type").asText(""));
    }

    @Test
    void originsApplicationIsDetectedAsAnOriginsProject() throws IOException {
        Path project = create(PhpTemplate.ORIGINS_APPLICATION, "loja");

        PhpProjectDescriptor descriptor = PhpProjectConventions.describe(project);

        assertNotNull(descriptor);
        assertEquals(PhpProjectKind.ORIGINS_APPLICATION, descriptor.kind());
        assertTrue(PhpProjectConventions.isOriginsRoot(project));
        assertTrue(Files.readString(project.resolve("modules.config")).contains("@App {"));
        assertTrue(Files.readString(
                project.resolve("src").resolve("App").resolve("Controllers")
                        .resolve("HomeController.php")).contains("#[Controller"));
    }

    @Test
    void plainPhpHasNoComposerManifest() throws IOException {
        Path project = create(PhpTemplate.PLAIN_PHP, "script");

        assertFalse(Files.exists(project.resolve("composer.json")));
        assertTrue(Files.isRegularFile(project.resolve("index.php")));
        assertTrue(Files.isDirectory(project.resolve("src")));
    }

    @Test
    void theGeneratedComposerJsonIsValidAndCarriesThePsr4Autoload() throws IOException {
        Path project = create(PhpTemplate.COMPOSER_APPLICATION, "minha-app");

        JsonNode composer = composerJson(project);

        assertEquals("vendor/minha-app", composer.path("name").asText(""));
        assertEquals(">=8.3", composer.path("require").path("php").asText(""));
        assertEquals("src/", composer.path("autoload").path("psr-4")
                .path("Vendor\\MinhaApp\\").asText(""));
        assertEquals("tests/", composer.path("autoload-dev").path("psr-4")
                .path("Vendor\\MinhaApp\\Tests\\").asText(""));
    }

    @Test
    void selectedDependenciesLandInRequireAndRequireDev() throws IOException {
        Path directory = workspace.resolve("com-deps");
        PhpProjectScaffolder.create(new PhpProjectScaffolder.ProjectRequest(
                directory, PhpTemplate.COMPOSER_APPLICATION, "vendor", "com-deps",
                "1.0.0", "", "Vendor\\ComDeps", "8.3", "MIT", List.of(),
                List.of(new PhpProjectScaffolder.Dependency("monolog/monolog", "^3.5", false),
                        new PhpProjectScaffolder.Dependency("mockery/mockery", "^1.6", true))));

        JsonNode composer = composerJson(directory);

        assertEquals("^3.5", composer.path("require").path("monolog/monolog").asText(""));
        assertEquals("^1.6", composer.path("require-dev").path("mockery/mockery").asText(""));
    }

    @Test
    void defaultsAreFilledWhenTheRequestOmitsThem() throws IOException {
        Path directory = workspace.resolve("sem-dados");
        PhpProjectScaffolder.ProjectRequest request = new PhpProjectScaffolder.ProjectRequest(
                directory, PhpTemplate.COMPOSER_LIBRARY, null, null, null, null, null, null,
                null, null, null);

        assertEquals("vendor/sem-dados", request.fullPackageName());
        assertEquals("Vendor\\SemDados", request.namespace());
        assertEquals("1.0.0", request.version());
        assertEquals("8.3", request.phpVersion());
        assertEquals("MIT", request.license());
    }

    @Test
    void kebabAndPascalNormalizeTheProjectName() {
        assertEquals("minha-loja", PhpProjectScaffolder.kebab("Minha Loja"));
        assertEquals("minha-loja", PhpProjectScaffolder.kebab("MinhaLoja"));
        assertEquals("MinhaLoja", PhpProjectScaffolder.pascal("minha-loja"));
        assertEquals("App", PhpProjectScaffolder.pascal(""));
    }

    private Path create(PhpTemplate template, String name) throws IOException {
        Path directory = workspace.resolve(name);
        return PhpProjectScaffolder.create(new PhpProjectScaffolder.ProjectRequest(
                directory, template, "vendor", name, "1.0.0", "Projeto de teste",
                "Vendor\\" + PhpProjectScaffolder.pascal(name), "8.3", "MIT",
                List.of("App"), List.of()));
    }

    private static JsonNode composerJson(Path project) throws IOException {
        return MAPPER.readTree(Files.readString(project.resolve("composer.json")));
    }
}
