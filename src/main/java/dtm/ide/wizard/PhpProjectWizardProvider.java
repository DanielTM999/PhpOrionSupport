package dtm.ide.wizard;

import dtm.di.annotations.Singleton;
import dtm.ide.api.annotations.PluginReference;
import dtm.ide.api.extension.wizard.ProjectWizard;
import dtm.ide.api.extension.wizard.ProjectWizardProvider;
import dtm.ide.api.plugin.PluginScope;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Singleton
@PluginReference(id = "php-project-wizard", singleton = true, scope = PluginScope.APPLICATION)
public class PhpProjectWizardProvider extends ProjectWizardProvider {

    @Override
    public Collection<ProjectWizard> getProjectWizards() {
        List<ProjectWizard> wizards = new ArrayList<>();
        for (PhpTemplate template : PhpTemplate.available()) {
            wizards.add(new PhpProjectWizard(template, this::resourcesRoot));
        }
        return wizards;
    }

    private Path resourcesRoot() {
        try {
            return getResource() == null ? null : getResource().getResourcePath();
        } catch (Exception e) {
            return null;
        }
    }
}
