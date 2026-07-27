package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Immediate HTML completion for the non-PHP regions of hybrid .php files. */
public final class HtmlCompletionProvider {
    private static final List<String> TAGS = List.of(
            "a", "abbr", "article", "aside", "audio", "body", "br", "button", "canvas",
            "code", "datalist", "details", "dialog", "div", "em", "fieldset", "figure",
            "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "head", "header",
            "html", "i", "iframe", "img", "input", "label", "li", "link", "main",
            "meta", "nav", "ol", "option", "p", "picture", "pre", "script", "section",
            "select", "small", "source", "span", "strong", "style", "table", "tbody",
            "td", "textarea", "tfoot", "th", "thead", "title", "tr", "ul", "video"
    );
    private static final List<String> ATTRIBUTES = List.of(
            "id", "class", "style", "title", "hidden", "lang", "role", "tabindex",
            "aria-label", "aria-controls", "aria-selected", "aria-expanded",
            "data-bs-toggle", "data-bs-target", "data-bs-dismiss",
            "href", "target", "rel", "download", "src", "srcset", "alt", "width", "height",
            "loading", "type", "name", "value", "placeholder", "required", "disabled",
            "readonly", "checked", "selected", "multiple", "autocomplete", "for", "action",
            "method", "enctype", "charset", "content", "http-equiv", "defer", "async",
            "onclick", "onchange", "oninput", "onsubmit"
    );
    private static final Set<String> VOID_TAGS = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "source", "track", "wbr"
    );

    public List<AutoCompleteItem> suggestions(String text, int caretOffset, boolean explicit) {
        if (text == null) return List.of();
        int caret = Math.clamp(caretOffset, 0, text.length());
        int open = text.lastIndexOf('<', Math.max(0, caret - 1));
        int close = text.lastIndexOf('>', Math.max(0, caret - 1));
        if (open > close) return insideTag(text, open, caret);

        String prefix = plainTagPrefix(text, caret);
        if (!prefix.isEmpty()) return tagItems(prefix, true);
        if (!explicit) return closeSuggestion(text, caret);

        List<AutoCompleteItem> result = new ArrayList<>();
        result.addAll(closeSuggestion(text, caret));
        result.addAll(tagItems("", true));
        return result;
    }

    private static List<AutoCompleteItem> insideTag(String text, int open, int caret) {
        String fragment = text.substring(open + 1, caret);
        if (fragment.startsWith("/")) {
            String prefix = fragment.substring(1).toLowerCase(Locale.ROOT);
            List<AutoCompleteItem> result = new ArrayList<>();
            for (String tag : openTags(text, caret)) {
                if (tag.startsWith(prefix)) {
                    result.add(new AutoCompleteItem(tag + ">", tag, "Fechar elemento HTML"));
                }
            }
            return result;
        }
        if (fragment.isBlank() || isTagName(fragment)) {
            return tagItems(fragment.toLowerCase(Locale.ROOT), false);
        }
        int whitespace = Math.max(fragment.lastIndexOf(' '), fragment.lastIndexOf('\n'));
        String prefix = whitespace < 0 ? "" : fragment.substring(whitespace + 1)
                .toLowerCase(Locale.ROOT);
        if (prefix.contains("=") || prefix.startsWith("\"") || prefix.startsWith("'")) {
            return List.of();
        }
        List<AutoCompleteItem> result = new ArrayList<>();
        for (String attribute : ATTRIBUTES) {
            if (attribute.startsWith(prefix)) {
                result.add(new AutoCompleteItem(attribute + "=\"\"", attribute, "Atributo HTML"));
            }
        }
        return result;
    }

    private static List<AutoCompleteItem> tagItems(String prefix, boolean includeBracket) {
        List<AutoCompleteItem> result = new ArrayList<>();
        for (String tag : TAGS) {
            if (tag.startsWith(prefix)) result.add(tagItem(tag, includeBracket));
        }
        return result;
    }

    private static AutoCompleteItem tagItem(String tag, boolean includeBracket) {
        String opening = includeBracket ? "<" : "";
        String insert = switch (tag) {
            case "a" -> opening + "a href=\"\"></a>";
            case "img" -> opening + "img src=\"\" alt=\"\">";
            case "link" -> opening + "link rel=\"stylesheet\" href=\"\">";
            case "script" -> opening + "script src=\"\"></script>";
            case "form" -> opening + "form action=\"\" method=\"post\"></form>";
            case "input" -> opening + "input type=\"text\" name=\"\">";
            case "button" -> opening + "button type=\"button\"></button>";
            case "meta" -> opening + "meta name=\"\" content=\"\">";
            default -> VOID_TAGS.contains(tag) ? opening + tag + ">"
                    : opening + tag + "></" + tag + ">";
        };
        return new AutoCompleteItem(insert, tag, "Elemento HTML");
    }

    private static List<AutoCompleteItem> closeSuggestion(String text, int caret) {
        Deque<String> open = openTags(text, caret);
        if (open.isEmpty()) return List.of();
        String tag = open.peek();
        return List.of(new AutoCompleteItem("</" + tag + ">", "</" + tag + ">",
                "Fechar elemento HTML"));
    }

    private static Deque<String> openTags(String text, int end) {
        Deque<String> stack = new ArrayDeque<>();
        for (int position = 0; position < end;) {
            int open = text.indexOf('<', position);
            if (open < 0 || open >= end) break;
            if (text.startsWith("<?", open)) {
                int phpEnd = text.indexOf("?>", open + 2);
                position = phpEnd < 0 ? end : phpEnd + 2;
                continue;
            }
            int close = text.indexOf('>', open + 1);
            if (close < 0 || close >= end) break;
            String content = text.substring(open + 1, close).strip();
            position = close + 1;
            if (content.isEmpty() || content.startsWith("!")) continue;
            boolean closing = content.startsWith("/");
            String name = tagName(closing ? content.substring(1) : content);
            if (name.isEmpty()) continue;
            if (closing) {
                while (!stack.isEmpty() && !stack.pop().equals(name)) {

                }
            } else if (!content.endsWith("/") && !VOID_TAGS.contains(name)) {
                stack.push(name);
            }
        }
        return stack;
    }

    public static boolean isPhpAt(String text, int caretOffset) {
        if (text == null) return false;
        int caret = Math.clamp(caretOffset, 0, text.length());
        boolean php = false;
        for (int i = 0; i < caret;) {
            int nextOpen = text.indexOf("<?", i);
            int nextClose = text.indexOf("?>", i);
            if (!php) {
                if (nextOpen < 0 || nextOpen >= caret) break;
                php = true;
                i = nextOpen + 2;
            } else {
                if (nextClose < 0 || nextClose >= caret) break;
                php = false;
                i = nextClose + 2;
            }
        }
        return php;
    }

    private static String tagName(String value) {
        int end = 0;
        while (end < value.length() && (Character.isLetterOrDigit(value.charAt(end))
                || value.charAt(end) == '-')) end++;
        return value.substring(0, end).toLowerCase(Locale.ROOT);
    }

    private static boolean isTagName(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '-') return false;
        }
        return true;
    }

    private static String plainTagPrefix(String text, int caret) {
        int start = Math.max(text.lastIndexOf('\n', Math.max(0, caret - 1)) + 1,
                text.lastIndexOf('\r', Math.max(0, caret - 1)) + 1);
        String value = text.substring(start, caret).strip();
        return isTagName(value) ? value.toLowerCase(Locale.ROOT) : "";
    }
}
