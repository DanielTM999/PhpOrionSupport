package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.prototype.constants.TokenType;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PhpConfigTokenizerProviderTest {
    @Test
    void highlightsEnvKeysValuesAndVariables() {
        Collection<Token> tokens = tokenizer(PhpConfigTokenizerProvider.Mode.ENV)
                .tokenize("APP_ENV=production\nDB_PORT=3306\nURL=\"${APP_URL}/api\"\n# local", null);

        assertEquals(PhpConfigTokenizerProvider.TOKEN_KEY, typeOf(tokens, "APP_ENV"));
        assertEquals(TokenType.IDENTIFIER, typeOf(tokens, "production"));
        assertEquals(TokenType.NUMBER, typeOf(tokens, "3306"));
        assertEquals(TokenType.STRING, typeOf(tokens, "\"${APP_URL}/api\""));
        assertEquals(TokenType.COMMENT, typeOf(tokens, "# local"));
    }

    @Test
    void highlightsHtaccessDirectivesSectionsAndVariables() {
        Collection<Token> tokens = tokenizer(PhpConfigTokenizerProvider.Mode.HTACCESS)
                .tokenize("<IfModule mod_rewrite.c>\nRewriteEngine On\nRewriteCond %{REQUEST_URI} !^/public\n</IfModule>", null);

        assertEquals(PhpConfigTokenizerProvider.TOKEN_SECTION,
                typeOf(tokens, "<IfModule mod_rewrite.c>"));
        assertEquals(PhpConfigTokenizerProvider.TOKEN_DIRECTIVE, typeOf(tokens, "RewriteEngine"));
        assertEquals(TokenType.KEYWORD, typeOf(tokens, "On"));
        assertEquals(PhpConfigTokenizerProvider.TOKEN_VARIABLE, typeOf(tokens, "%{REQUEST_URI}"));
    }

    @Test
    void highlightsModulesConfigSectionsAndProperties() {
        Collection<Token> tokens = tokenizer(PhpConfigTokenizerProvider.Mode.MODULES)
                .tokenize("@modules{\n@global{\nviews = $env[\"base.dir\"]/src/$module[\"name\"]\n}\n"
                        + "module.enabled=true\nmodule.path={projectRoot}/src\n}", null);

        assertEquals(PhpConfigTokenizerProvider.TOKEN_SECTION, typeOf(tokens, "@modules"));
        assertEquals(PhpConfigTokenizerProvider.TOKEN_SECTION, typeOf(tokens, "@global"));
        assertEquals(PhpConfigTokenizerProvider.TOKEN_VARIABLE, typeOf(tokens, "$env"));
        assertEquals(PhpConfigTokenizerProvider.TOKEN_VARIABLE, typeOf(tokens, "$module"));
        assertEquals(PhpConfigTokenizerProvider.TOKEN_KEY, typeOf(tokens, "module.enabled"));
        assertEquals(TokenType.KEYWORD, typeOf(tokens, "true"));
        assertEquals(PhpConfigTokenizerProvider.TOKEN_VARIABLE, typeOf(tokens, "{projectRoot}"));
        assertEquals(4, tokens.stream()
                .filter(token -> TokenType.SYMBOL.equals(token.getType()))
                .filter(token -> "{".equals(token.getText()) || "}".equals(token.getText()))
                .count());
    }

    private static PhpConfigTokenizerProvider tokenizer(PhpConfigTokenizerProvider.Mode mode) {
        return new PhpConfigTokenizerProvider(mode);
    }

    private static String typeOf(Collection<Token> tokens, String text) {
        return tokens.stream()
                .filter(token -> text.equals(token.getText()))
                .findFirst()
                .orElseThrow()
                .getType();
    }
}
