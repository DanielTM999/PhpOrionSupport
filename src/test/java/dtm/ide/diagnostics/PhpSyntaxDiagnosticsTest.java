package dtm.ide.diagnostics;

import dtm.ide.lsp.PhpToolchainService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PhpSyntaxDiagnosticsTest {
    private final PhpSyntaxDiagnostics diagnostics = new PhpSyntaxDiagnostics();

    private static Path php() {
        try {
            return new PhpToolchainService().findPhp(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Test
    void reportsSyntaxErrorIntelephenseSilentlyRecovers() {
        Path php = php();
        assumeTrue(php != null, "PHP executable not available");

        // `Foo:bar()` (single colon) is recovered silently by Intelephense but
        // rejected by the strict linter.
        String source = """
                <?php
                $value = Foo:bar();
                """;

        List<PhpSyntaxDiagnostics.Issue> issues = diagnostics.analyze(php, source);

        assertEquals(1, issues.size());
        PhpSyntaxDiagnostics.Issue issue = issues.getFirst();
        assertEquals(1, issue.line());
        assertTrue(issue.message().toLowerCase().contains("syntax error"),
                "unexpected message: " + issue.message());
        assertTrue(issue.endCol() > issue.startCol());
    }

    @Test
    void reportsNothingForValidSource() {
        Path php = php();
        assumeTrue(php != null, "PHP executable not available");

        String source = """
                <?php
                final class Ok {
                    public function run(): int { return 1; }
                }
                """;

        assertTrue(diagnostics.analyze(php, source).isEmpty());
    }

    @Test
    void returnsEmptyWhenExecutableIsNull() {
        assertTrue(diagnostics.analyze(null, "<?php $x = 1;").isEmpty());
    }
}
