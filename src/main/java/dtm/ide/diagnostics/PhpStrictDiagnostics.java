package dtm.ide.diagnostics;

import dtm.stools.component.panels.editor.code.api.Range;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Diagnostics for duplicate class constants, including suspicious repeated
 * values that are valid PHP and therefore are not reported by Intelephense.
 */
public final class PhpStrictDiagnostics {
    public enum Kind {
        DUPLICATE_NAME,
        DUPLICATE_VALUE
    }

    public record Issue(Range range, Kind kind, String constantName, String previousName) {
    }

    public List<Issue> analyze(String source) {
        if (source == null || source.isBlank()) return List.of();
        List<Token> tokens = tokenize(source);
        int[] lineStarts = lineStarts(source);
        List<Issue> issues = new ArrayList<>();

        for (int i = 0; i < tokens.size(); i++) {
            if (!isTypeKeyword(tokens, i)) continue;
            int openBrace = findTypeOpenBrace(tokens, i + 1);
            if (openBrace < 0) continue;
            int closeBrace = matchingBrace(tokens, openBrace);
            if (closeBrace < 0) closeBrace = tokens.size();
            inspectType(tokens, openBrace + 1, closeBrace, lineStarts, issues);
        }
        return List.copyOf(issues);
    }

    private static void inspectType(List<Token> tokens, int start, int end,
                                    int[] lineStarts, List<Issue> issues) {
        Map<String, Declaration> names = new HashMap<>();
        Map<String, Declaration> values = new HashMap<>();
        int braceDepth = 0;

        for (int i = start; i < end; i++) {
            String text = tokens.get(i).text();
            if ("{".equals(text)) {
                braceDepth++;
                continue;
            }
            if ("}".equals(text)) {
                braceDepth = Math.max(0, braceDepth - 1);
                continue;
            }
            if (braceDepth != 0 || !tokens.get(i).isIdentifier("const")) continue;

            int statementEnd = constStatementEnd(tokens, i + 1, end);
            if (statementEnd < 0) continue;
            for (Declaration declaration : declarations(tokens, i + 1, statementEnd)) {
                Declaration sameName = names.putIfAbsent(declaration.name(), declaration);
                if (sameName != null) {
                    issues.add(issue(declaration, Kind.DUPLICATE_NAME, sameName.name(), lineStarts));
                    continue;
                }
                if (declaration.valueKey().isBlank()) continue;
                Declaration sameValue = values.putIfAbsent(declaration.valueKey(), declaration);
                if (sameValue != null) {
                    issues.add(issue(declaration, Kind.DUPLICATE_VALUE, sameValue.name(), lineStarts));
                }
            }
            i = statementEnd;
        }
    }

    private static Issue issue(Declaration declaration, Kind kind, String previousName,
                               int[] lineStarts) {
        Position start = positionAt(lineStarts, declaration.nameStart());
        Position end = positionAt(lineStarts, declaration.nameEnd());
        return new Issue(Range.of(start.line(), start.col(), end.line(), end.col()),
                kind, declaration.name(), previousName);
    }

    private static List<Declaration> declarations(List<Token> tokens, int start, int end) {
        List<Declaration> declarations = new ArrayList<>();
        int segmentStart = start;
        int parens = 0;
        int brackets = 0;
        int braces = 0;
        for (int i = start; i <= end; i++) {
            String text = i == end ? "," : tokens.get(i).text();
            switch (text) {
                case "(" -> parens++;
                case ")" -> parens = Math.max(0, parens - 1);
                case "[" -> brackets++;
                case "]" -> brackets = Math.max(0, brackets - 1);
                case "{" -> braces++;
                case "}" -> braces = Math.max(0, braces - 1);
                default -> {
                }
            }
            if (!",".equals(text) || parens != 0 || brackets != 0 || braces != 0) continue;
            Declaration declaration = declaration(tokens, segmentStart, i);
            if (declaration != null) declarations.add(declaration);
            segmentStart = i + 1;
        }
        return declarations;
    }

    private static Declaration declaration(List<Token> tokens, int start, int end) {
        int equals = -1;
        int parens = 0;
        int brackets = 0;
        int braces = 0;
        for (int i = start; i < end; i++) {
            String text = tokens.get(i).text();
            switch (text) {
                case "(" -> parens++;
                case ")" -> parens = Math.max(0, parens - 1);
                case "[" -> brackets++;
                case "]" -> brackets = Math.max(0, brackets - 1);
                case "{" -> braces++;
                case "}" -> braces = Math.max(0, braces - 1);
                case "=" -> {
                    if (parens == 0 && brackets == 0 && braces == 0) equals = i;
                }
                default -> {
                }
            }
            if (equals >= 0) break;
        }
        if (equals < 0) return null;

        Token name = null;
        for (int i = equals - 1; i >= start; i--) {
            if (tokens.get(i).kind() == TokenKind.IDENTIFIER) {
                name = tokens.get(i);
                break;
            }
        }
        if (name == null) return null;

        StringBuilder value = new StringBuilder();
        for (int i = equals + 1; i < end; i++) value.append(tokens.get(i).text());
        return new Declaration(name.text(), name.start(), name.end(), value.toString());
    }

    private static int constStatementEnd(List<Token> tokens, int start, int end) {
        int parens = 0;
        int brackets = 0;
        int braces = 0;
        for (int i = start; i < end; i++) {
            switch (tokens.get(i).text()) {
                case "(" -> parens++;
                case ")" -> parens = Math.max(0, parens - 1);
                case "[" -> brackets++;
                case "]" -> brackets = Math.max(0, brackets - 1);
                case "{" -> braces++;
                case "}" -> braces = Math.max(0, braces - 1);
                case ";" -> {
                    if (parens == 0 && brackets == 0 && braces == 0) return i;
                }
                default -> {
                }
            }
        }
        return -1;
    }

    private static boolean isTypeKeyword(List<Token> tokens, int at) {
        Token token = tokens.get(at);
        if (!(token.isIdentifier("class") || token.isIdentifier("interface")
                || token.isIdentifier("trait") || token.isIdentifier("enum"))) {
            return false;
        }
        return at == 0 || !"::".equals(tokens.get(at - 1).text());
    }

    private static int findTypeOpenBrace(List<Token> tokens, int start) {
        for (int i = start; i < tokens.size(); i++) {
            String text = tokens.get(i).text();
            if ("{".equals(text)) return i;
            if (";".equals(text)) return -1;
        }
        return -1;
    }

    private static int matchingBrace(List<Token> tokens, int open) {
        int depth = 0;
        for (int i = open; i < tokens.size(); i++) {
            if ("{".equals(tokens.get(i).text())) depth++;
            else if ("}".equals(tokens.get(i).text()) && --depth == 0) return i;
        }
        return -1;
    }

    private static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (source.startsWith("//", i)
                    || (c == '#' && (i + 1 >= source.length() || source.charAt(i + 1) != '['))) {
                int newline = source.indexOf('\n', i + 1);
                i = newline < 0 ? source.length() : newline + 1;
            } else if (source.startsWith("/*", i)) {
                int close = source.indexOf("*/", i + 2);
                i = close < 0 ? source.length() : close + 2;
            } else if (c == '\'' || c == '"') {
                int start = i;
                i = stringEnd(source, i, c);
                tokens.add(new Token(TokenKind.STRING, source.substring(start, i), start, i));
            } else if (identifierStart(c)) {
                int start = i++;
                while (i < source.length() && identifierPart(source.charAt(i))) i++;
                tokens.add(new Token(TokenKind.IDENTIFIER, source.substring(start, i), start, i));
            } else if (Character.isDigit(c)) {
                int start = i++;
                while (i < source.length()) {
                    char number = source.charAt(i);
                    if (!Character.isLetterOrDigit(number) && number != '.' && number != '_') break;
                    i++;
                }
                tokens.add(new Token(TokenKind.NUMBER, source.substring(start, i), start, i));
            } else {
                int start = i++;
                String symbol;
                if (i < source.length() && ((c == ':' && source.charAt(i) == ':')
                        || (c == '=' && source.charAt(i) == '>'))) {
                    i++;
                    symbol = source.substring(start, i);
                } else {
                    symbol = String.valueOf(c);
                }
                tokens.add(new Token(TokenKind.SYMBOL, symbol, start, i));
            }
        }
        return tokens;
    }

    private static int stringEnd(String source, int at, char quote) {
        int i = at + 1;
        while (i < source.length()) {
            if (source.charAt(i) == '\\' && i + 1 < source.length()) i += 2;
            else if (source.charAt(i) == quote) return i + 1;
            else i++;
        }
        return i;
    }

    private static boolean identifierStart(char c) {
        return c == '_' || Character.isLetter(c) || c >= 0x80;
    }

    private static boolean identifierPart(char c) {
        return identifierStart(c) || Character.isDigit(c);
    }

    private static int[] lineStarts(String source) {
        int count = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') count++;
        }
        int[] starts = new int[count];
        int line = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') starts[line++] = i + 1;
        }
        return starts;
    }

    private static Position positionAt(int[] starts, int offset) {
        int low = 0;
        int high = starts.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (starts[middle] <= offset) low = middle + 1;
            else high = middle - 1;
        }
        int line = Math.max(0, high);
        return new Position(line, offset - starts[line]);
    }

    private enum TokenKind {
        IDENTIFIER,
        STRING,
        NUMBER,
        SYMBOL
    }

    private record Token(TokenKind kind, String text, int start, int end) {
        boolean isIdentifier(String value) {
            return kind == TokenKind.IDENTIFIER && text.equalsIgnoreCase(value);
        }
    }

    private record Declaration(String name, int nameStart, int nameEnd, String valueKey) {
    }

    private record Position(int line, int col) {
    }
}
