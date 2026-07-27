package dtm.ide.origins;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OriginsIndexerTest {

    @TempDir
    Path temp;

    @Test
    void resolvesModuleAreaAndActionPlaceholder() throws Exception {
        Files.writeString(temp.resolve("modules.config"), """
                @modules{
                  @Blog {
                    area = /blog
                    publicArea = /blog/public
                  }
                }
                """);
        Path controller = Files.createDirectories(temp.resolve("src/Blog/Controllers"))
                .resolve("PostController.php");
        Files.writeString(controller, """
                <?php
                namespace Blog\\Controllers;
                #[Controller("{Module.current.area}")]
                class PostController {
                    #[Get("/[action]")]
                    public function list() {}
                    #[Post("/create")]
                    public function create() {}
                }
                """);

        OriginsSnapshot snapshot = new OriginsIndexer().build(temp);
        assertEquals(1, snapshot.modules().size());
        assertEquals(2, snapshot.endpoints().size());
        assertEquals("/blog/list", snapshot.endpoints().getFirst().route());
        assertEquals("/blog/create", snapshot.endpoints().getLast().route());
    }

    @Test
    void indexesRealReferenceProjectWhenAvailable() {
        Path project = Path.of("C:\\xampp\\htdocs");
        if (!Files.isDirectory(project)) return;
        OriginsSnapshot snapshot = new OriginsIndexer().build(project);
        assertEquals(4, snapshot.modules().size());
        assertTrue(snapshot.endpoints().size() >= 70,
                () -> "Endpoints encontrados: " + snapshot.endpoints().size() + ", problemas: " + snapshot.problems());
    }
}
