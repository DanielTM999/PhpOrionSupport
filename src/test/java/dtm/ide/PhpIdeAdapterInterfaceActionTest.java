package dtm.ide;

import dtm.ide.api.project.editor.IdeCodeActionContext;
import dtm.ide.api.project.editor.IdeDiagnosticsContext;
import dtm.ide.navigation.PhpDefinitionIndex;
import dtm.stools.component.panels.editor.code.api.CodeAction;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpIdeAdapterInterfaceActionTest {
    @TempDir
    Path temp;

    @Test
    void exposesErrorAndImplementInterfaceQuickFix() throws Exception {
        Files.writeString(temp.resolve("Worker.php"), """
                <?php
                interface Worker {
                    public function run(string $job): bool;
                }
                """);
        Path serviceFile = temp.resolve("Service.php");
        String source = """
                <?php
                final class Service implements Worker
                {
                }
                """;
        PhpDefinitionIndex index = new PhpDefinitionIndex();
        index.rebuild(temp).join();
        PhpIdeAdapter adapter = new PhpIdeAdapter();
        setField(adapter, "definitionIndex", index);

        Collection<Diagnostic> diagnostics = adapter.getDiagnostics(
                new IdeDiagnosticsContext(source, serviceFile), false, List.of());

        assertEquals(1, diagnostics.size());
        Diagnostic diagnostic = diagnostics.iterator().next();
        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity());
        assertTrue(diagnostic.message().contains("run()"));

        Range range = Range.of(
                diagnostic.startLine(), diagnostic.startCol(),
                diagnostic.endLine(), diagnostic.endCol());
        List<CodeAction> actions = adapter.getCodeActions(
                new IdeCodeActionContext(source, serviceFile, range, List.of(diagnostic)));

        assertEquals(1, actions.size());
        assertFalse(actions.getFirst().title().isBlank());
        assertEquals(1, actions.getFirst().edits().size());
        String updated = apply(source, actions.getFirst().edits().getFirst());
        assertTrue(updated.contains("public function run(string $job): bool"));
        assertTrue(updated.contains("throw new \\LogicException('Not implemented');"));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static String apply(String source, TextEdit edit) {
        int start = offset(source, edit.range().start());
        int end = offset(source, edit.range().end());
        return source.substring(0, start) + edit.newText() + source.substring(end);
    }

    private static int offset(String source, Position position) {
        int at = 0;
        for (int line = 0; line < position.line() && at < source.length(); line++) {
            int newline = source.indexOf('\n', at);
            at = newline < 0 ? source.length() : newline + 1;
        }
        return Math.min(source.length(), at + position.col());
    }
}
