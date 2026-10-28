package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * File-path completion for src/href/action values in the HTML regions of .php files.
 * A leading "/" is resolved against the project root (the web document root, e.g. htdocs).
 */
public final class HtmlPathCompletionProvider {
    private static final Pattern PATH_ATTRIBUTE =
            Pattern.compile("(?is)(?:src|href|action)\\s*=\\s*(['\"])([^'\"]*)$");
    private static final int LIMIT = 100;

    public List<AutoCompleteItem> suggestions(String text, int caretOffset,
                                              Path currentFile, Path projectRoot) {
        String typed = typedPath(text, caretOffset);
        if (typed == null || currentFile == null) return List.of();
        String normalized = typed.replace('\\', '/');
        if (normalized.contains("<?") || normalized.startsWith("//")
                || normalized.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
            return List.of(); // PHP-built, protocol-relative and http:/data: URLs are not local files.
        }
        Path fileFolder = currentFile.toAbsolutePath().normalize().getParent();
        if (fileFolder == null) return List.of();
        Path root = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();

        int slash = normalized.lastIndexOf('/');
        String folderPart = slash < 0 ? "" : normalized.substring(0, slash + 1);
        String namePart = slash < 0 ? normalized : normalized.substring(slash + 1);

        boolean fromRoot = folderPart.startsWith("/");
        Path base = fromRoot ? root : fileFolder;
        Path boundary = root == null ? fileFolder : root;
        if (base == null) return List.of();
        String relative = folderPart.replaceFirst("^/+", "");
        while (relative.startsWith("./")) relative = relative.substring(2);

        Path folder;
        try {
            folder = base.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        } catch (InvalidPathException e) {
            return List.of();
        }
        if (!folder.startsWith(boundary) || !Files.isDirectory(folder)) return List.of();

        try (Stream<Path> entries = Files.list(folder)) {
            return entries
                    .filter(path -> path.getFileName() != null)
                    .filter(path -> path.getFileName().toString().startsWith(namePart))
                    .sorted(Comparator.comparing((Path path) -> !Files.isDirectory(path))
                            .thenComparing(path -> path.getFileName().toString()))
                    .limit(LIMIT)
                    .map(path -> {
                        boolean directory = Files.isDirectory(path);
                        // The editor keeps the text before the file-name prefix, so insert only the name.
                        String name = path.getFileName() + (directory ? "/" : "");
                        return new AutoCompleteItem(name, folderPart + name, directory ? "folder" : "file");
                    })
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    static String typedPath(String text, int caretOffset) {
        if (text == null || !PhpCaretContext.insideHtmlAttributeValue(text, caretOffset)) return null;
        int end = Math.min(caretOffset, text.length());
        int tagStart = text.lastIndexOf('<', end - 1);
        Matcher matcher = PATH_ATTRIBUTE.matcher(text.substring(tagStart + 1, end));
        return matcher.find() ? matcher.group(2) : null;
    }
}
