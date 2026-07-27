package dtm.ide.navigation;

import dtm.stools.component.panels.editor.code.api.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PhpDefinitionIndexTest {
    @TempDir
    Path temp;

    @Test
    void resolvesTypesFunctionsAndConstantsWithoutLsp() throws Exception {
        Files.writeString(temp.resolve("RouterAssembler.php"), """
                <?php
                class RouterAssembler {
                    public const USER_UPDATE_PASSWORD = '/password';
                    public function assembly(string $route) {}
                }
                """);
        PhpDefinitionIndex index = new PhpDefinitionIndex();
        index.rebuild(temp).join();

        assertDefinition(index, "<?php new RouterAssembler();", "RouterAssembler.php");
        assertDefinition(index, "<?php $router->assembly('x');", "RouterAssembler.php");
        assertDefinition(index, "<?php RouterAssembler::USER_UPDATE_PASSWORD;", "RouterAssembler.php");
    }

    @Test
    void extractsWordAtEitherSideOfCaret() {
        assertEquals("BaseHttpService", PhpDefinitionIndex.wordAt("extends BaseHttpService", 15));
        assertEquals("BaseHttpService", PhpDefinitionIndex.wordAt("extends BaseHttpService", 23));
    }

    @Test
    void leavesAmbiguousMethodsForTheLanguageServer() throws Exception {
        Files.writeString(temp.resolve("First.php"), "<?php class First { function execute() {} }");
        Files.writeString(temp.resolve("Second.php"), "<?php class Second { function execute() {} }");
        PhpDefinitionIndex index = new PhpDefinitionIndex();
        index.rebuild(temp).join();
        String call = "<?php $service->execute();";

        assertTrue(index.preferredDefinitions(call, call.indexOf("execute") + 2).isEmpty());
        assertEquals(2, index.definitions(call, call.indexOf("execute") + 2).size());
    }

    @Test
    void resolvesInterfaceAndInterfaceMethodImplementations() throws Exception {
        Path contracts = Files.createDirectories(temp.resolve("Contracts"));
        Path implementations = Files.createDirectories(temp.resolve("Implementations"));
        String contract = """
                <?php
                namespace App\\Contracts;
                interface ReportService {
                    public function generate(int $id);
                }
                """;
        Files.writeString(contracts.resolve("ReportService.php"), contract);
        Files.writeString(implementations.resolve("PdfReportService.php"), """
                <?php
                namespace App\\Implementations;
                use App\\Contracts\\ReportService;
                final class PdfReportService implements ReportService {
                    public function generate(int $id) {}
                }
                """);
        Files.writeString(implementations.resolve("HtmlReportService.php"), """
                <?php
                namespace App\\Implementations;
                final class HtmlReportService implements \\App\\Contracts\\ReportService {
                    public function generate(int $id) {}
                }
                """);
        String caller = """
                <?php
                namespace App;
                use App\\Contracts\\ReportService;
                function render(ReportService $service) {
                    $service->generate(1);
                }
                """;
        Files.writeString(temp.resolve("Caller.php"), caller);
        Files.writeString(temp.resolve("ImportOnly.php"), """
                <?php
                namespace App;
                use App\\Contracts\\ReportService;
                """);

        PhpDefinitionIndex index = new PhpDefinitionIndex();
        index.rebuild(temp).join();
        List<Location> types = index.implementations(
                contract, contract.indexOf("ReportService") + 2,
                contracts.resolve("ReportService.php"));
        List<Location> methods = index.implementations(
                contract, contract.indexOf("generate") + 2,
                contracts.resolve("ReportService.php"));
        String call = "<?php $service->generate(1);";
        List<Location> callImplementations = index.implementations(
                call, call.indexOf("generate") + 2, temp.resolve("Caller.php"));

        assertEquals(2, types.size());
        assertEquals(2, methods.size());
        assertEquals(2, callImplementations.size());
        assertEquals(1, index.usages(contract, contract.indexOf("generate") + 2).stream()
                .filter(location -> location.uri().endsWith("Caller.php"))
                .count());
        List<Location> typeUsages =
                index.usages(contract, contract.indexOf("ReportService") + 2);
        assertEquals(1, typeUsages.size());
        assertTrue(typeUsages.getFirst().uri().endsWith("Caller.php"));
        assertTrue(methods.stream().allMatch(location ->
                location.range().start().line() == 3
                        || location.range().start().line() == 4));
        assertEquals(2, index.implementationAnchors(
                contract, contracts.resolve("ReportService.php")).size());
        assertEquals(2, index.usageAnchors(
                contract, contracts.resolve("ReportService.php")).size());
    }

    @Test
    void resolvesARealHtdocsServiceWhenAvailable() throws Exception {
        Path root = Path.of("C:\\xampp\\htdocs");
        Path service = root.resolve(
                "src\\CautcarVeiculos\\Services\\Laudo\\LaudoServiceImple.php");
        if (!Files.isRegularFile(service)) return;
        String source = Files.readString(service);
        int offset = source.lastIndexOf("BaseHttpService");
        assertTrue(offset >= 0);

        PhpDefinitionIndex index = new PhpDefinitionIndex();
        index.rebuild(root).join();
        List<Location> definitions = index.definitions(source, offset + 2);
        List<Location> preferred = index.preferredDefinitions(source, offset + 2);

        assertFalse(definitions.isEmpty());
        assertTrue(definitions.getFirst().uri().replace('\\', '/')
                .endsWith("CautcarVeiculos/Services/Base/BaseHttpService.php"));
        assertEquals(definitions.getFirst(), preferred.getFirst());
    }

    @Test
    void resolvesRealHtdocsInterfaceImplementationsWhenAvailable() throws Exception {
        Path root = Path.of("C:\\xampp\\htdocs");
        Path contractFile = root.resolve(
                "src\\CautcarVeiculos\\Services\\Laudo\\LaudoService.php");
        if (!Files.isRegularFile(contractFile)) return;
        String contract = Files.readString(contractFile);
        int typeOffset = contract.indexOf("LaudoService");
        int methodOffset = contract.indexOf("findElementosAvaliados");
        assertTrue(typeOffset >= 0);
        assertTrue(methodOffset >= 0);

        PhpDefinitionIndex index = new PhpDefinitionIndex();
        index.rebuild(root).join();

        assertTrue(index.implementations(contract, typeOffset + 2, contractFile).stream()
                .anyMatch(location -> location.uri().endsWith("LaudoServiceImple.php")));
        assertTrue(index.implementations(contract, methodOffset + 2, contractFile).stream()
                .anyMatch(location -> location.uri().endsWith("LaudoServiceImple.php")));
    }

    private static void assertDefinition(PhpDefinitionIndex index, String source, String filename) {
        int offset = source.indexOf(source.contains("assembly") ? "assembly"
                : source.contains("USER_UPDATE") ? "USER_UPDATE_PASSWORD" : "RouterAssembler") + 2;
        List<Location> definitions = index.definitions(source, offset);
        assertFalse(definitions.isEmpty());
        assertTrue(definitions.getFirst().uri().endsWith(filename));
    }
}
