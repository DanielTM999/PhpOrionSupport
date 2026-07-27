package dtm.ide.settings;

import dtm.ide.api.extension.settings.PluginSettingsPage;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

public final class PhpSettingsPage implements PluginSettingsPage {
    private final PhpPluginSettings settings;
    private final JTextField php = new JTextField(36);
    private final JTextField composer = new JTextField(36);
    private final JTextField xampp = new JTextField(36);
    private final JSpinner debugPort = new JSpinner(new SpinnerNumberModel(9003, 1, 65535, 1));
    private final JSpinner serverPort = new JSpinner(new SpinnerNumberModel(8080, 1, 65535, 1));
    private final JCheckBox openBrowser = new JCheckBox("Abrir navegador ao executar aplicações web");
    private JComponent view;

    public PhpSettingsPage(PhpPluginSettings settings) {
        this.settings = settings;
    }

    @Override
    public String getTitle() {
        return "PHP";
    }

    @Override
    public JComponent getView() {
        if (view == null) view = build();
        load();
        return view;
    }

    private JComponent build() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.insets = new Insets(0, 0, 8, 10);
        gbc.anchor = GridBagConstraints.WEST;
        add(panel, gbc, "Executável PHP:", php);
        add(panel, gbc, "Executável Composer:", composer);
        add(panel, gbc, "Diretório XAMPP:", xampp);
        add(panel, gbc, "Porta Xdebug:", debugPort);
        add(panel, gbc, "Porta do servidor PHP:", serverPort);
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.weighty = 1;
        panel.add(openBrowser, gbc);
        return panel;
    }

    private static void add(JPanel panel, GridBagConstraints gbc, String label, JComponent field) {
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, gbc);
        gbc.gridy++;
    }

    private void load() {
        php.setText(settings.phpExecutable());
        composer.setText(settings.composerExecutable());
        xampp.setText(settings.xamppRoot());
        debugPort.setValue(settings.xdebugPort());
        serverPort.setValue(settings.serverPort());
        openBrowser.setSelected(settings.openBrowser());
    }

    @Override
    public void onApply() {
        settings.phpExecutable(php.getText());
        settings.composerExecutable(composer.getText());
        settings.xamppRoot(xampp.getText());
        settings.xdebugPort((Integer) debugPort.getValue());
        settings.serverPort((Integer) serverPort.getValue());
        settings.openBrowser(openBrowser.isSelected());
        applySystemProperties();
    }

    @Override
    public void onRestoreDefaults() {
        settings.restoreDefaults();
        load();
        applySystemProperties();
    }

    private void applySystemProperties() {
        set("orion.php.executable", settings.phpExecutable());
        set("orion.composer.executable", settings.composerExecutable());
        set("orion.xampp.root", settings.xamppRoot());
        System.setProperty("orion.php.xdebugPort", Integer.toString(settings.xdebugPort()));
        System.setProperty("orion.php.serverPort", Integer.toString(settings.serverPort()));
    }

    private static void set(String key, String value) {
        if (value == null || value.isBlank()) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
