package dtm.ide.debug;

import java.nio.file.Path;

public record XdebugInstallResult(boolean installed, String message, Path backupIni, XdebugStatus status) {
}
