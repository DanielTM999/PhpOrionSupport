package dtm.ide.debug;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class XdebugInstallerServiceTest {

    @Test
    void inspectsXamppPhpWhenAvailable() throws Exception {
        Path php = Path.of("C:\\xampp\\php\\php.exe");
        if (!Files.isRegularFile(php)) return;

        XdebugStatus status = new XdebugInstallerService().inspectNow(php);

        assertEquals(php.toAbsolutePath().normalize(), status.php());
        assertTrue(status.phpVersion().startsWith("8."));
        assertEquals(Path.of("C:\\xampp\\php\\php.ini"), status.loadedIni());
        assertEquals(Path.of("C:\\xampp\\php\\ext"), status.extensionDirectory());
        assertTrue(status.threadSafe());
        assertTrue(status.installable());
    }

    @Test
    void debugAdapterUsesPinnedMarketplacePackage() {
        assertEquals("1.40.1", PhpDebugAdapterInstallerService.VERSION);
        assertTrue(PhpDebugAdapterInstallerService.PACKAGE.toString().endsWith(
                "/xdebug/vsextensions/php-debug/1.40.1/vspackage"));
    }
}
