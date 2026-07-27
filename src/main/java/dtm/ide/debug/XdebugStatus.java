package dtm.ide.debug;

import java.nio.file.Path;

public record XdebugStatus(
        Path php,
        String phpVersion,
        Path loadedIni,
        Path extensionDirectory,
        boolean threadSafe,
        String architecture,
        String compiler,
        boolean loaded,
        String xdebugVersion
) {
    public boolean installable() {
        return php != null && loadedIni != null && extensionDirectory != null;
    }

    public String summary() {
        if (loaded) {
            return "Xdebug " + (xdebugVersion.isBlank() ? "" : xdebugVersion + " ")
                    + "está carregado em " + php + ".";
        }
        if (!installable()) {
            return "O PHP foi encontrado, mas php.ini ou extension_dir não puderam ser identificados.";
        }
        return "Xdebug não está carregado no PHP " + phpVersion + " (" + architecture
                + (threadSafe ? ", TS" : ", NTS") + ").";
    }
}
