package dtm.ide.wizard;

import dtm.stools.utils.ImageUtils;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.Color;

final class WizardIcons {

    private WizardIcons() {
    }

    static Icon project(PhpTemplate template, int size) {
        String resource = switch (template) {
            case ORIGINS_APPLICATION -> "/imgs/wizard-origins.svg";
            case COMPOSER_APPLICATION, COMPOSER_LIBRARY -> "/imgs/wizard-composer.svg";
            case LARAVEL -> "/imgs/wizard-laravel.svg";
            case SYMFONY -> "/imgs/wizard-symfony.svg";
            case SLIM -> "/imgs/wizard-slim.svg";
            case PLAIN_PHP -> "/imgs/wizard-php.svg";
        };
        return ImageUtils.getIconByResource(WizardIcons.class, resource)
                .map(icon -> ImageUtils.resizeIcon(icon, size, size)).orElse(null);
    }

    static Icon folder(int size) {
        return tinted("/imgs/wizard-folder.svg", size);
    }

    static Icon search(int size) {
        return tinted("/imgs/wizard-search.svg", size);
    }

    static Icon create(int size) {
        return tinted("/imgs/wizard-create.svg", size);
    }

    static Icon cancel(int size) {
        return tinted("/imgs/wizard-cancel.svg", size);
    }

    static Icon dependency(int size) {
        return tinted("/imgs/wizard-dependency.svg", size);
    }

    static Icon loading(int size) {
        return tinted("/imgs/wizard-loading.svg", size);
    }

    static Icon error(int size) {
        return colored("/imgs/wizard-error.svg", size, new Color(0xE05252));
    }

    private static Icon tinted(String resource, int size) {
        Color foreground = UIManager.getColor("Label.foreground");
        return colored(resource, size, foreground == null ? Color.LIGHT_GRAY : foreground);
    }

    private static Icon colored(String resource, int size, Color color) {
        return ImageUtils.getColoredIconByResource(WizardIcons.class, resource, color)
                .map(icon -> ImageUtils.resizeIcon(icon, size, size)).orElse(null);
    }
}
