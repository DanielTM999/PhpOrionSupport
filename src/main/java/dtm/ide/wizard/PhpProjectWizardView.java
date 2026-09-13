package dtm.ide.wizard;

import dtm.ide.api.extension.wizard.ProjectWizardCallback;
import dtm.ide.composer.ComposerInstallerService;
import dtm.ide.composer.ComposerService;
import dtm.ide.lsp.PhpToolchainService;
import dtm.stools.component.feedback.alert.AlertPanel;
import dtm.stools.component.feedback.badge.BadgeLabel;
import dtm.stools.component.feedback.progress.ProgressBar;
import dtm.stools.component.feedback.steps.StepsPanel;
import dtm.stools.component.form.FormField;
import dtm.stools.component.form.FormValues;
import dtm.stools.component.form.ValidationResult;
import dtm.stools.component.form.Validator;
import dtm.stools.component.inputfields.duallistfield.DualListField;
import dtm.stools.component.inputfields.segmentedfield.SegmentedField;
import dtm.stools.component.inputfields.selectfield.DropdownFieldListener;
import dtm.stools.component.inputfields.tagfield.TagInputField;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import dtm.stools.component.panels.card.CardPanel;
import dtm.stools.component.panels.scroll.ScrollPanel;
import dtm.stools.configs.UiTokens;
import dtm.stools.i18n.I18n;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

final class PhpProjectWizardView extends JPanel {

    private static final List<String> PHP_VERSIONS = List.of("8.4", "8.3", "8.2", "8.1", "8.0");
    private static final String DEFAULT_PHP_VERSION = "8.3";
    private static final String DEFAULT_VERSION = PhpProjectScaffolder.DEFAULT_VERSION;
    private static final String DEFAULT_LICENSE = PhpProjectScaffolder.DEFAULT_LICENSE;
    private static final int CHIP_COLUMNS = 2;

    private static final Pattern NAMESPACE = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(\\\\[A-Za-z_][A-Za-z0-9_]*)*");
    private static final Pattern PACKAGE_SEGMENT = Pattern.compile("[a-z0-9]([_.-]?[a-z0-9]+)*");
    private static final Pattern VERSION_TEXT = Pattern.compile("[0-9]+(\\.[0-9]+)*([-+][A-Za-z0-9.]+)?");
    private static final Pattern FOLDER_NAME = Pattern.compile("[^\\\\/:*?\"<>|]+");

    private static final String STEP_PROJECT = "project";
    private static final String STEP_COMPOSER = "composer";
    private static final String STEP_DEPENDENCIES = "dependencies";
    private static final String STEP_REVIEW = "review";

    private final PhpTemplate template;
    private final Supplier<Path> resourcesRoot;
    private final ProjectWizardCallback callback;
    private final PackagistClient packagist = new PackagistClient();
    private final PhpToolchainService toolchain = new PhpToolchainService();
    private final ComposerInstallerService composerInstaller = new ComposerInstallerService();
    private final ComposerService composerService = new ComposerService();

    private final MaskedTextField location = WizardUi.sized(new MaskedTextField());
    private final MaskedTextField projectName = WizardUi.sized(new MaskedTextField());
    private final MaskedTextField namespaceField = WizardUi.sized(new MaskedTextField());
    private final MaskedTextField vendor = WizardUi.sized(new MaskedTextField());
    private final MaskedTextField packageName = WizardUi.sized(new MaskedTextField());
    private final MaskedTextField version = WizardUi.sized(new MaskedTextField());
    private final MaskedTextField descriptionField = WizardUi.sized(new MaskedTextField());
    private final MaskedTextField license = WizardUi.sized(new MaskedTextField());
    private final MaskedTextField dependencyQuery = WizardUi.sized(new MaskedTextField());
    private final DropdownFieldListener<String> phpVersion =
            WizardUi.sized(new DropdownFieldListener<String>());
    private final SegmentedField<String> packageType = WizardUi.sized(new SegmentedField<String>());
    private final SegmentedField<String> dependencyScope = WizardUi.sized(new SegmentedField<String>());
    private final TagInputField modules = new TagInputField();
    private final DualListField<PackagistClient.Package> dependencySelector = new DualListField<>();
    private final JCheckBox runInstall = new JCheckBox();

    private final List<String> stepIds = new ArrayList<>();
    private final List<String> stepTitles = new ArrayList<>();
    private final Map<String, WizardFormGrid> forms = new LinkedHashMap<>();
    private final CardLayout bodyLayout = new CardLayout();
    private final JPanel body = WizardUi.transparent(bodyLayout);
    private final StepsPanel steps = new StepsPanel();
    private final JPanel reviewContent = WizardUi.transparent(new GridBagLayout());

    private final JButton backButton;
    private final JButton nextButton;
    private final JButton cancelButton;
    private final JLabel status = new JLabel(" ");
    private final ProgressBar progress = new ProgressBar().setIndeterminate(true)
            .setBarHeight(UiTokens.scale(4)).setShowLabel(false);
    private final AlertPanel alert = new AlertPanel(AlertPanel.Severity.ERROR, "", "")
            .setClosable(false).setShowIcon(true);

    private JLabel dependencyHint;
    private boolean packageEdited;
    private boolean namespaceEdited;
    private boolean busy;
    private int step;

    PhpProjectWizardView(PhpTemplate template, Supplier<Path> resourcesRoot,
                         ProjectWizardCallback callback) {
        super(new BorderLayout(0, UiTokens.space(3)));
        this.template = template;
        this.resourcesRoot = resourcesRoot;
        this.callback = callback;
        this.backButton = WizardUi.secondary(text("action.back", "Voltar"), null);
        this.nextButton = WizardUi.primary(text("action.next", "Avancar"), null);
        this.cancelButton = WizardUi.ghost(text("action.cancel", "Cancelar"), WizardIcons.cancel(15));

        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(UiTokens.space(5), UiTokens.space(5),
                UiTokens.space(4), UiTokens.space(5)));
        setPreferredSize(new Dimension(UiTokens.scale(720), UiTokens.scale(560)));

        buildSteps();
        applyDefaults();

        add(headerArea(), BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);

        wireDerivedFields();
        wireNavigation();
        showStep(0);

        if (template.allowsDependencySelection()) {
            loadDependencies("");
        }
    }

    private void buildSteps() {
        addStep(STEP_PROJECT, text("step.project", "Projeto"), scrollable(projectStep()));
        if (template.usesComposer()) {
            addStep(STEP_COMPOSER, text("step.composer", "Composer"), scrollable(composerStep()));
        }
        if (template.allowsDependencySelection()) {
            addStep(STEP_DEPENDENCIES, text("step.dependencies", "Dependencias"), dependenciesStep());
        }
        addStep(STEP_REVIEW, text("step.review", "Revisao"), scrollable(reviewStep()));

        steps.setSteps(List.copyOf(stepTitles));
        steps.setClickable(true);
        steps.setCircleSize(UiTokens.scale(24));
    }

    private void addStep(String id, String title, JComponent content) {
        stepIds.add(id);
        stepTitles.add(title);
        body.add(content, id);
    }

    private JComponent headerArea() {
        JPanel heading = WizardUi.transparent(new BorderLayout(0, UiTokens.space(4)));
        heading.add(header(), BorderLayout.NORTH);
        heading.add(steps, BorderLayout.SOUTH);
        return heading;
    }

    private JPanel header() {
        JPanel panel = WizardUi.transparent(new BorderLayout(UiTokens.space(4), 0));
        JLabel icon = new JLabel(WizardIcons.project(template, UiTokens.scale(40)));
        icon.setPreferredSize(new Dimension(UiTokens.scale(46), UiTokens.scale(46)));

        JLabel title = WizardUi.title(template.displayName(), UiTokens.scale(19));
        JLabel description = new JLabel("<html>" + template.description() + "</html>");
        description.setForeground(UiTokens.muted());
        description.setFont(UiTokens.fontSmall());

        JPanel copy = WizardUi.transparent(new BorderLayout(0, UiTokens.space(1)));
        copy.add(title, BorderLayout.NORTH);
        copy.add(description, BorderLayout.CENTER);

        panel.add(icon, BorderLayout.WEST);
        panel.add(copy, BorderLayout.CENTER);
        panel.add(buildBadge(), BorderLayout.EAST);
        return panel;
    }

    private JComponent buildBadge() {
        BadgeLabel badge = new BadgeLabel(template.badge(), BadgeLabel.Tone.INFO);
        badge.setStyle(BadgeLabel.Style.SOFT).setSize(BadgeLabel.Size.MD);
        JPanel holder = WizardUi.transparent(new BorderLayout());
        holder.add(badge, BorderLayout.NORTH);
        return holder;
    }

    private JComponent projectStep() {
        WizardFormGrid form = new WizardFormGrid(3);
        forms.put(STEP_PROJECT, form);

        form.addWide(new DelegatedFormField("location", text("field.location", "Pasta"),
                        locationRow(), location)
                        .setRequired(true)
                        .setHelperText(text("helper.location", "Pasta onde a pasta do projeto sera criada")))
                .add(field("name", text("field.name", "Nome do projeto"), projectName)
                        .setRequired(true)
                        .setValidator(rule(FOLDER_NAME,
                                text("error.invalidName", "Nome invalido para uma pasta."))), 2)
                .add(field("php", text("field.phpVersion", "Versao do PHP"), phpVersion)
                        .setRequired(true));

        if (template.usesComposer()) {
            form.addWide(field("namespace", text("field.namespace", "Namespace"), namespaceField)
                    .setRequired(true)
                    .setHelperText(text("helper.namespace", "Derivado do vendor e do nome do projeto"))
                    .setValidator(rule(NAMESPACE,
                            text("error.invalidNamespace", "Use o formato Vendor\\Projeto."))));
        }

        return stepCard(text("section.project", "Identidade do projeto"),
                text("section.projectHint", "Onde ele vai morar e como sera chamado"), form);
    }

    private JComponent composerStep() {
        WizardFormGrid form = new WizardFormGrid(3);
        forms.put(STEP_COMPOSER, form);

        form.add(field("vendor", text("field.vendor", "Vendor"), vendor)
                        .setRequired(true)
                        .setValidator(rule(PACKAGE_SEGMENT,
                                text("error.invalidVendor", "Letras minusculas, numeros, ponto e hifen."))))
                .add(field("packageName", text("field.packageName", "Pacote"), packageName)
                        .setRequired(true)
                        .setValidator(rule(PACKAGE_SEGMENT,
                                text("error.invalidPackage", "Letras minusculas, numeros, ponto e hifen."))))
                .add(field("version", text("field.version", "Versao"), version)
                        .setHelperText(text("helper.version", "Opcional; padrao " + DEFAULT_VERSION))
                        .setValidator(rule(VERSION_TEXT,
                                text("error.invalidVersion", "Versao invalida."))));

        form.add(field("type", text("field.type", "Tipo"), packageType))
                .add(field("license", text("field.license", "Licenca"), license), 2)
                .addWide(field("description", text("field.description", "Descricao"), descriptionField)
                        .setHelperText(text("helper.optional", "Opcional")));

        if (template.isOrigins()) {
            form.addWide(new TagFormField("modules", text("field.modules", "Modulos"), modules)
                    .setRequired(true)
                    .setHelperText(text("helper.modules",
                            "Enter adiciona um modulo; ao menos um e necessario")));
        }

        return stepCard(text("section.composer", "Metadados do composer.json"),
                text("section.composerHint",
                        "Como o pacote sera identificado e resolvido pelo Composer"), form);
    }

    private JComponent dependenciesStep() {
        dependencySelector.setEnabled(false);
        dependencySelector
                .setTitles(text("list.available", "Disponiveis"), text("list.selected", "Selecionadas"))
                .setShowFilter(true).setShowCounters(true).setReorderable(false)
                .setRowHeight(UiTokens.scale(46))
                .setCellRenderer(new PackageRenderer());
        dependencySelector.setPreferredSize(new Dimension(UiTokens.scale(560), UiTokens.scale(250)));

        dependencyHint = WizardUi.muted(text("status.loadingOptions", "Consultando o Packagist..."));

        JPanel content = WizardUi.transparent(new BorderLayout(0, UiTokens.space(2)));
        content.add(searchRow(), BorderLayout.NORTH);
        content.add(dependencySelector, BorderLayout.CENTER);
        content.add(dependencyHint, BorderLayout.SOUTH);

        CardPanel card = WizardUi.section(text("section.dependencies", "Dependencias do Composer"),
                text("section.dependenciesHint",
                        "Busque no Packagist e passe para a direita o que entra no require"),
                content);
        JPanel holder = WizardUi.transparent(new BorderLayout());
        holder.add(card, BorderLayout.CENTER);
        return holder;
    }

    private JComponent searchRow() {
        dependencyQuery.setPlaceholder(text("placeholder.search", "monolog, guzzle, symfony/console..."));
        JButton search = WizardUi.iconAction(WizardIcons.search(16),
                text("action.search", "Buscar no Packagist"));
        search.addActionListener(event -> loadDependencies(dependencyQuery.getText()));
        dependencyQuery.addActionListener(event -> loadDependencies(dependencyQuery.getText()));

        dependencyScope.addSegment(text("scope.require", "require"), "require")
                .addSegment(text("scope.requireDev", "require-dev"), "require-dev")
                .setArc(UiTokens.radius(UiTokens.Radius.SM))
                .setPreferredHeight(UiTokens.scale(WizardUi.FIELD_HEIGHT))
                .setSelectedIndex(0, false);

        JPanel field = WizardUi.transparent(new BorderLayout(UiTokens.space(2), 0));
        field.add(dependencyQuery, BorderLayout.CENTER);
        field.add(search, BorderLayout.EAST);

        JPanel row = WizardUi.transparent(new BorderLayout(UiTokens.space(3), 0));
        row.add(field, BorderLayout.CENTER);
        row.add(dependencyScope, BorderLayout.EAST);
        row.setPreferredSize(new Dimension(UiTokens.scale(560), UiTokens.scale(WizardUi.FIELD_HEIGHT)));
        return row;
    }

    private JComponent reviewStep() {
        JPanel content = WizardUi.transparent(new BorderLayout(0, UiTokens.space(3)));
        content.add(reviewContent, BorderLayout.CENTER);
        if (template.usesComposer()) {
            runInstall.setText(template.needsCreateProject()
                    ? text("option.createProject", "Gerar com composer create-project")
                    : text("option.install", "Executar composer install ao final"));
            runInstall.setSelected(true);
            runInstall.setOpaque(false);
            runInstall.setEnabled(!template.needsCreateProject());
            runInstall.setFont(UiTokens.fontSmall());
            runInstall.setForeground(UiTokens.foreground());
            content.add(runInstall, BorderLayout.SOUTH);
        }
        return stepCard(text("section.review", "Revisao"),
                text("section.reviewHint", "Confira antes de criar"), content);
    }

    private JComponent stepCard(String title, String subtitle, JComponent content) {
        JPanel panel = WizardUi.transparent(new BorderLayout());
        panel.add(WizardUi.section(title, subtitle, content), BorderLayout.NORTH);
        return panel;
    }

    private JPanel locationRow() {
        JPanel row = WizardUi.transparent(new BorderLayout(UiTokens.space(2), 0));
        JButton browse = WizardUi.iconAction(WizardIcons.folder(16),
                text("action.browse", "Escolher pasta"));
        browse.addActionListener(event -> chooseLocation());
        row.add(location, BorderLayout.CENTER);
        row.add(browse, BorderLayout.EAST);
        row.setPreferredSize(new Dimension(UiTokens.scale(320), UiTokens.scale(WizardUi.FIELD_HEIGHT)));
        return row;
    }

    private JComponent footer() {
        progress.setVisible(false);
        alert.setVisible(false);
        status.setForeground(UiTokens.muted());
        status.setFont(UiTokens.fontSmall());

        JPanel state = WizardUi.transparent(new BorderLayout(0, UiTokens.space(2)));
        state.add(status, BorderLayout.NORTH);
        state.add(alert, BorderLayout.CENTER);

        JPanel buttons = WizardUi.transparent(new FlowLayout(FlowLayout.RIGHT, UiTokens.space(2), 0));
        buttons.add(cancelButton);
        buttons.add(backButton);
        buttons.add(nextButton);

        JPanel bar = WizardUi.transparent(new BorderLayout(UiTokens.space(4), UiTokens.space(2)));
        bar.add(state, BorderLayout.CENTER);
        bar.add(buttons, BorderLayout.EAST);

        JPanel panel = WizardUi.transparent(new BorderLayout(0, UiTokens.space(2)));
        panel.add(progress, BorderLayout.NORTH);
        panel.add(bar, BorderLayout.CENTER);
        return panel;
    }

    private JComponent scrollable(JComponent content) {
        ScrollPanel scroll = new ScrollPanel(new ViewportWidthPanel(content));
        scroll.setHorizontalScrollEnabled(false);
        scroll.setUnitIncrement(UiTokens.scale(16));
        return scroll;
    }

    private void applyDefaults() {
        location.setPlaceholder("C:\\workspace");
        location.setText(System.getProperty("user.home", ""));
        projectName.setPlaceholder("meu-projeto");
        vendor.setPlaceholder("vendor");
        vendor.setText("vendor");
        packageName.setPlaceholder("meu-projeto");
        version.setPlaceholder(DEFAULT_VERSION);
        version.setText(DEFAULT_VERSION);
        license.setPlaceholder(DEFAULT_LICENSE);
        license.setText(DEFAULT_LICENSE);
        namespaceField.setPlaceholder("Vendor\\MeuProjeto");
        descriptionField.setPlaceholder(text("placeholder.description", "Projeto gerado pela Orion"));

        phpVersion.setModel(new DefaultComboBoxModel<>(PHP_VERSIONS.toArray(String[]::new)));
        phpVersion.setSelectedItem(DEFAULT_PHP_VERSION);

        packageType.addSegment(text("type.project", "project"), "project")
                .addSegment(text("type.library", "library"), "library")
                .setArc(UiTokens.radius(UiTokens.Radius.SM))
                .setPreferredHeight(UiTokens.scale(WizardUi.FIELD_HEIGHT))
                .setSelectedIndex(template.isLibrary() ? 1 : 0, false);

        modules.setPlaceholder(text("placeholder.modules", "App, Admin"));
        modules.setTags(List.of("App"));
    }

    private void wireDerivedFields() {
        projectName.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            String name = projectName.getText().trim();
            if (!packageEdited) {
                packageName.setText(PhpProjectScaffolder.kebab(name));
            }
            if (!namespaceEdited) {
                namespaceField.setText(derivedNamespace(name));
            }
            refreshLocationHint();
        }));
        location.getDocument().addDocumentListener(
                new SimpleDocumentListener(this::refreshLocationHint));
        vendor.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            if (!namespaceEdited) {
                namespaceField.setText(derivedNamespace(projectName.getText().trim()));
            }
        }));
        packageName.getDocument().addDocumentListener(new SimpleDocumentListener(
                () -> packageEdited |= packageName.hasFocus()));
        namespaceField.getDocument().addDocumentListener(new SimpleDocumentListener(
                () -> namespaceEdited |= namespaceField.hasFocus()));
    }

    private void wireNavigation() {
        backButton.addActionListener(event -> showStep(step - 1));
        nextButton.addActionListener(event -> advance());
        cancelButton.addActionListener(event -> callback.cancel());
        steps.addEventListener(StepsPanel.STEP_SELECTED, event -> {
            Object selected = event.getValue();
            if (selected instanceof Integer target && target != step) {
                showStep(target);
            }
        });
    }

    private void showStep(int target) {
        if (busy || target < 0 || target >= stepIds.size()) {
            return;
        }
        step = target;
        alert.setVisible(false);
        bodyLayout.show(body, stepIds.get(step));
        steps.setCurrentStep(step, false);

        boolean last = step == stepIds.size() - 1;
        backButton.setVisible(step > 0);
        nextButton.setText(last
                ? text("action.create", "Criar projeto")
                : text("action.next", "Avancar"));
        nextButton.setIcon(last ? WizardIcons.create(16) : null);
        if (last) {
            refreshReview();
        }
        revalidate();
        repaint();
    }

    private void advance() {
        boolean last = step == stepIds.size() - 1;
        if (!validateUpTo(last ? stepIds.size() - 1 : step)) {
            return;
        }
        if (last) {
            create();
            return;
        }
        showStep(step + 1);
    }

    private boolean validateUpTo(int lastStep) {
        for (int index = 0; index <= lastStep && index < stepIds.size(); index++) {
            WizardFormGrid form = forms.get(stepIds.get(index));
            if (form == null) {
                continue;
            }
            List<FormField> invalid = form.invalidFields();
            if (!invalid.isEmpty()) {
                failOn(index, invalid.getFirst(),
                        text("error.checkFields", "Revise os campos destacados."));
                return false;
            }
        }
        return validateDestination();
    }

    private boolean validateDestination() {
        WizardFormGrid form = forms.get(STEP_PROJECT);
        Path parent = locationDirectory();
        if (parent == null) {
            return failField(form, "location", text("error.invalidLocation", "Pasta invalida."));
        }
        if (Files.exists(parent) && !Files.isDirectory(parent)) {
            return failField(form, "location",
                    text("error.locationNotDirectory", "O caminho escolhido nao e uma pasta."));
        }
        String name = projectFolderName();
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
            return failField(form, "name", text("error.invalidName", "Nome invalido para uma pasta."));
        }
        Path directory = parent.resolve(name).normalize();
        if (Files.exists(directory) && !Files.isDirectory(directory)) {
            return failField(form, "name",
                    text("error.nameTaken", "Ja existe um arquivo com esse nome na pasta."));
        }
        if (Files.isDirectory(directory) && !isEmptyDirectory(directory)) {
            return failField(form, "name",
                    text("error.directoryNotEmpty", "A pasta do projeto ja existe e nao esta vazia."));
        }
        alert.setVisible(false);
        return true;
    }

    private boolean failField(WizardFormGrid form, String fieldName, String message) {
        FormField field = form == null ? null : form.field(fieldName);
        if (field != null) {
            field.setError(message);
        }
        failOn(stepIds.indexOf(STEP_PROJECT), field, message);
        return false;
    }

    private void failOn(int stepIndex, FormField field, String message) {
        if (stepIndex != step) {
            showStep(stepIndex);
        }
        showError(message);
        if (field != null) {
            field.getControl().requestFocusInWindow();
        }
    }

    private void refreshReview() {
        reviewContent.removeAll();
        int line = 0;
        Path directory = targetDirectory();
        line = summaryRow(line, text("review.destination", "Destino"),
                directory == null ? "-" : directory.toString());
        line = summaryRow(line, text("review.template", "Modelo"), template.displayName());
        line = summaryRow(line, text("review.php", "PHP"), selectedPhpVersion());
        if (template.usesComposer()) {
            line = summaryRow(line, text("review.package", "Pacote"), fullPackageName());
            line = summaryRow(line, text("review.namespace", "Namespace"),
                    namespaceField.getText().trim());
            line = summaryRow(line, text("review.version", "Versao"), effectiveVersion());
            line = summaryRow(line, text("review.license", "Licenca"), effectiveLicense());
        }
        if (template.needsCreateProject()) {
            line = summaryRow(line, text("review.source", "Origem"),
                    "composer create-project " + template.createProjectPackage());
        }
        if (template.isOrigins()) {
            line = summaryRow(line, text("review.modules", "Modulos"),
                    String.join(", ", modules.getTags()));
        }
        if (template.allowsDependencySelection()) {
            summaryChips(line, text("review.dependencies", "Dependencias"),
                    dependencySelector.getSelected());
        }
        reviewContent.revalidate();
        reviewContent.repaint();
    }

    private int summaryRow(int line, String key, String value) {
        reviewContent.add(WizardUi.summaryKey(key), keyConstraints(line));
        reviewContent.add(WizardUi.summaryValue("<html>"
                + (value == null || value.isBlank() ? "-" : value) + "</html>"), valueConstraints(line));
        return line + 1;
    }

    private void summaryChips(int line, String key, List<PackagistClient.Package> selected) {
        reviewContent.add(WizardUi.summaryKey(key), keyConstraints(line));

        JPanel chips = WizardUi.transparent(new GridBagLayout());
        if (selected.isEmpty()) {
            chips.add(WizardUi.muted(text("review.noDependencies", "Nenhuma")), chipConstraints(0));
        } else {
            for (int index = 0; index < selected.size(); index++) {
                BadgeLabel chip = new BadgeLabel(selected.get(index).name(), BadgeLabel.Tone.PRIMARY);
                chip.setStyle(BadgeLabel.Style.SOFT).setSize(BadgeLabel.Size.SM);
                chips.add(chip, chipConstraints(index));
            }
        }
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = CHIP_COLUMNS;
        filler.gridy = 0;
        filler.weightx = 1;
        filler.fill = GridBagConstraints.HORIZONTAL;
        chips.add(Box.createHorizontalGlue(), filler);

        reviewContent.add(chips, valueConstraints(line));
    }

    private static GridBagConstraints chipConstraints(int index) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = index % CHIP_COLUMNS;
        constraints.gridy = index / CHIP_COLUMNS;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(0, 0, UiTokens.space(1), UiTokens.space(2));
        return constraints;
    }

    private static GridBagConstraints keyConstraints(int line) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = line;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = new Insets(0, 0, UiTokens.space(2), UiTokens.space(4));
        return constraints;
    }

    private static GridBagConstraints valueConstraints(int line) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 1;
        constraints.gridy = line;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = new Insets(0, 0, UiTokens.space(2), 0);
        return constraints;
    }

    private void loadDependencies(String query) {
        setBusy(true, text("status.loadingOptions", "Consultando o Packagist..."));
        Thread worker = new Thread(() -> {
            List<PackagistClient.Package> packages = packagist.search(query);
            SwingUtilities.invokeLater(() -> applyPackages(packages));
        }, "packagist-search");
        worker.setDaemon(true);
        worker.start();
    }

    private void applyPackages(List<PackagistClient.Package> packages) {
        setBusy(false, " ");
        if (packages.isEmpty()) {
            String message = text("status.offline", "Nao foi possivel consultar o Packagist.");
            dependencyHint.setText(message);
            showOfflineAlert(message);
            return;
        }
        List<PackagistClient.Package> selected = dependencySelector.getSelected();
        List<PackagistClient.Package> available = new ArrayList<>(packages);
        available.removeIf(selected::contains);
        dependencySelector.setAvailable(available);
        dependencySelector.setEnabled(true);
        dependencyHint.setText(packages.size() + " "
                + text("status.packagesAvailable", "pacotes encontrados"));
    }

    private void showOfflineAlert(String message) {
        JButton retry = WizardUi.secondary(text("action.retry", "Tentar novamente"), null);
        retry.addActionListener(event -> loadDependencies(dependencyQuery.getText()));
        alert.setSeverity(AlertPanel.Severity.WARNING)
                .setTitle(text("error.offlineTitle", "Sem catalogo de dependencias"))
                .setMessage(message)
                .addAction(retry)
                .restore();
        alert.setVisible(true);
        revalidate();
        repaint();
    }

    private void chooseLocation() {
        JFileChooser chooser = new JFileChooser(location.getText());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle(text("action.browse", "Escolher pasta"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            location.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void create() {
        Path directory = targetDirectory();
        if (directory == null) {
            showError(text("error.invalidLocation", "Pasta invalida."));
            return;
        }
        setBusy(true, text("status.creating", "Criando e configurando o projeto..."));
        Thread worker = new Thread(() -> {
            try {
                Path created = template.needsCreateProject()
                        ? generateWithComposer(directory)
                        : generateLocally(directory);
                String warning = template.needsCreateProject() ? null : runComposerInstall(created);
                SwingUtilities.invokeLater(() -> {
                    if (warning != null) {
                        showWarning(warning);
                    }
                    callback.notifyProjectCreated(created);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    setBusy(false, " ");
                    showError(text("error.createFailed", "Falha ao criar:") + " " + messageOf(e));
                });
            }
        }, "php-project-wizard");
        worker.setDaemon(true);
        worker.start();
    }

    private Path generateLocally(Path directory) throws Exception {
        return PhpProjectScaffolder.create(new PhpProjectScaffolder.ProjectRequest(
                directory, template, vendor.getText().trim(), packageName.getText().trim(),
                effectiveVersion(), descriptionField.getText().trim(),
                namespaceField.getText().trim(), selectedPhpVersion(), effectiveLicense(),
                template.isOrigins() ? modules.getTags() : List.of(), selectedDependencies()));
    }

    private Path generateWithComposer(Path directory) throws Exception {
        List<String> launcher = composerLauncher();
        Path parent = directory.getParent();
        if (parent == null) {
            throw new IllegalStateException("Pasta de destino invalida.");
        }
        Files.createDirectories(parent);
        if (Files.isDirectory(directory) && isEmptyDirectory(directory)) {
            Files.delete(directory);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int exit = composerService.execute(launcher, parent, output,
                "create-project", template.createProjectPackage(),
                directory.getFileName().toString(), "--no-interaction").join();
        if (exit != 0 || !Files.isDirectory(directory)) {
            throw new IllegalStateException(tail(output));
        }
        return directory;
    }

    private String runComposerInstall(Path directory) {
        if (!template.usesComposer() || !runInstall.isSelected()) {
            return null;
        }
        try {
            List<String> launcher = composerLauncher();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int exit = composerService.execute(launcher, directory, output,
                    "install", "--no-interaction").join();
            return exit == 0 ? null
                    : text("warning.installFailed",
                    "O projeto foi criado, mas o composer install falhou:") + " " + tail(output);
        } catch (Exception e) {
            return text("warning.installFailed",
                    "O projeto foi criado, mas o composer install falhou:") + " " + messageOf(e);
        }
    }

    private List<String> composerLauncher() throws Exception {
        Path resources = resourcesRoot == null ? null : resourcesRoot.get();
        return composerInstaller.ensure(resources, toolchain);
    }

    private List<PhpProjectScaffolder.Dependency> selectedDependencies() {
        if (!template.allowsDependencySelection()) {
            return List.of();
        }
        boolean development = "require-dev".equals(String.valueOf(dependencyScope.getSelectedValue()));
        List<PhpProjectScaffolder.Dependency> dependencies = new ArrayList<>();
        for (PackagistClient.Package selected : dependencySelector.getSelected()) {
            String constraint = packagist.latestConstraint(selected.name()).orElse("*");
            dependencies.add(new PhpProjectScaffolder.Dependency(
                    selected.name(), constraint, development));
        }
        return List.copyOf(dependencies);
    }

    private void setBusy(boolean running, String message) {
        this.busy = running;
        nextButton.setEnabled(!running);
        backButton.setEnabled(!running);
        progress.setVisible(running);
        alert.setVisible(false);
        status.setForeground(UiTokens.muted());
        status.setIcon(running ? WizardIcons.loading(14) : null);
        status.setText(message == null || message.isBlank() ? " " : message);
    }

    private void showError(String message) {
        status.setIcon(null);
        status.setText(" ");
        alert.setSeverity(AlertPanel.Severity.ERROR)
                .setTitle(text("error.title", "Nao foi possivel continuar"))
                .setMessage(message).restore();
        alert.setVisible(true);
        revalidate();
        repaint();
    }

    private void showWarning(String message) {
        status.setIcon(null);
        status.setText(" ");
        alert.setSeverity(AlertPanel.Severity.WARNING)
                .setTitle(text("warning.title", "Projeto criado com avisos"))
                .setMessage(message).restore();
        alert.setVisible(true);
        revalidate();
        repaint();
    }

    private void refreshLocationHint() {
        WizardFormGrid form = forms.get(STEP_PROJECT);
        FormField field = form == null ? null : form.field("location");
        if (field == null) {
            return;
        }
        Path directory = targetDirectory();
        field.setHelperText(directory == null
                ? text("helper.location", "Pasta onde a pasta do projeto sera criada")
                : text("helper.target", "Sera criado em") + " " + directory);
    }

    private String selectedPhpVersion() {
        Object selected = phpVersion.getSelectedItem();
        return selected == null ? DEFAULT_PHP_VERSION : String.valueOf(selected);
    }

    private String fullPackageName() {
        return vendor.getText().trim().toLowerCase(Locale.ROOT) + "/"
                + packageName.getText().trim().toLowerCase(Locale.ROOT);
    }

    private String effectiveVersion() {
        String typed = version.getText().trim();
        return typed.isBlank() ? DEFAULT_VERSION : typed;
    }

    private String effectiveLicense() {
        String typed = license.getText().trim();
        return typed.isBlank() ? DEFAULT_LICENSE : typed;
    }

    private String derivedNamespace(String name) {
        String prefix = PhpProjectScaffolder.pascal(vendor.getText().trim());
        String suffix = PhpProjectScaffolder.pascal(name);
        return name.isBlank() ? prefix : prefix + "\\" + suffix;
    }

    private Path locationDirectory() {
        String folder = location.getText().trim();
        if (folder.isBlank()) {
            return null;
        }
        try {
            return Path.of(folder).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private String projectFolderName() {
        String typed = projectName.getText().trim().replace('\\', '/');
        int slash = typed.lastIndexOf('/');
        return slash < 0 ? typed : typed.substring(slash + 1).trim();
    }

    private Path targetDirectory() {
        Path parent = locationDirectory();
        String name = projectFolderName();
        if (parent == null || name.isBlank()) {
            return null;
        }
        try {
            return parent.resolve(name).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static FormField field(String name, String label, JComponent control) {
        return new FormField(name, label, control).setHelperText(" ");
    }

    private static Validator<String> rule(Pattern pattern, String message) {
        return value -> {
            String typed = value == null ? "" : value.trim();
            if (typed.isBlank() || pattern.matcher(typed).matches()) {
                return ValidationResult.ok();
            }
            return ValidationResult.error(message);
        };
    }

    private static boolean isEmptyDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static String messageOf(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static String tail(ByteArrayOutputStream output) {
        String content = output.toString(StandardCharsets.UTF_8).trim();
        if (content.isBlank()) {
            return "composer falhou sem saida.";
        }
        String[] lines = content.split("\\R");
        int from = Math.max(0, lines.length - 4);
        return String.join(" ", List.of(lines).subList(from, lines.length));
    }

    private static String text(String key, String fallback) {
        return I18n.getText(PhpProjectWizardView.class, key, fallback);
    }

    private static final class TagFormField extends FormField {
        private TagFormField(String name, String labelText, TagInputField control) {
            super(name, labelText, control);
        }

        @Override
        public Object getValue() {
            return ((TagInputField) getControl()).getTags();
        }

        @Override
        public FormField setValue(Object value) {
            if (value instanceof List<?> list) {
                ((TagInputField) getControl()).setTags(list.stream().map(String::valueOf).toList());
            }
            return this;
        }
    }

    private static final class DelegatedFormField extends FormField {
        private final JComponent value;

        private DelegatedFormField(String name, String labelText, JComponent wrapper, JComponent value) {
            super(name, labelText, wrapper);
            this.value = value;
            if (value instanceof javax.swing.text.JTextComponent input) {
                input.getDocument().addDocumentListener(new SimpleDocumentListener(this::clearError));
            }
        }

        @Override
        public Object getValue() {
            return FormValues.read(value);
        }

        @Override
        public FormField setValue(Object newValue) {
            FormValues.write(value, newValue);
            return this;
        }
    }

    private static final class ViewportWidthPanel extends JPanel implements Scrollable {
        private ViewportWidthPanel(JComponent content) {
            super(new BorderLayout());
            setOpaque(false);
            add(content, BorderLayout.NORTH);
        }

        @Override public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return UiTokens.scale(16);
        }

        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return visible.height;
        }

        @Override public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static final class PackageRenderer extends DefaultListCellRenderer {

        private static final int MAX_DESCRIPTION = 70;

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            if (!(value instanceof PackagistClient.Package item)) {
                return label;
            }
            String muted = hex(UiTokens.muted());
            label.setText("<html><b>" + item.name() + "</b> &nbsp; <font color='" + muted + "'>"
                    + downloads(item.downloads()) + "</font><br><font color='" + muted + "'>"
                    + shorten(item.description()) + "</font></html>");
            label.setIcon(WizardIcons.dependency(16));
            label.setIconTextGap(UiTokens.space(2));
            label.setFont(UiTokens.fontSmall());
            label.setBorder(BorderFactory.createEmptyBorder(UiTokens.space(1), UiTokens.space(2),
                    UiTokens.space(1), UiTokens.space(2)));
            label.setToolTipText(item.description());
            return label;
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension preferred = super.getPreferredSize();
            preferred.width = Math.min(preferred.width, UiTokens.scale(260));
            return preferred;
        }

        private static String downloads(long value) {
            if (value >= 1_000_000) {
                return (value / 1_000_000) + "M downloads";
            }
            if (value >= 1_000) {
                return (value / 1_000) + "k downloads";
            }
            return value + " downloads";
        }

        private static String shorten(String description) {
            if (description == null || description.isBlank()) {
                return "";
            }
            String value = description.trim();
            return value.length() <= MAX_DESCRIPTION
                    ? value
                    : value.substring(0, MAX_DESCRIPTION - 1).trim() + "...";
        }

        private static String hex(Color color) {
            return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
        }
    }

    private record SimpleDocumentListener(Runnable action) implements DocumentListener {
        @Override public void insertUpdate(DocumentEvent event) {
            action.run();
        }

        @Override public void removeUpdate(DocumentEvent event) {
            action.run();
        }

        @Override public void changedUpdate(DocumentEvent event) {
            action.run();
        }
    }
}
