package dtm.ide.editor;

import dtm.ide.api.theme.EditorTheme;
import dtm.ide.api.theme.EditorThemeConfig;
import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.prototype.constants.TokenType;

import java.awt.Color;
import java.util.Locale;
import java.util.Set;

public final class PhpEditorTheme implements EditorTheme {
    private static final Set<String> TYPES = Set.of(
            "php", "phtml", "inc", "module", "env", "htaccess", "config"
    );

    @Override
    public EditorThemeConfig getConfigByFileType(String fileType) {
        if (fileType == null) return this;
        String normalized = fileType.toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
        return TYPES.contains(normalized) ? this : null;
    }

    @Override
    public Color getColorByToken(Token token) {
        return token == null ? null : getColorByToken(token.getType());
    }

    @Override
    public Color getColorByToken(String tokenType) {
        if (tokenType == null) return null;
        return switch (tokenType) {
            case PhpTokenizerProvider.TOKEN_VARIABLE -> new Color(156, 220, 254);
            case PhpTokenizerProvider.TOKEN_TYPE -> new Color(78, 201, 176);
            case PhpTokenizerProvider.TOKEN_FUNCTION -> new Color(220, 220, 170);
            case PhpTokenizerProvider.TOKEN_ATTRIBUTE -> new Color(197, 134, 192);
            case PhpTokenizerProvider.TOKEN_HTML_TAG -> new Color(86, 156, 214);
            case PhpTokenizerProvider.TOKEN_HTML_ATTRIBUTE -> new Color(156, 220, 254);
            case PhpTokenizerProvider.TOKEN_HTML_ENTITY -> new Color(215, 186, 125);
            case PhpTokenizerProvider.TOKEN_HTML_DOCTYPE -> new Color(155, 155, 155);
            case PhpConfigTokenizerProvider.TOKEN_KEY -> new Color(156, 220, 254);
            case PhpConfigTokenizerProvider.TOKEN_DIRECTIVE -> new Color(197, 134, 192);
            case PhpConfigTokenizerProvider.TOKEN_SECTION -> new Color(220, 220, 170);
            case PhpConfigTokenizerProvider.TOKEN_VARIABLE -> new Color(78, 201, 176);
            case TokenType.KEYWORD -> new Color(86, 156, 214);
            case TokenType.STRING -> new Color(206, 145, 120);
            case TokenType.NUMBER -> new Color(181, 206, 168);
            case TokenType.COMMENT -> new Color(106, 153, 85);
            case TokenType.SYMBOL -> new Color(212, 212, 212);
            default -> null;
        };
    }
}
