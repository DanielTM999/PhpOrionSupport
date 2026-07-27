package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.prototype.constants.TokenType;
import dtm.stools.component.panels.editor.code.provider.TokenClassifierCodeEditorProvider;
import dtm.stools.component.panels.editor.code.provider.TokenizerCodeEditorProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public final class PhpConfigTokenizerProvider implements TokenizerCodeEditorProvider {
    public enum Mode { ENV, HTACCESS, MODULES }

    public static final String TOKEN_KEY = "PHP_CONFIG_KEY";
    public static final String TOKEN_DIRECTIVE = "PHP_CONFIG_DIRECTIVE";
    public static final String TOKEN_SECTION = "PHP_CONFIG_SECTION";
    public static final String TOKEN_VARIABLE = "PHP_CONFIG_VARIABLE";

    private final Mode mode;

    public PhpConfigTokenizerProvider(Mode mode) {
        this.mode = mode;
    }

    @Override
    public boolean supportsIncremental() {
        return false;
    }

    @Override
    public Collection<Token> tokenize(String text, TokenClassifierCodeEditorProvider classifier) {
        String source = text == null ? "" : text;
        List<Token> output = new ArrayList<>();
        int start = 0;
        while (start < source.length()) {
            int end = source.indexOf('\n', start);
            if (end < 0) end = source.length();
            tokenizeLine(source, start, end, output);
            if (end < source.length()) {
                output.add(token(source, end, end + 1, TokenType.NEWLINE));
                start = end + 1;
            } else {
                start = end;
            }
        }
        return output;
    }

    private void tokenizeLine(String source, int from, int to, List<Token> output) {
        int i = from;
        while (i < to && Character.isWhitespace(source.charAt(i))) i++;
        if (i > from) output.add(token(source, from, i, TokenType.WHITESPACE));
        if (i >= to) return;
        if (source.charAt(i) == '#' || (source.startsWith("//", i) && mode == Mode.MODULES)) {
            output.add(token(source, i, to, TokenType.COMMENT));
            return;
        }
        if (mode == Mode.ENV) {
            tokenizeEnv(source, i, to, output);
        } else if (mode == Mode.HTACCESS) {
            tokenizeHtaccess(source, i, to, output);
        } else {
            tokenizeModules(source, i, to, output);
        }
    }

    private static void tokenizeEnv(String source, int from, int to, List<Token> output) {
        int i = from;
        if (source.startsWith("export ", i)) {
            output.add(token(source, i, i + 6, TokenType.KEYWORD));
            output.add(token(source, i + 6, i + 7, TokenType.WHITESPACE));
            i += 7;
        }
        int equals = source.indexOf('=', i);
        if (equals < 0 || equals >= to) {
            output.add(token(source, i, to, TOKEN_KEY));
            return;
        }
        int keyEnd = equals;
        while (keyEnd > i && Character.isWhitespace(source.charAt(keyEnd - 1))) keyEnd--;
        output.add(token(source, i, keyEnd, TOKEN_KEY));
        if (keyEnd < equals) output.add(token(source, keyEnd, equals, TokenType.WHITESPACE));
        output.add(token(source, equals, equals + 1, TokenType.SYMBOL));
        emitValue(source, equals + 1, to, output);
    }

    private static void tokenizeHtaccess(String source, int from, int to, List<Token> output) {
        int i = from;
        if (source.charAt(i) == '<') {
            output.add(token(source, i, to, TOKEN_SECTION));
            return;
        }
        int directiveEnd = i;
        while (directiveEnd < to && !Character.isWhitespace(source.charAt(directiveEnd))) directiveEnd++;
        output.add(token(source, i, directiveEnd, TOKEN_DIRECTIVE));
        emitValue(source, directiveEnd, to, output);
    }

    private static void tokenizeModules(String source, int from, int to, List<Token> output) {
        int i = from;
        if (source.charAt(i) == '@') {
            int end = i + 1;
            while (end < to && (Character.isLetterOrDigit(source.charAt(end))
                    || source.charAt(end) == '_' || source.charAt(end) == '.')) end++;
            output.add(token(source, i, end, TOKEN_SECTION));
            emitValue(source, end, to, output);
            return;
        }
        int equals = source.indexOf('=', i);
        if (equals >= 0 && equals < to) {
            int keyEnd = equals;
            while (keyEnd > i && Character.isWhitespace(source.charAt(keyEnd - 1))) keyEnd--;
            output.add(token(source, i, keyEnd, TOKEN_KEY));
            if (keyEnd < equals) output.add(token(source, keyEnd, equals, TokenType.WHITESPACE));
            output.add(token(source, equals, equals + 1, TokenType.SYMBOL));
            emitValue(source, equals + 1, to, output);
        } else {
            emitValue(source, i, to, output);
        }
    }

    private static void emitValue(String source, int from, int to, List<Token> output) {
        int i = from;
        while (i < to) {
            int start = i;
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                while (i < to && Character.isWhitespace(source.charAt(i))) i++;
                output.add(token(source, start, i, TokenType.WHITESPACE));
            } else if (c == '\'' || c == '"') {
                i = stringEnd(source, i, to, c);
                output.add(token(source, start, i, TokenType.STRING));
            } else if ((c == '$' && i + 1 < to && source.charAt(i + 1) == '{') || c == '%') {
                char close = c == '$' ? '}' : (i + 1 < to && source.charAt(i + 1) == '{' ? '}' : ' ');
                i += c == '$' ? 2 : 1;
                if (c == '%' && i < to && source.charAt(i) == '{') i++;
                while (i < to && source.charAt(i) != close) i++;
                if (i < to && close != ' ') i++;
                output.add(token(source, start, i, TOKEN_VARIABLE));
            } else if (c == '$') {
                i++;
                while (i < to && (Character.isLetterOrDigit(source.charAt(i))
                        || source.charAt(i) == '_' || source.charAt(i) == '.')) i++;
                output.add(token(source, start, i, TOKEN_VARIABLE));
            } else if (c == '{') {
                int close = source.indexOf('}', i + 1);
                if (close >= 0 && close < to) {
                    i = close + 1;
                    output.add(token(source, start, i, TOKEN_VARIABLE));
                } else {
                    output.add(token(source, i, i + 1, TokenType.SYMBOL));
                    i++;
                }
            } else if ("}[](),".indexOf(c) >= 0) {
                output.add(token(source, i, i + 1, TokenType.SYMBOL));
                i++;
            } else if (Character.isDigit(c)) {
                i++;
                while (i < to && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '.')) i++;
                output.add(token(source, start, i, TokenType.NUMBER));
            } else {
                i++;
                while (i < to && !Character.isWhitespace(source.charAt(i))
                        && "'\"$%{}[](),".indexOf(source.charAt(i)) < 0) i++;
                String value = source.substring(start, i).toLowerCase(Locale.ROOT);
                String type = value.equals("true") || value.equals("false")
                        || value.equals("null") || value.equals("on") || value.equals("off")
                        ? TokenType.KEYWORD : TokenType.IDENTIFIER;
                output.add(token(source, start, i, type));
            }
        }
    }

    private static int stringEnd(String source, int at, int limit, char quote) {
        int i = at + 1;
        while (i < limit) {
            if (source.charAt(i) == '\\' && i + 1 < limit) i += 2;
            else if (source.charAt(i) == quote) return i + 1;
            else i++;
        }
        return i;
    }

    private static Token token(String source, int start, int end, String type) {
        return new Token(start, end, type, source.substring(start, end));
    }
}
