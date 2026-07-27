package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.prototype.constants.TokenType;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpTokenizerProviderTest {
    @Test
    void tokenizesEmbeddedHtmlAttributesAndPhpVariables() {
        Collection<Token> tokens = new PhpTokenizerProvider().tokenize(
                "<main><?php #[Controller(\"/\")] class Home { public $name = \"ok\"; } ?></main>",
                null
        );
        assertTrue(tokens.stream().anyMatch(token -> PhpTokenizerProvider.TOKEN_HTML_TAG.equals(token.getType())));
        assertTrue(tokens.stream().anyMatch(token -> PhpTokenizerProvider.TOKEN_ATTRIBUTE.equals(token.getType())));
        assertTrue(tokens.stream().anyMatch(token -> PhpTokenizerProvider.TOKEN_VARIABLE.equals(token.getType())));
        assertTrue(tokens.stream().anyMatch(token -> PhpTokenizerProvider.TOKEN_TYPE.equals(token.getType())));
    }

    @Test
    void givesAttributeArgumentsTheirOwnPhpStyles() {
        Collection<Token> tokens = new PhpTokenizerProvider().tokenize(
                "<?php #[Dependency(false)] #[Controller('/users')] class User {}",
                null
        );

        assertEquals(PhpTokenizerProvider.TOKEN_ATTRIBUTE, typeOf(tokens, "Dependency"));
        assertEquals(TokenType.KEYWORD, typeOf(tokens, "false"));
        assertEquals(PhpTokenizerProvider.TOKEN_ATTRIBUTE, typeOf(tokens, "Controller"));
        assertEquals(TokenType.STRING, typeOf(tokens, "'/users'"));
        assertTrue(tokens.stream()
                .filter(token -> "]".equals(token.getText()))
                .allMatch(token -> PhpTokenizerProvider.TOKEN_ATTRIBUTE.equals(token.getType())));
        assertEquals(new java.awt.Color(212, 212, 212),
                new PhpEditorTheme().getColorByToken(TokenType.SYMBOL));
    }

    @Test
    void tokenizesHtmlInsidePhpFilesWithDistinctTagAttributeAndValueStyles() {
        Collection<Token> tokens = new PhpTokenizerProvider().tokenize(
                "<?php echo 'ok'; ?><button class=\"nav-link\" aria-selected=\"false\">Abrir</button>",
                null
        );

        assertEquals(PhpTokenizerProvider.TOKEN_HTML_TAG, typeOf(tokens, "button"));
        assertEquals(PhpTokenizerProvider.TOKEN_HTML_ATTRIBUTE, typeOf(tokens, "class"));
        assertEquals(PhpTokenizerProvider.TOKEN_HTML_ATTRIBUTE, typeOf(tokens, "aria-selected"));
        assertEquals(TokenType.STRING, typeOf(tokens, "\"nav-link\""));
    }

    private static String typeOf(Collection<Token> tokens, String text) {
        return tokens.stream()
                .filter(token -> text.equals(token.getText()))
                .findFirst()
                .orElseThrow()
                .getType();
    }
}
