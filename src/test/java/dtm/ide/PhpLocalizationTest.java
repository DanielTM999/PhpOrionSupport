package dtm.ide;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpLocalizationTest {
    @Test
    void providesNavigationTranslationsForEverySupportedLocale() throws Exception {
        for (String locale : List.of("pt-BR", "en-US", "es-ES", "fr-FR", "de-DE", "it-IT")) {
            String resource = "languages/" + locale + ".json";
            try (var stream = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(stream, resource);
                String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(json.contains("PhpIdeAdapter.lens.implementations"), resource);
                assertTrue(json.contains("PhpIdeAdapter.popup.implementations"), resource);
                assertTrue(json.contains("PhpIdeAdapter.lens.usages"), resource);
                assertTrue(json.contains("PhpIdeAdapter.popup.usages"), resource);
                assertTrue(json.contains("PhpIdeAdapter.editor.goToDefinition"), resource);
                assertTrue(json.contains("PhpIdeAdapter.editor.goToImplementation"), resource);
                assertTrue(json.contains("PhpIdeAdapter.editor.findUsages"), resource);
                assertTrue(json.contains("PhpIdeAdapter.diagnostic.duplicateConstantName"), resource);
                assertTrue(json.contains("PhpIdeAdapter.diagnostic.duplicateConstantValue"), resource);
            }
        }
    }
}
