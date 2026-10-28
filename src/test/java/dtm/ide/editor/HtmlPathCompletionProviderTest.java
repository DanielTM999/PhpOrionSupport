package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlPathCompletionProviderTest {

    @TempDir
    Path root;
    Path currentFile;
    private final HtmlPathCompletionProvider provider = new HtmlPathCompletionProvider();

    @BeforeEach
    void createWebRoot() throws IOException {
        Path images = Files.createDirectories(root.resolve("src/CautcarMain/views/images"));
        Files.createFile(images.resolve("a.png"));
        Files.createFile(images.resolve("b.jpg"));
        Files.createDirectories(images.resolve("parceiros"));
        currentFile = Files.createDirectories(root.resolve("src/CautcarVistorias/views/Index"))
                .resolve("index.php");
    }

    private List<AutoCompleteItem> complete(String source) {
        return provider.suggestions(source, source.length(), currentFile, root);
    }

    @Test
    void resolvesLeadingSlashAgainstProjectRoot() {
        List<AutoCompleteItem> items = complete(
                "<?php $x = 1; ?>\n<img src=\"/src/CautcarMain/views/images/");
        assertEquals(List.of("parceiros/", "a.png", "b.jpg"),
                items.stream().map(AutoCompleteItem::insertText).toList());
    }

    @Test
    void filtersByTypedNameAndInsertsWholeName() {
        List<AutoCompleteItem> items = complete("<img alt=\"x\" src='/src/CautcarMain/views/images/a");
        assertEquals(1, items.size());
        assertEquals("a.png", items.getFirst().insertText());
        assertEquals("/src/CautcarMain/views/images/a.png", items.getFirst().label());
    }

    @Test
    void resolvesRelativePathsAgainstCurrentFile() {
        List<AutoCompleteItem> items = complete("<a href=\"../../../CautcarMain/views/");
        assertTrue(items.stream().anyMatch(item -> "images/".equals(item.insertText())));
    }

    @Test
    void ignoresUrlsPhpAndPathsOutsideRoot() {
        assertTrue(complete("<img src=\"https://cdn/").isEmpty());
        assertTrue(complete("<img src=\"//cdn/").isEmpty());
        assertTrue(complete("<img src=\"/../../").isEmpty());
        assertTrue(complete("<img class=\"/src/").isEmpty());
        assertTrue(complete("<img src=\"/src/CautcarMain/views/images/\" ").isEmpty());
    }
}
