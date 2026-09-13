package dtm.ide.wizard;

import dtm.ide.api.extension.wizard.ProjectWizardCallback;
import dtm.stools.component.feedback.steps.StepsPanel;
import dtm.stools.component.form.FormField;
import dtm.stools.component.inputfields.duallistfield.DualListField;
import dtm.stools.component.inputfields.tagfield.TagInputField;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import org.junit.jupiter.api.Test;

import javax.swing.JList;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpProjectWizardViewTest {

    @Test
    void everyTemplateExposesAnIconAndAUniqueId() {
        List<String> ids = new ArrayList<>();
        for (PhpTemplate template : PhpTemplate.available()) {
            PhpProjectWizard wizard = new PhpProjectWizard(template, () -> null);
            assertNotNull(wizard.getIcon(), template.name());
            assertEquals("PHP", wizard.getLanguage());
            assertFalse(ids.contains(wizard.getId()), "id repetido: " + wizard.getId());
            ids.add(wizard.getId());
        }
        assertEquals(PhpTemplate.values().length, ids.size());
    }

    @Test
    void buildsTheWizardWithSwingToolsFields() {
        JPanel view = view(PhpTemplate.COMPOSER_APPLICATION);

        assertTrue(contains(view, MaskedTextField.class));
        assertTrue(view.getPreferredSize().width >= 600);
        assertTrue(view.getPreferredSize().height >= 480);
    }

    @Test
    void composerTemplatesAskForTheFullPackageCoordinates() {
        JPanel view = view(PhpTemplate.COMPOSER_APPLICATION);

        assertTrue(field(view, "vendor").isPresent());
        assertTrue(field(view, "packageName").isPresent());
        assertTrue(field(view, "namespace").isPresent());
        assertTrue(field(view, "version").isPresent());
        assertTrue(field(view, "license").isPresent());
    }

    @Test
    void theVersionIsOptionalWhileTheCoordinatesAreNot() {
        JPanel view = view(PhpTemplate.COMPOSER_APPLICATION);

        assertTrue(field(view, "vendor").orElseThrow().isRequired());
        assertTrue(field(view, "packageName").orElseThrow().isRequired());
        assertTrue(field(view, "namespace").orElseThrow().isRequired());
        assertFalse(field(view, "version").orElseThrow().isRequired());
    }

    @Test
    void plainPhpSkipsComposerAndDependencySteps() {
        JPanel view = view(PhpTemplate.PLAIN_PHP);

        assertTrue(field(view, "vendor").isEmpty());
        assertTrue(field(view, "packageName").isEmpty());
        assertTrue(field(view, "namespace").isEmpty());
        assertEquals(2, steps(view).getSteps().size(), "projeto e revisao");
    }

    @Test
    void composerTemplatesHaveFourSteps() {
        JPanel view = view(PhpTemplate.COMPOSER_APPLICATION);

        assertEquals(4, steps(view).getSteps().size(),
                "projeto, composer, dependencias e revisao");
        assertTrue(contains(view, DualListField.class));
        assertTrue(count(view, JList.class) >= 2, "uma lista de disponiveis e uma de selecionadas");
    }

    @Test
    void createProjectTemplatesSkipTheDependencyStep() {
        JPanel view = view(PhpTemplate.LARAVEL);

        assertEquals(3, steps(view).getSteps().size(), "projeto, composer e revisao");
        assertFalse(contains(view, DualListField.class),
                "composer create-project ja traz as dependencias do esqueleto");
    }

    @Test
    void theOriginsTemplateLetsTheUserNameTheModules() {
        JPanel view = view(PhpTemplate.ORIGINS_APPLICATION);

        FormField modules = field(view, "modules").orElseThrow();
        assertTrue(modules.getControl() instanceof TagInputField);
        assertEquals(List.of("App"), ((TagInputField) modules.getControl()).getTags());
    }

    @Test
    void theLocationFieldReadsTheTextAndNotItsWrapper() {
        JPanel view = view(PhpTemplate.COMPOSER_APPLICATION);

        Object value = field(view, "location").orElseThrow().getValue();

        assertNotNull(value);
        assertFalse(String.valueOf(value).isBlank(), "a pasta comeca com a home do usuario");
    }

    private static JPanel view(PhpTemplate template) {
        return new PhpProjectWizardView(template, () -> null, new ProjectWizardCallback() {
            @Override public void notifyProjectCreated(Path path) {
            }

            @Override public void cancel() {
            }
        });
    }

    private static Optional<FormField> field(Component root, String name) {
        return all(root).stream()
                .filter(FormField.class::isInstance)
                .map(FormField.class::cast)
                .filter(field -> field.getFieldName().equals(name))
                .findFirst();
    }

    private static StepsPanel steps(Component root) {
        return all(root).stream()
                .filter(StepsPanel.class::isInstance)
                .map(StepsPanel.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static boolean contains(Component component, Class<?> type) {
        return all(component).stream().anyMatch(type::isInstance);
    }

    private static int count(Component component, Class<?> type) {
        return (int) all(component).stream().filter(type::isInstance).count();
    }

    private static List<Component> all(Component root) {
        List<Component> result = new ArrayList<>();
        result.add(root);
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                result.addAll(all(child));
            }
        }
        return result;
    }
}
