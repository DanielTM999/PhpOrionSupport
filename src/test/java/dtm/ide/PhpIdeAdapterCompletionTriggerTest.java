package dtm.ide;

import dtm.ide.api.project.editor.IdeCompletionContext;
import dtm.ide.api.project.editor.IdeCompletionTriggerKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpIdeAdapterCompletionTriggerTest {

    private static final Path PHP_FILE = Path.of("Service.php");

    private final PhpIdeAdapter adapter = new PhpIdeAdapter();

    @Test
    void onlyRealTriggerCharactersAreAdvertisedToTheHost() {
        Set<Character> triggers = adapter.getCompletionTriggerCharacters();

        assertEquals(Set.of('$', '>', ':', '\\', '#', '<', '/'), triggers);
        assertFalse(triggers.contains('"'), "aspas abriam o popup dentro de strings");
        assertFalse(triggers.contains('\''));
        assertFalse(triggers.contains('-'), "o hifen abria o popup em texto comum");
        assertFalse(triggers.contains('@'));
    }

    @Test
    void typingOnTypingStaysEnabledSoTheHostStillConsultsTheAdapter() {
        assertTrue(adapter.isAutoCompletionOnTypingEnabled(),
                "desligar aqui mataria tambem $ e -> no host");
    }

    @Test
    void lettersAndDigitsNeverOpenThePopup() {
        assertFalse(triggers("<?php\n$service->calc", "$service->calc"));
        assertFalse(triggers("<?php\n$total = 42", "$total = 42"));
        assertFalse(triggers("<?php\nfunction nome_", "function nome_"));
    }

    @Test
    void variablesObjectAccessAndStaticAccessOpenThePopup() {
        assertTrue(triggers("<?php\n$", "$"));
        assertTrue(triggers("<?php\n$service->", "$service->"));
        assertTrue(triggers("<?php\nUser::", "User::"));
        assertTrue(triggers("<?php\nuse Daniel\\", "use Daniel\\"));
        assertTrue(triggers("<?php\n#", "#"));
    }

    @Test
    void looseGreaterThanAndColonDoNotOpenThePopup() {
        assertFalse(triggers("<?php\nif ($a >", "if ($a >"), "comparacao maior que");
        assertFalse(triggers("<?php\nfunction f(): ", "function f():"), "tipo de retorno");
        assertFalse(triggers("<?php\n$x = $a ? 1 :", "$x = $a ? 1 :"), "ternario");
    }

    @Test
    void nothingIsTriggeredInsideStringsOrComments() {
        assertFalse(triggers("<?php\n$s = \"custo: R$", "$s = \"custo: R$"));
        assertFalse(triggers("<?php\n// preco em R$", "// preco em R$"));
        assertFalse(triggers("<?php\n/* $", "/* $"));
    }

    @Test
    void htmlRegionsOpenOnTagCharactersOnly() {
        assertTrue(triggers("<div>\n<", "<div>\n<"));
        assertTrue(triggers("<div>\n</", "<div>\n</"));
        assertFalse(triggers("<a href=\"/", "<a href=\"/"), "dentro de valor de atributo");
        assertFalse(triggers("<div>texto$", "<div>texto$"), "cifrao em texto HTML solto");
    }

    @Test
    void nonPhpFilesAndBrokenContextsAreIgnored() {
        assertFalse(adapter.shouldAutoTriggerCompletion(null));
        assertFalse(adapter.shouldAutoTriggerCompletion(context("$", "$", Path.of("notas.txt"))));
        assertFalse(adapter.shouldAutoTriggerCompletion(new IdeCompletionContext(
                "<?php $", PHP_FILE, 7, 1, 0, "", "", 0, IdeCompletionTriggerKind.TYPING)));
    }

    private boolean triggers(String text, String lineUpToCaret) {
        return adapter.shouldAutoTriggerCompletion(context(text, lineUpToCaret, PHP_FILE));
    }

    private static IdeCompletionContext context(String text, String lineUpToCaret, Path file) {
        int caret = text.length();
        int lineStart = Math.max(text.lastIndexOf('\n') + 1, 0);
        String line = text.substring(lineStart);
        int col = line.length();
        return new IdeCompletionContext(text, file, caret, countLines(text), col, line, "",
                caret, IdeCompletionTriggerKind.TYPING);
    }

    private static int countLines(String text) {
        int lines = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
