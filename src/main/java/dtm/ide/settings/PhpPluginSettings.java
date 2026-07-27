package dtm.ide.settings;

import java.util.prefs.Preferences;

public final class PhpPluginSettings {
    private static final String PHP = "phpExecutable";
    private static final String COMPOSER = "composerExecutable";
    private static final String XAMPP = "xamppRoot";
    private static final String DEBUG_PORT = "xdebugPort";
    private static final String SERVER_PORT = "serverPort";
    private static final String OPEN_BROWSER = "openBrowser";

    private final Preferences preferences = Preferences.userNodeForPackage(PhpPluginSettings.class);

    public String phpExecutable() {
        return preferences.get(PHP, "");
    }

    public void phpExecutable(String value) {
        put(PHP, value);
    }

    public String composerExecutable() {
        return preferences.get(COMPOSER, "");
    }

    public void composerExecutable(String value) {
        put(COMPOSER, value);
    }

    public String xamppRoot() {
        return preferences.get(XAMPP, "C:\\xampp");
    }

    public void xamppRoot(String value) {
        put(XAMPP, value);
    }

    public int xdebugPort() {
        return preferences.getInt(DEBUG_PORT, 9003);
    }

    public void xdebugPort(int value) {
        preferences.putInt(DEBUG_PORT, Math.clamp(value, 1, 65535));
    }

    public int serverPort() {
        return preferences.getInt(SERVER_PORT, 8080);
    }

    public void serverPort(int value) {
        preferences.putInt(SERVER_PORT, Math.clamp(value, 1, 65535));
    }

    public boolean openBrowser() {
        return preferences.getBoolean(OPEN_BROWSER, true);
    }

    public void openBrowser(boolean value) {
        preferences.putBoolean(OPEN_BROWSER, value);
    }

    public void restoreDefaults() {
        phpExecutable("");
        composerExecutable("");
        xamppRoot("C:\\xampp");
        xdebugPort(9003);
        serverPort(8080);
        openBrowser(true);
    }

    private void put(String key, String value) {
        preferences.put(key, value == null ? "" : value.strip());
    }
}
