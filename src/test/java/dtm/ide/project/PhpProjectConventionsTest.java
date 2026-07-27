package dtm.ide.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PhpProjectConventionsTest {

    @TempDir
    Path temp;

    @Test
    void detectsOriginsApplication() throws Exception {
        Files.writeString(temp.resolve("composer.json"), """
                {"name":"sample/app","require":{"danieltm/origins":"^2.0"}}
                """);
        Files.writeString(temp.resolve("index.php"), "<?php Origin::initialize()->run();");
        PhpProjectDescriptor descriptor = PhpProjectConventions.describe(temp);
        assertNotNull(descriptor);
        assertEquals(PhpProjectKind.ORIGINS_APPLICATION, descriptor.kind());
        assertTrue(descriptor.origins());
    }

    @Test
    void detectsComposerWorkspace() throws Exception {
        for (String name : new String[]{"one", "two", "three"}) {
            Path child = Files.createDirectories(temp.resolve(name));
            Files.writeString(child.resolve("composer.json"), "{\"name\":\"sample/" + name + "\"}");
        }
        PhpProjectDescriptor descriptor = PhpProjectConventions.describe(temp);
        assertNotNull(descriptor);
        assertEquals(PhpProjectKind.PHP_WORKSPACE, descriptor.kind());
        assertEquals(3, descriptor.composerRoots().size());
    }

    @Test
    void delegatesFrontEndAssetsToCooperativeWebkit() {
        assertTrue(PhpProjectConventions.handlesPath(Path.of("index.php")));
        assertTrue(PhpProjectConventions.handlesPath(Path.of("composer.json")));
        assertFalse(PhpProjectConventions.handlesPath(Path.of("app.js")));
        assertFalse(PhpProjectConventions.handlesPath(Path.of("styles.css")));
    }

    @Test
    void recognizesNativeFrameworkRepositoriesWhenAvailable() {
        Path frameworkRoot = Path.of("C:\\Users\\danie\\Documents\\development\\php");
        if (!Files.isDirectory(frameworkRoot)) return;

        for (String project : new String[]{"origins", "TemplateViewer", "ValidationIO"}) {
            PhpProjectDescriptor descriptor = PhpProjectConventions.describe(frameworkRoot.resolve(project));
            assertNotNull(descriptor, project);
            assertTrue(descriptor.origins(), project);
        }
    }
}
