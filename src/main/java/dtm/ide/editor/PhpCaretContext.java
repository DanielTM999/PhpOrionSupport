package dtm.ide.editor;

public final class PhpCaretContext {

    private PhpCaretContext() {
    }

    public static boolean inStringOrComment(String text, int offset) {
        if (text == null) {
            return false;
        }
        int end = Math.clamp(offset, 0, text.length());
        boolean php = false;
        int cursor = 0;
        while (cursor < end) {
            if (!php) {
                int open = text.indexOf("<?", cursor);
                if (open < 0 || open >= end) {
                    return false;
                }
                cursor = open + 2;
                if (text.startsWith("php", cursor)) {
                    cursor += 3;
                } else if (cursor < text.length() && text.charAt(cursor) == '=') {
                    cursor++;
                }
                php = true;
                continue;
            }
            char current = text.charAt(cursor);
            if (current == '?' && cursor + 1 < text.length() && text.charAt(cursor + 1) == '>') {
                php = false;
                cursor += 2;
                continue;
            }
            if (current == '\'' || current == '"') {
                int close = closingQuote(text, cursor + 1, current);
                if (close < 0 || close >= end) {
                    return true;
                }
                cursor = close + 1;
                continue;
            }
            if (startsLineComment(text, cursor)) {
                int newline = text.indexOf('\n', cursor);
                if (newline < 0 || newline >= end) {
                    return true;
                }
                cursor = newline + 1;
                continue;
            }
            if (current == '/' && cursor + 1 < text.length() && text.charAt(cursor + 1) == '*') {
                int close = text.indexOf("*/", cursor + 2);
                if (close < 0 || close + 2 > end) {
                    return true;
                }
                cursor = close + 2;
                continue;
            }
            if (text.startsWith("<<<", cursor)) {
                int close = heredocEnd(text, cursor);
                if (close < 0 || close >= end) {
                    return true;
                }
                cursor = close;
                continue;
            }
            cursor++;
        }
        return false;
    }

    public static boolean insideHtmlAttributeValue(String text, int offset) {
        if (text == null || offset <= 0) {
            return false;
        }
        int end = Math.min(offset, text.length());
        int tagStart = text.lastIndexOf('<', end - 1);
        if (tagStart < 0 || text.lastIndexOf('>', end - 1) > tagStart) {
            return false;
        }
        char quote = 0;
        for (int i = tagStart + 1; i < end; i++) {
            char current = text.charAt(i);
            if (quote == 0 && (current == '\'' || current == '"')) {
                quote = current;
            } else if (quote == current) {
                quote = 0;
            }
        }
        return quote != 0;
    }

    public static boolean isAttributeStart(String text, int offset) {
        if (text == null || offset <= 0 || offset > text.length()) {
            return false;
        }
        return text.charAt(offset - 1) == '#';
    }

    private static boolean startsLineComment(String text, int cursor) {
        char current = text.charAt(cursor);
        if (current == '/') {
            return cursor + 1 < text.length() && text.charAt(cursor + 1) == '/';
        }
        if (current != '#') {
            return false;
        }
        return cursor + 1 >= text.length() || text.charAt(cursor + 1) != '[';
    }

    private static int closingQuote(String text, int from, char quote) {
        for (int i = from; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '\\') {
                i++;
                continue;
            }
            if (current == quote) {
                return i;
            }
        }
        return -1;
    }

    private static int heredocEnd(String text, int start) {
        int cursor = start + 3;
        while (cursor < text.length() && (text.charAt(cursor) == ' ' || text.charAt(cursor) == '\t')) {
            cursor++;
        }
        char quote = 0;
        if (cursor < text.length() && (text.charAt(cursor) == '"' || text.charAt(cursor) == '\'')) {
            quote = text.charAt(cursor);
            cursor++;
        }
        int labelStart = cursor;
        while (cursor < text.length() && isLabelChar(text.charAt(cursor))) {
            cursor++;
        }
        if (cursor == labelStart) {
            return -1;
        }
        String label = text.substring(labelStart, cursor);
        if (quote != 0) {
            if (cursor >= text.length() || text.charAt(cursor) != quote) {
                return -1;
            }
            cursor++;
        }
        int newline = text.indexOf('\n', cursor);
        if (newline < 0) {
            return -1;
        }
        int line = newline + 1;
        while (line < text.length()) {
            int lineEnd = text.indexOf('\n', line);
            int stop = lineEnd < 0 ? text.length() : lineEnd;
            if (closesHeredoc(text.substring(line, stop).strip(), label)) {
                return stop;
            }
            if (lineEnd < 0) {
                return -1;
            }
            line = lineEnd + 1;
        }
        return -1;
    }

    private static boolean closesHeredoc(String line, String label) {
        if (!line.startsWith(label)) {
            return false;
        }
        return line.length() == label.length() || !isLabelChar(line.charAt(label.length()));
    }

    private static boolean isLabelChar(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }
}
