package dtm.ide.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpCaretContextTest {

    @Test
    void codeOutsideStringsAndCommentsIsNotMasked() {
        String source = "<?php\n$user = new User();\n";

        assertFalse(PhpCaretContext.inStringOrComment(source, source.indexOf("new")));
        assertFalse(PhpCaretContext.inStringOrComment(source, source.indexOf("$user")));
    }

    @Test
    void doubleAndSingleQuotedStringsAreMasked() {
        String source = "<?php\n$name = \"algum texto\";\n$other = 'outro';\n";

        assertTrue(PhpCaretContext.inStringOrComment(source, source.indexOf("algum")));
        assertTrue(PhpCaretContext.inStringOrComment(source, source.indexOf("outro")));
        assertFalse(PhpCaretContext.inStringOrComment(source, source.indexOf("$other")));
    }

    @Test
    void escapedQuotesDoNotCloseTheString() {
        String source = "<?php\n$value = \"aspas \\\" ainda dentro\";\n$fora = 1;\n";

        assertTrue(PhpCaretContext.inStringOrComment(source, source.indexOf("ainda")));
        assertFalse(PhpCaretContext.inStringOrComment(source, source.indexOf("$fora")));
    }

    @Test
    void lineAndBlockCommentsAreMasked() {
        String source = "<?php\n// comentario de linha\n/* bloco */\n$depois = 1;\n";

        assertTrue(PhpCaretContext.inStringOrComment(source, source.indexOf("comentario")));
        assertTrue(PhpCaretContext.inStringOrComment(source, source.indexOf("bloco")));
        assertFalse(PhpCaretContext.inStringOrComment(source, source.indexOf("$depois")));
    }

    @Test
    void hashStartsACommentButNeverAnAttribute() {
        String comment = "<?php\n# comentario shell\n$valor = 1;\n";
        assertTrue(PhpCaretContext.inStringOrComment(comment, comment.indexOf("shell")));

        String attribute = "<?php\n#[Controller(\"/\")]\nclass Home {}\n";
        assertFalse(PhpCaretContext.inStringOrComment(attribute, attribute.indexOf("Controller")));
        assertFalse(PhpCaretContext.inStringOrComment(attribute, attribute.indexOf("class")));
    }

    @Test
    void heredocBodyIsMaskedUntilItsClosingLabel() {
        String source = "<?php\n$html = <<<HTML\n  conteudo livre\nHTML;\n$depois = 1;\n";

        assertTrue(PhpCaretContext.inStringOrComment(source, source.indexOf("conteudo")));
        assertFalse(PhpCaretContext.inStringOrComment(source, source.indexOf("$depois")));
    }

    @Test
    void htmlRegionsOutsidePhpAreNeverMasked() {
        String source = "<div class=\"card\">\n<?php $x = 1; ?>\n</div>";

        assertFalse(PhpCaretContext.inStringOrComment(source, source.indexOf("card")));
        assertFalse(PhpCaretContext.inStringOrComment(source, source.indexOf("$x")));
    }

    @Test
    void htmlAttributeValuesAreDetected() {
        String source = "<a href=\"/home\" class=\"link\">";

        assertTrue(PhpCaretContext.insideHtmlAttributeValue(source, source.indexOf("/home") + 2));
        assertFalse(PhpCaretContext.insideHtmlAttributeValue(source, source.indexOf("class")));
        assertFalse(PhpCaretContext.insideHtmlAttributeValue(source, source.length()));
    }

    @Test
    void emptyAndNullInputAreSafe() {
        assertFalse(PhpCaretContext.inStringOrComment(null, 3));
        assertFalse(PhpCaretContext.inStringOrComment("", 0));
        assertFalse(PhpCaretContext.insideHtmlAttributeValue(null, 1));
        assertFalse(PhpCaretContext.inStringOrComment("<?php $a", 999));
    }
}
