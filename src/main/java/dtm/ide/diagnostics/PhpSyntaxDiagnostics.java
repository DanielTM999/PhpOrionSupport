package dtm.ide.diagnostics;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict PHP syntax diagnostics produced by the Zend linter ({@code php -l}).
 *
 * <p>Intelephense parses with an error-recovering grammar and silently swallows
 * some syntax errors (for example {@code Foo:bar()} instead of {@code Foo::bar()},
 * or a missing {@code ;}). The recovered AST breaks type resolution, so completion
 * desyncs on the surrounding code without any reported error. Running the strict
 * linter over the current buffer surfaces those errors in the diagnostics panel.
 *
 * <p>The buffer is fed through the process' standard input, so unsaved edits are
 * checked and no temporary file is written.
 */
public final class PhpSyntaxDiagnostics {

    /** 0-based line, 0-based column range and the raw parser message. */
    public record Issue(int line, int startCol, int endCol, String message) {
    }

    private static final Pattern ERROR_LINE = Pattern.compile(
            "^\\s*(?:PHP )?(?:Parse|Fatal) error:\\s*(.*?) in .* on line (\\d+)\\s*$");

    private final long timeoutMillis;

    public PhpSyntaxDiagnostics() {
        this(4000L);
    }

    public PhpSyntaxDiagnostics(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Lints {@code source} with the given PHP executable and returns the parse
     * errors found. Returns an empty list when the executable is missing, the
     * source is blank, the linter times out or nothing is wrong.
     */
    public List<Issue> analyze(Path phpExecutable, String source) {
        if (phpExecutable == null || source == null || source.isBlank()) return List.of();
        String output = lint(phpExecutable, source);
        if (output == null || output.isBlank()) return List.of();

        int[] lineStarts = lineStarts(source);
        List<Issue> issues = new ArrayList<>();
        for (String line : output.split("\\R")) {
            Matcher matcher = ERROR_LINE.matcher(line);
            if (!matcher.matches()) continue;
            int reported = parseInt(matcher.group(2));
            if (reported <= 0) continue;
            int row = Math.min(reported - 1, Math.max(0, lineStarts.length - 1));
            int[] cols = columns(source, lineStarts, row);
            Issue issue = new Issue(row, cols[0], cols[1], matcher.group(1).trim());
            if (!issues.contains(issue)) issues.add(issue);
        }
        return List.copyOf(issues);
    }

    private String lint(Path phpExecutable, String source) {
        Process process = null;
        try {
            process = new ProcessBuilder(
                    phpExecutable.toString(), "-d", "display_errors=stderr", "-l")
                    .redirectErrorStream(true)
                    .start();

            final Process running = process;
            Thread writer = new Thread(() -> {
                try (OutputStream stdin = running.getOutputStream()) {
                    stdin.write(source.getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // The linter closes stdin as soon as it hits a fatal error;
                    // a broken pipe here is expected and not an error.
                }
            }, "php-lint-stdin");
            writer.setDaemon(true);
            writer.start();

            byte[] out = process.getInputStream().readAllBytes();
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            return null;
        }
    }

    private static int[] columns(String source, int[] lineStarts, int row) {
        int start = lineStarts[row];
        int end = row + 1 < lineStarts.length ? lineStarts[row + 1] : source.length();
        while (end > start && (source.charAt(end - 1) == '\n' || source.charAt(end - 1) == '\r')) {
            end--;
        }
        int firstNonWs = start;
        while (firstNonWs < end && Character.isWhitespace(source.charAt(firstNonWs))) firstNonWs++;
        int startCol = firstNonWs - start;
        int endCol = end - start;
        if (endCol <= startCol) return new int[]{0, Math.max(1, end - start)};
        return new int[]{startCol, endCol};
    }

    private static int[] lineStarts(String source) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') starts.add(i + 1);
        }
        int[] result = new int[starts.size()];
        for (int i = 0; i < result.length; i++) result[i] = starts.get(i);
        return result;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
