package dtm.ide.wizard;

import java.util.List;

enum PhpTemplate {

    ORIGINS_APPLICATION("Aplicacao Origins",
            "Aplicacao web sobre o framework Origins, com modules.config, rotas e controller de exemplo.",
            true, "project", null),
    COMPOSER_APPLICATION("Aplicacao Composer",
            "Aplicacao PHP com autoload PSR-4, ponto de entrada em public/index.php e PHPUnit.",
            true, "project", null),
    COMPOSER_LIBRARY("Biblioteca Composer",
            "Pacote reutilizavel com autoload PSR-4 e suite de testes PHPUnit.",
            true, "library", null),
    PLAIN_PHP("PHP simples",
            "Projeto sem Composer, apenas index.php e a pasta src.",
            false, null, null),
    LARAVEL("Laravel",
            "Projeto Laravel completo gerado por composer create-project.",
            true, "project", "laravel/laravel"),
    SYMFONY("Symfony",
            "Esqueleto Symfony gerado por composer create-project.",
            true, "project", "symfony/skeleton"),
    SLIM("Slim",
            "Esqueleto Slim Framework gerado por composer create-project.",
            true, "project", "slim/slim-skeleton");

    private final String displayName;
    private final String description;
    private final boolean composer;
    private final String composerType;
    private final String createProjectPackage;

    PhpTemplate(String displayName, String description, boolean composer,
                String composerType, String createProjectPackage) {
        this.displayName = displayName;
        this.description = description;
        this.composer = composer;
        this.composerType = composerType;
        this.createProjectPackage = createProjectPackage;
    }

    static List<PhpTemplate> available() {
        return List.of(values());
    }

    String displayName() {
        return displayName;
    }

    String description() {
        return description;
    }

    boolean usesComposer() {
        return composer;
    }

    String defaultComposerType() {
        return composerType == null ? "project" : composerType;
    }

    boolean needsCreateProject() {
        return createProjectPackage != null;
    }

    String createProjectPackage() {
        return createProjectPackage;
    }

    boolean isOrigins() {
        return this == ORIGINS_APPLICATION;
    }

    boolean isLibrary() {
        return this == COMPOSER_LIBRARY;
    }

    boolean hasEntryPoint() {
        return this != COMPOSER_LIBRARY;
    }

    boolean allowsDependencySelection() {
        return composer && !needsCreateProject();
    }

    String badge() {
        return switch (this) {
            case ORIGINS_APPLICATION -> "Origins";
            case PLAIN_PHP -> "PHP";
            case LARAVEL -> "Laravel";
            case SYMFONY -> "Symfony";
            case SLIM -> "Slim";
            default -> "Composer";
        };
    }
}
