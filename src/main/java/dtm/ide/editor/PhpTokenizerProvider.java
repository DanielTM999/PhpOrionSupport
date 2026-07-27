package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.prototype.constants.TokenType;
import dtm.stools.component.panels.editor.code.provider.TokenClassifierCodeEditorProvider;
import dtm.stools.component.panels.editor.code.provider.TokenizerCodeEditorProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public final class PhpTokenizerProvider implements TokenizerCodeEditorProvider {

    public static final String TOKEN_VARIABLE = "PHP_VARIABLE";
    public static final String TOKEN_TYPE = "PHP_TYPE";
    public static final String TOKEN_FUNCTION = "PHP_FUNCTION";
    public static final String TOKEN_ATTRIBUTE = "PHP_ATTRIBUTE";
    public static final String TOKEN_HTML_TAG = "PHP_HTML_TAG";
    public static final String TOKEN_HTML_ATTRIBUTE = "PHP_HTML_ATTRIBUTE";
    public static final String TOKEN_HTML_ENTITY = "PHP_HTML_ENTITY";
    public static final String TOKEN_HTML_DOCTYPE = "PHP_HTML_DOCTYPE";

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "and", "array", "as", "break", "callable", "case", "catch",
            "class", "clone", "const", "continue", "declare", "default", "do", "echo",
            "else", "elseif", "empty", "enddeclare", "endfor", "endforeach", "endif",
            "endswitch", "endwhile", "enum", "eval", "exit", "extends", "false", "final",
            "finally", "fn", "for", "foreach", "function", "global", "goto", "if",
            "implements", "include", "include_once", "instanceof", "insteadof", "interface",
            "isset", "list", "match", "namespace", "new", "null", "or", "print", "private",
            "protected", "public", "readonly", "require", "require_once", "return", "static",
            "switch", "throw", "trait", "true", "try", "unset", "use", "var", "while",
            "xor", "yield", "yield from"
    );

    @Override
    public boolean supportsIncremental() {
        return false;
    }

    @Override
    public synchronized Collection<Token> tokenize(String text, TokenClassifierCodeEditorProvider classifier) {
        String source = text == null ? "" : text;
        List<Token> tokens = new ArrayList<>(Math.max(16, source.length() / 4));
        boolean php = false;
        int i = 0;
        while (i < source.length()) {
            if (!php) {
                int open = source.indexOf("<?", i);
                int end = open < 0 ? source.length() : open;
                emitHtml(source, i, end, tokens);
                if (open < 0) {
                    break;
                }
                int markerEnd = source.startsWith("<?php", open) ? open + 5
                        : source.startsWith("<?=", open) ? open + 3 : open + 2;
                tokens.add(token(source, open, markerEnd, TokenType.KEYWORD));
                i = markerEnd;
                php = true;
                continue;
            }
            if (source.startsWith("?>", i)) {
                tokens.add(token(source, i, i + 2, TokenType.KEYWORD));
                i += 2;
                php = false;
                continue;
            }
            char c = source.charAt(i);
            if (c == '\r' || c == '\n') {
                int start = i++;
                if (c == '\r' && i < source.length() && source.charAt(i) == '\n') i++;
                tokens.add(token(source, start, i, TokenType.NEWLINE));
            } else if (Character.isWhitespace(c)) {
                int start = i++;
                while (i < source.length() && Character.isWhitespace(source.charAt(i))
                        && source.charAt(i) != '\r' && source.charAt(i) != '\n') i++;
                tokens.add(token(source, start, i, TokenType.WHITESPACE));
            } else if (c == '#' && i + 1 < source.length() && source.charAt(i + 1) == '[') {
                int end = attributeEnd(source, i);
                emitAttribute(source, i, end, tokens, classifier);
                i = end;
            } else if (source.startsWith("//", i) || source.charAt(i) == '#') {
                int start = i;
                i = lineEnd(source, i);
                tokens.add(token(source, start, i, TokenType.COMMENT));
            } else if (source.startsWith("/*", i)) {
                int start = i;
                int close = source.indexOf("*/", i + 2);
                i = close < 0 ? source.length() : close + 2;
                tokens.add(token(source, start, i, TokenType.COMMENT));
            } else if (c == '\'' || c == '"') {
                int start = i;
                i = stringEnd(source, i, c);
                tokens.add(token(source, start, i, TokenType.STRING));
            } else if (c == '$') {
                int start = i++;
                while (i < source.length() && isIdentifierPart(source.charAt(i))) i++;
                tokens.add(token(source, start, i, TOKEN_VARIABLE));
            } else if (Character.isDigit(c)) {
                int start = i++;
                while (i < source.length() && (Character.isDigit(source.charAt(i))
                        || "._xXabcdefABCDEF".indexOf(source.charAt(i)) >= 0)) i++;
                tokens.add(token(source, start, i, TokenType.NUMBER));
            } else if (isIdentifierStart(c)) {
                int start = i++;
                while (i < source.length() && isIdentifierPart(source.charAt(i))) i++;
                String word = source.substring(start, i);
                String type = KEYWORDS.contains(word.toLowerCase()) ? TokenType.KEYWORD
                        : classifyIdentifier(source, start, i, word, classifier);
                tokens.add(token(source, start, i, type));
            } else {
                tokens.add(token(source, i, i + 1, TokenType.SYMBOL));
                i++;
            }
        }
        return tokens;
    }

    private static String classifyIdentifier(String source, int start, int end, String word,
                                             TokenClassifierCodeEditorProvider classifier) {
        int next = skipWhitespace(source, end);
        if (next < source.length() && source.charAt(next) == '(') return TOKEN_FUNCTION;
        if (!word.isEmpty() && Character.isUpperCase(word.charAt(0))) return TOKEN_TYPE;
        String classified = classifier == null ? null : classifier.classify(word);
        return classified == null || classified.isBlank() || TokenType.UNKNOWN.equals(classified)
                ? TokenType.IDENTIFIER : classified;
    }

    private static void emitHtml(String source, int from, int to, List<Token> out) {
        int i = from;
        while (i < to) {
            int start = i;
            char c = source.charAt(i);
            if (source.startsWith("<!--", i)) {
                int end = source.indexOf("-->", i + 4);
                i = end < 0 || end >= to ? to : end + 3;
                out.add(token(source, start, i, TokenType.COMMENT));
            } else if (source.regionMatches(true, i, "<!DOCTYPE", 0, 9)) {
                int end = source.indexOf('>', i + 9);
                i = end < 0 || end >= to ? to : end + 1;
                out.add(token(source, start, i, TOKEN_HTML_DOCTYPE));
            } else if (c == '<') {
                i = emitHtmlTag(source, i, to, out);
            } else if (c == '&') {
                int semicolon = source.indexOf(';', i + 1);
                i = semicolon < 0 || semicolon >= to ? i + 1 : semicolon + 1;
                out.add(token(source, start, i,
                        i > start + 1 ? TOKEN_HTML_ENTITY : TokenType.IDENTIFIER));
            } else if (c == '\r' || c == '\n') {
                i++;
                if (c == '\r' && i < to && source.charAt(i) == '\n') i++;
                out.add(token(source, start, i, TokenType.NEWLINE));
            } else if (Character.isWhitespace(c)) {
                i++;
                while (i < to && Character.isWhitespace(source.charAt(i))
                        && source.charAt(i) != '\r' && source.charAt(i) != '\n') i++;
                out.add(token(source, start, i, TokenType.WHITESPACE));
            } else {
                i++;
                while (i < to && source.charAt(i) != '<' && source.charAt(i) != '&'
                        && !Character.isWhitespace(source.charAt(i))) i++;
                out.add(token(source, start, i, TokenType.IDENTIFIER));
            }
        }
    }

    private static int emitHtmlTag(String source, int from, int to, List<Token> out) {
        int i = from;
        out.add(token(source, i, i + 1, TokenType.SYMBOL));
        i++;
        if (i < to && source.charAt(i) == '/') {
            out.add(token(source, i, i + 1, TokenType.SYMBOL));
            i++;
        }
        while (i < to && Character.isWhitespace(source.charAt(i))) {
            int start = i++;
            while (i < to && Character.isWhitespace(source.charAt(i))) i++;
            out.add(token(source, start, i, containsLineBreak(source, start, i)
                    ? TokenType.NEWLINE : TokenType.WHITESPACE));
        }
        if (i < to && isHtmlNameStart(source.charAt(i))) {
            int start = i++;
            while (i < to && isHtmlNamePart(source.charAt(i))) i++;
            out.add(token(source, start, i, TOKEN_HTML_TAG));
        }
        while (i < to) {
            char c = source.charAt(i);
            if (c == '>') {
                out.add(token(source, i, i + 1, TokenType.SYMBOL));
                return i + 1;
            }
            if (c == '/' && i + 1 < to && source.charAt(i + 1) == '>') {
                out.add(token(source, i, i + 2, TokenType.SYMBOL));
                return i + 2;
            }
            if (Character.isWhitespace(c)) {
                int start = i++;
                while (i < to && Character.isWhitespace(source.charAt(i))) i++;
                out.add(token(source, start, i, containsLineBreak(source, start, i)
                        ? TokenType.NEWLINE : TokenType.WHITESPACE));
            } else if (c == '\'' || c == '"') {
                int start = i;
                i = Math.min(stringEnd(source, i, c), to);
                out.add(token(source, start, i, TokenType.STRING));
            } else if (c == '=') {
                out.add(token(source, i, i + 1, TokenType.SYMBOL));
                i++;
            } else if (isHtmlAttributeStart(c)) {
                int start = i++;
                while (i < to && isHtmlAttributePart(source.charAt(i))) i++;
                out.add(token(source, start, i, TOKEN_HTML_ATTRIBUTE));
            } else {
                out.add(token(source, i, i + 1, TokenType.SYMBOL));
                i++;
            }
        }
        return i;
    }

    private static boolean containsLineBreak(String source, int from, int to) {
        for (int i = from; i < to; i++) {
            if (source.charAt(i) == '\r' || source.charAt(i) == '\n') return true;
        }
        return false;
    }

    private static boolean isHtmlNameStart(char c) {
        return c == '_' || Character.isLetter(c);
    }

    private static boolean isHtmlNamePart(char c) {
        return isHtmlNameStart(c) || Character.isDigit(c) || c == '-' || c == ':' || c == '.';
    }

    private static boolean isHtmlAttributeStart(char c) {
        return isHtmlNameStart(c) || c == '@' || c == ':' || c == '#' || c == '[';
    }

    private static boolean isHtmlAttributePart(char c) {
        return isHtmlNamePart(c) || c == '@' || c == '[' || c == ']' || c == '!';
    }

    private static void emitAttribute(String source, int from, int to, List<Token> out,
                                      TokenClassifierCodeEditorProvider classifier) {
        int i = from;
        out.add(token(source, i, Math.min(i + 2, to), TOKEN_ATTRIBUTE));
        i = Math.min(i + 2, to);
        int parentheses = 0;
        boolean attributeName = true;
        while (i < to) {
            char c = source.charAt(i);
            if (c == '\r' || c == '\n') {
                int start = i++;
                if (c == '\r' && i < to && source.charAt(i) == '\n') i++;
                out.add(token(source, start, i, TokenType.NEWLINE));
            } else if (Character.isWhitespace(c)) {
                int start = i++;
                while (i < to && Character.isWhitespace(source.charAt(i))
                        && source.charAt(i) != '\r' && source.charAt(i) != '\n') i++;
                out.add(token(source, start, i, TokenType.WHITESPACE));
            } else if (c == '\'' || c == '"') {
                int start = i;
                i = Math.min(stringEnd(source, i, c), to);
                out.add(token(source, start, i, TokenType.STRING));
            } else if (c == '$') {
                int start = i++;
                while (i < to && isIdentifierPart(source.charAt(i))) i++;
                out.add(token(source, start, i, TOKEN_VARIABLE));
            } else if (Character.isDigit(c)) {
                int start = i++;
                while (i < to && (Character.isDigit(source.charAt(i))
                        || "._xXabcdefABCDEF".indexOf(source.charAt(i)) >= 0)) i++;
                out.add(token(source, start, i, TokenType.NUMBER));
            } else if (isIdentifierStart(c)) {
                int start = i++;
                while (i < to && isIdentifierPart(source.charAt(i))) i++;
                String word = source.substring(start, i);
                String type;
                if (parentheses == 0 && attributeName) {
                    type = TOKEN_ATTRIBUTE;
                } else if (KEYWORDS.contains(word.toLowerCase())) {
                    type = TokenType.KEYWORD;
                } else {
                    type = classifyIdentifier(source, start, i, word, classifier);
                }
                out.add(token(source, start, i, type));
            } else {
                String symbolType = c == ']' && parentheses == 0
                        ? TOKEN_ATTRIBUTE : TokenType.SYMBOL;
                out.add(token(source, i, i + 1, symbolType));
                if (c == '(') {
                    parentheses++;
                    attributeName = false;
                } else if (c == ')') {
                    parentheses = Math.max(0, parentheses - 1);
                } else if (c == ',' && parentheses == 0) {
                    attributeName = true;
                }
                i++;
            }
        }
    }

    private static int attributeEnd(String source, int start) {
        int squareDepth = 0;
        char quote = 0;
        for (int i = start + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '[') {
                squareDepth++;
            } else if (c == ']' && --squareDepth == 0) {
                return i + 1;
            }
        }
        return source.length();
    }

    private static int lineEnd(String value, int at) {
        int end = value.indexOf('\n', at);
        return end < 0 ? value.length() : end;
    }

    private static int stringEnd(String value, int at, char quote) {
        int i = at + 1;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) i += 2;
            else if (c == quote) return i + 1;
            else i++;
        }
        return i;
    }

    private static int skipWhitespace(String source, int at) {
        int i = at;
        while (i < source.length() && Character.isWhitespace(source.charAt(i))) i++;
        return i;
    }

    private static boolean isIdentifierStart(char c) {
        return c == '_' || Character.isLetter(c);
    }

    private static boolean isIdentifierPart(char c) {
        return c == '_' || Character.isLetterOrDigit(c) || c >= 128;
    }

    private static Token token(String source, int start, int end, String type) {
        return new Token(start, end, type, source.substring(start, end));
    }
}
