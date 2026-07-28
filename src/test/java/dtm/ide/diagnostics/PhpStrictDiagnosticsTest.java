package dtm.ide.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpStrictDiagnosticsTest {
    private final PhpStrictDiagnostics diagnostics = new PhpStrictDiagnostics();

    @Test
    void reportsRepeatedConstantValueOnSecondDeclaration() {
        String source = """
                <?php
                final class RouterAssembler
                {
                    public const SYSTEM_REGISTRO_LISTAR_LOJISTA = "SystemRegistroListarLojista";
                    public const SYSTEM_REGISTRO_LISTAR_LOGISTA = "SystemRegistroListarLojista";
                }
                """;

        List<PhpStrictDiagnostics.Issue> issues = diagnostics.analyze(source);

        assertEquals(1, issues.size());
        PhpStrictDiagnostics.Issue issue = issues.getFirst();
        assertEquals(PhpStrictDiagnostics.Kind.DUPLICATE_VALUE, issue.kind());
        assertEquals("SYSTEM_REGISTRO_LISTAR_LOGISTA", issue.constantName());
        assertEquals("SYSTEM_REGISTRO_LISTAR_LOJISTA", issue.previousName());
        assertEquals(4, issue.range().start().line());
    }

    @Test
    void reportsDuplicateNamesAndHandlesMultiDeclarationConstants() {
        String source = """
                <?php
                class Example {
                    const FIRST = ['x', 'y'], SECOND = 2;
                    const FIRST = 3;
                }
                """;

        List<PhpStrictDiagnostics.Issue> issues = diagnostics.analyze(source);

        assertEquals(1, issues.size());
        assertEquals(PhpStrictDiagnostics.Kind.DUPLICATE_NAME, issues.getFirst().kind());
        assertEquals("FIRST", issues.getFirst().constantName());
    }

    @Test
    void keepsValuesSeparatedByTypeAndIgnoresCommentsAndMethodBodies() {
        String source = """
                <?php
                class First {
                    const ROUTE = "same";
                    // const COMMENT = "same";
                    public function value() {
                        const LOCAL_VALUE = "same";
                    }
                }
                class Second {
                    const ROUTE = "same";
                }
                """;

        assertTrue(diagnostics.analyze(source).isEmpty());
    }

    @Test
    void doesNotTreatClassConstantFetchAsATypeDeclaration() {
        String source = """
                <?php
                $name = Example::class;
                class Example {
                    const ONE = 1;
                    const TWO = 2;
                }
                """;

        assertTrue(diagnostics.analyze(source).isEmpty());
    }
}
