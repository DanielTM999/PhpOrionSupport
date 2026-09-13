package dtm.ide.wizard;

import dtm.ide.api.extension.wizard.ProjectWizard;
import dtm.ide.api.extension.wizard.ProjectWizardCallback;

import javax.swing.Icon;
import javax.swing.JPanel;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Supplier;

public final class PhpProjectWizard implements ProjectWizard {

    private final PhpTemplate template;
    private final Supplier<Path> resourcesRoot;

    public PhpProjectWizard(PhpTemplate template, Supplier<Path> resourcesRoot) {
        this.template = template;
        this.resourcesRoot = resourcesRoot;
    }

    @Override
    public String getId() {
        return "php-" + template.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    @Override
    public String getName() {
        return template.displayName();
    }

    @Override
    public String getLanguage() {
        return "PHP";
    }

    @Override
    public Icon getIcon() {
        return WizardIcons.project(template, 24);
    }

    @Override
    public JPanel getView(ProjectWizardCallback callback) {
        return new PhpProjectWizardView(template, resourcesRoot, callback);
    }
}
