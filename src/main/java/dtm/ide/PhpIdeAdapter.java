package dtm.ide;

import dtm.di.annotations.Singleton;
import dtm.ide.api.annotations.PluginReference;
import dtm.ide.api.context.IdeProjectContext;
import dtm.ide.api.extension.IdeAdapter;
import dtm.ide.api.extension.event.BreakpointChangedEvent;
import dtm.ide.api.extension.menu.IdeMenuBarBuilder;
import dtm.ide.api.extension.menu.IdeMenuBuilder;
import dtm.ide.api.extension.output.OutputPanelHandle;
import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.api.extension.runconfig.RunExecutionContext;
import dtm.ide.api.extension.runconfig.RunProcessHandle;
import dtm.ide.api.extension.screen.ToolIconType;
import dtm.ide.api.extension.settings.PluginSettingsPage;
import dtm.ide.api.project.editor.DocumentHighlight;
import dtm.ide.api.project.editor.FileAssociated;
import dtm.ide.api.project.editor.FormatCodeContext;
import dtm.ide.api.project.editor.IdeCompletionContext;
import dtm.ide.api.project.editor.IdeCompletionTriggerKind;
import dtm.ide.api.project.editor.IdeCodeLensContext;
import dtm.ide.api.project.editor.IdeCodeActionContext;
import dtm.ide.api.project.editor.IdeDefinitionContext;
import dtm.ide.api.project.editor.IdeDiagnosticsContext;
import dtm.ide.api.project.editor.IdeDocumentHighlightContext;
import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.api.project.editor.IdeFormatScope;
import dtm.ide.api.project.editor.IdeHoverContext;
import dtm.ide.api.project.editor.IdeSignatureHelpContext;
import dtm.ide.api.project.editor.IdeWordClickContext;
import dtm.ide.api.project.editor.IdeWordCaretContext;
import dtm.ide.api.project.editor.NativeEditorType;
import dtm.ide.api.project.tree.ProjectTreeIgnoreRule;
import dtm.ide.api.theme.EditorTheme;
import dtm.ide.composer.ComposerService;
import dtm.ide.debug.PhpDapSession;
import dtm.ide.debug.PhpDebugAdapterInstallerService;
import dtm.ide.debug.PhpDebugService;
import dtm.ide.debug.XdebugInstallerService;
import dtm.ide.debug.XdebugStatus;
import dtm.ide.diagnostics.PhpStrictDiagnostics;
import dtm.ide.editor.PhpEditorTheme;
import dtm.ide.editor.HtmlCompletionProvider;
import dtm.ide.editor.PhpConfigTokenizerProvider;
import dtm.ide.editor.PhpTokenizerProvider;
import dtm.ide.lsp.PhpLanguageServerProcess;
import dtm.ide.lsp.PhpLanguageService;
import dtm.ide.lsp.PhpToolchainService;
import dtm.ide.navigation.PhpDefinitionIndex;
import dtm.ide.navigation.PhpNavigationPopup;
import dtm.ide.origins.OriginsExplorerPanel;
import dtm.ide.origins.OriginsIndexer;
import dtm.ide.origins.OriginsSnapshot;
import dtm.ide.project.PhpProjectConventions;
import dtm.ide.project.PhpProjectDescriptor;
import dtm.ide.run.PhpRunSupport;
import dtm.ide.settings.PhpPluginSettings;
import dtm.ide.settings.PhpSettingsPage;
import dtm.stools.component.panels.dock.DockRegion;
import dtm.stools.component.panels.editor.code.api.CodeAction;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.codelens.CodeLens;
import dtm.stools.component.panels.editor.code.codelens.CodeLensItem;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
import dtm.stools.component.panels.editor.code.prototype.folding.FoldRule;
import dtm.stools.component.panels.editor.code.provider.TokenizerCodeEditorProvider;
import dtm.stools.component.panels.editor.code.signature.SignatureHelp;
import dtm.stools.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JOptionPane;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
@Singleton
@PluginReference(id = "php-ide-adapter")
public class PhpIdeAdapter extends IdeAdapter {

    private static String text(String key, String fallback) {
        return dtm.stools.i18n.I18n.getText(PhpIdeAdapter.class, key, fallback);
    }

    private final PhpTokenizerProvider tokenizer = new PhpTokenizerProvider();
    private final HtmlCompletionProvider htmlCompletion = new HtmlCompletionProvider();
    private final PhpConfigTokenizerProvider envTokenizer = new PhpConfigTokenizerProvider(PhpConfigTokenizerProvider.Mode.ENV);
    private final PhpConfigTokenizerProvider htaccessTokenizer = new PhpConfigTokenizerProvider(PhpConfigTokenizerProvider.Mode.HTACCESS);
    private final PhpConfigTokenizerProvider modulesTokenizer = new PhpConfigTokenizerProvider(PhpConfigTokenizerProvider.Mode.MODULES);
    private final EditorTheme theme = new PhpEditorTheme();
    private final OriginsExplorerPanel originsPanel = new OriginsExplorerPanel();
    private final PhpPluginSettings settings = new PhpPluginSettings();
    private final PhpSettingsPage settingsPage = new PhpSettingsPage(settings);
    private final PhpStrictDiagnostics strictDiagnostics = new PhpStrictDiagnostics();
    private final AtomicLong lifecycle = new AtomicLong();
    private final AtomicBoolean lspStarting = new AtomicBoolean();
    private final Set<Path> openPhpFiles = ConcurrentHashMap.newKeySet();
    private final Map<Path, String> openPhpTexts = new ConcurrentHashMap<>();
    private final AtomicLong wordCaretTicket = new AtomicLong();
    private final ScheduledExecutorService codeActionDelayExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "php-code-action-delay");
                thread.setDaemon(true);
                return thread;
            });
    private volatile Path projectRoot;
    private volatile PhpProjectDescriptor descriptor;
    private volatile IdeProjectContext projectContext;
    private volatile PhpToolchainService toolchain;
    private volatile PhpLanguageService languageService;
    private volatile OriginsIndexer originsIndexer;
    private volatile ComposerService composerService;
    private volatile PhpRunSupport runSupport;
    private volatile XdebugInstallerService xdebugInstaller;
    private volatile PhpDebugAdapterInstallerService debugAdapterInstaller;
    private volatile PhpDebugService debugService;
    private volatile PhpDefinitionIndex definitionIndex;
    private volatile OriginsSnapshot originsSnapshot;
    private volatile String originsPanelId;
    private volatile IdeEditorContext debugEditor;
    private volatile int debugLine = -1;
    private volatile JComponent codeActionLamp;
    private volatile JLayeredPane codeActionLampLayer;

    @Override
    public boolean supports(Path path) {
        return PhpProjectConventions.supports(path);
    }

    @Override
    public String getProjectType() {
        return PhpProjectConventions.PROJECT_TYPE;
    }

    @Override
    public boolean handlesPath(Path path) {
        return PhpProjectConventions.handlesPath(path);
    }

    @Override
    public void onAdapterSelected(IdeProjectContext context) {
        bind(context);
    }

    @Override
    public void onProjectOpened(IdeProjectContext context) {
        bind(context);
        setupOriginsPanel();
        requestSetRunButtonEnabled(true);
        requestSetDebugButtonEnabled(true);
    }

    private void bind(IdeProjectContext context) {
        applySettings();
        this.projectContext = context;
        this.projectRoot = context == null ? null
                : context.getProjectPath().map(PhpProjectConventions::normalize).orElse(null);
        this.descriptor = PhpProjectConventions.describe(projectRoot);
        long ticket = lifecycle.incrementAndGet();
        ensureServices();
        startLanguageServer(ticket);
        rebuildDefinitions();
        rebuildOrigins(ticket);
    }

    private void ensureServices() {
        if (toolchain == null) toolchain = newInstance(PhpToolchainService.class);
        if (originsIndexer == null) originsIndexer = newInstance(OriginsIndexer.class);
        if (composerService == null) composerService = newInstance(ComposerService.class);
        if (runSupport == null) {
            runSupport = newInstance(PhpRunSupport.class);
            runSupport.configureXampp(settings::xamppRoot, this::persistXamppRoot,
                    this::createModernComponentDialogBuilder);
        }
        if (xdebugInstaller == null) xdebugInstaller = newInstance(XdebugInstallerService.class);
        if (debugAdapterInstaller == null) {
            debugAdapterInstaller = newInstance(PhpDebugAdapterInstallerService.class);
        }
        if (debugService == null) debugService = newInstance(PhpDebugService.class);
        if (definitionIndex == null) definitionIndex = newInstance(PhpDefinitionIndex.class);
        if (languageService == null) {
            languageService = newInstance(PhpLanguageService.class, (Consumer<Path>) this::onDiagnosticsPublished);
        }
    }

    private void startLanguageServer(long ticket) {
        Path root = projectRoot;
        if (root == null) return;
        if (process().isPresent() || !lspStarting.compareAndSet(false, true)) return;
        Path resources = getApplicationContext().getResourcesPath();
        log.info("Inicializando Intelephense para {} em {}", root, resources);
        showProgress("phpLsp", "Preparando Intelephense");
        toolchain.ensureIntelephense(resources)
                .thenCompose(command -> {
                    if (!current(ticket, root)) return CompletableFuture.completedFuture(null);
                    Path storage = resources.resolve("php").resolve("workspaces")
                            .resolve(Integer.toHexString(root.toString().toLowerCase().hashCode()));
                    return languageService.start(command, root, storage);
                })
                .whenComplete((unused, error) -> {
                    lspStarting.set(false);
                    hideProgress("phpLsp");
                    if (error != null && current(ticket, root)) {
                        log.warn("Intelephense indisponível", error);
                        setStatusBarText("PHP: Intelephense indisponível — " + rootMessage(error));
                    } else if (current(ticket, root)) {
                        refreshOpenPhpFiles();
                        setStatusBarText("PHP: Intelephense pronto");
                    }
                });
    }

    private void rebuildDefinitions() {
        Path root = projectRoot;
        if (root != null && definitionIndex != null) {
            definitionIndex.rebuild(root)
                    .thenRun(() -> openPhpFiles.forEach(this::requestRefreshCodeLenses))
                    .exceptionally(error -> {
                log.debug("Índice nativo de definições PHP falhou: {}", rootMessage(error));
                return null;
                    });
        }
    }

    private void rebuildOrigins(long ticket) {
        Path root = projectRoot;
        if (root == null || descriptor == null || !descriptor.origins()) {
            originsSnapshot = OriginsSnapshot.empty(root);
            originsPanel.showSnapshot(originsSnapshot);
            return;
        }
        originsIndexer.rebuild(root).whenComplete((snapshot, error) -> {
            if (!current(ticket, root)) return;
            if (error != null) {
                log.warn("Falha ao indexar Origins", error);
                return;
            }
            originsSnapshot = snapshot;
            originsPanel.showSnapshot(snapshot);
        });
    }

    private boolean current(long ticket, Path root) {
        return lifecycle.get() == ticket && root != null && root.equals(projectRoot);
    }

    private void setupOriginsPanel() {
        if (originsPanelId != null) return;
        originsPanel.setOpenHandler((file, line) -> {
            requestOpenFile(file);
            SwingUtilities.invokeLater(() -> {
                IdeEditorContext editor = getEditor(file, true);
                if (editor != null) editor.setCaretPosition(Math.max(0, line - 1), 0);
            });
        });
        originsPanelId = registerToolPanel(DockRegion.BOTTOM, "Origins", ToolIconType.INFO, originsPanel);
    }

    @Override
    public void onProjectClosed(IdeProjectContext context) {
        lifecycle.incrementAndGet();
        wordCaretTicket.incrementAndGet();
        SwingUtilities.invokeLater(this::hideCodeActionLamp);
        if (languageService != null) languageService.stopAsync();
        if (runSupport != null) runSupport.stopAll();
        if (debugService != null) debugService.stop();
        clearDebugLine();
        originsSnapshot = null;
        lspStarting.set(false);
        descriptor = null;
        projectRoot = null;
        projectContext = null;
        originsPanelId = null;
        openPhpFiles.clear();
        openPhpTexts.clear();
    }

    @Override
    public void clearCaches() {
        long ticket = lifecycle.incrementAndGet();
        if (languageService != null) languageService.stopAsync()
                .whenComplete((unused, error) -> startLanguageServer(ticket));
        rebuildOrigins(ticket);
        rebuildDefinitions();
    }

    @Override
    public EditorTheme getEditorTheme() {
        return theme;
    }

    @Override
    public TokenizerCodeEditorProvider resolveSyntaxHighlightTokenizer(Path filePath) {
        if (PhpProjectConventions.isPhp(filePath)) return tokenizer;
        String name = filename(filePath);
        if (name.equals(".env") || name.startsWith(".env.")) return envTokenizer;
        if (name.equals(".htaccess")) return htaccessTokenizer;
        if (name.equals("modules.config")) return modulesTokenizer;
        return null;
    }

    @Override
    public Collection<FoldRule> resolveFoldRules(Path filePath) {
        return PhpProjectConventions.isPhp(filePath)
                ? List.of(FoldRule.pair('{', '}'), FoldRule.pair('[', ']'),
                FoldRule.pair("/*", "*/"), FoldRule.htmlTags())
                : List.of();
    }

    @Override
    public String getLineCommentPrefix(Path filePath) {
        if (PhpProjectConventions.isPhp(filePath)) return "//";
        return isHighlightedConfig(filePath) ? "#" : null;
    }

    @Override
    public Collection<FileAssociated> getFileAssociations() {
        return List.of(
                new FileAssociated("json", NativeEditorType.JSON),
                new FileAssociated("lock", NativeEditorType.JSON)
        );
    }

    @Override
    public void configureEditor(IdeEditorContext context) {
        if (context != null && (PhpProjectConventions.isPhp(context.filePath())
                || isHighlightedConfig(context.filePath()))) {
            context.setAutoCompleteOnTyping(true);
            context.setFoldingEnabled(true);
            context.setSyntaxHighlightEnabled(true);
            context.setDiagnosticsAutoRunEnabled(PhpProjectConventions.isPhp(context.filePath()));
            context.setCodeLensesEnabled(PhpProjectConventions.isPhp(context.filePath()));
        }
    }

    @Override
    public void onEditorOpen(IdeEditorContext context) {
        if (context == null || !PhpProjectConventions.isPhp(context.filePath())) return;
        Path file = context.filePath().toAbsolutePath().normalize();
        openPhpFiles.add(file);
        openPhpTexts.put(file, context.getText());
        runSupport.setActiveFile(context.filePath());
        process().ifPresent(lsp -> lsp.sync(file, context.getText()));
        requestInitialEditorFeatures(file);
    }

    @Override
    public void onEditorSelected(IdeEditorContext context) {
        if (context != null && PhpProjectConventions.isPhp(context.filePath())) {
            runSupport.setActiveFile(context.filePath());
        }
    }

    @Override
    public void onCodeEditorTextChanged(IdeEditorContext context) {
        wordCaretTicket.incrementAndGet();
        SwingUtilities.invokeLater(this::hideCodeActionLamp);
        if (context != null && PhpProjectConventions.isPhp(context.filePath())) {
            Path file = context.filePath().toAbsolutePath().normalize();
            openPhpTexts.put(file, context.getText());
            process().ifPresent(lsp -> lsp.sync(file, context.getText()));
        }
    }

    @Override
    public void onEditorClose(Path filePath) {
        wordCaretTicket.incrementAndGet();
        SwingUtilities.invokeLater(this::hideCodeActionLamp);
        if (filePath != null) {
            Path file = filePath.toAbsolutePath().normalize();
            openPhpFiles.remove(file);
            openPhpTexts.remove(file);
        }
        process().ifPresent(lsp -> lsp.close(filePath));
    }

    @Override
    public boolean supportsIncrementalDiagnostics() {
        return false;
    }

    @Override
    public Collection<Diagnostic> getDiagnostics(IdeDiagnosticsContext context,
                                                 boolean incremental,
                                                 Collection<Diagnostic> previous) {
        if (context == null || !PhpProjectConventions.isPhp(context.getFilePath())) return List.of();
        List<Diagnostic> diagnostics = new ArrayList<>(process().map(lsp -> {
            lsp.sync(context.getFilePath(), context.getText());
            return lsp.diagnostics(context.getFilePath());
        }).orElse(List.of()));
        appendStrictDiagnostics(diagnostics, context.getText());
        if (definitionIndex == null) return diagnostics;

        for (PhpDefinitionIndex.MissingInterfaceImplementation missing
                : definitionIndex.missingInterfaceImplementations(
                context.getText(), context.getFilePath())) {
            if (hasMissingInterfaceDiagnostic(diagnostics, missing.range())) continue;
            String methods = missing.methodNames().stream()
                    .map(name -> name + "()")
                    .collect(java.util.stream.Collectors.joining(", "));
            String message = text("diagnostic.missingInterfaceMethods",
                    "Class {0} must implement the interface methods: {1}.")
                    .replace("{0}", missing.className())
                    .replace("{1}", methods);
            diagnostics.add(new Diagnostic(
                    missing.range().start().line(),
                    missing.range().start().col(),
                    missing.range().end().line(),
                    missing.range().end().col(),
                    DiagnosticSeverity.ERROR,
                    message));
        }
        return List.copyOf(diagnostics);
    }

    private void appendStrictDiagnostics(List<Diagnostic> diagnostics, String source) {
        for (PhpStrictDiagnostics.Issue issue : strictDiagnostics.analyze(source)) {
            if (hasDuplicateConstantDiagnostic(diagnostics, issue.range())) continue;
            String message = switch (issue.kind()) {
                case DUPLICATE_NAME -> text("diagnostic.duplicateConstantName",
                        "Constant {0} is already declared in this type.")
                        .replace("{0}", issue.constantName());
                case DUPLICATE_VALUE -> text("diagnostic.duplicateConstantValue",
                        "Constant {0} repeats the value declared by {1}.")
                        .replace("{0}", issue.constantName())
                        .replace("{1}", issue.previousName());
            };
            diagnostics.add(new Diagnostic(
                    issue.range().start().line(),
                    issue.range().start().col(),
                    issue.range().end().line(),
                    issue.range().end().col(),
                    DiagnosticSeverity.ERROR,
                    message));
        }
    }

    @Override
    public List<CodeAction> getCodeActions(IdeCodeActionContext context) {
        if (context == null || context.filePath() == null || definitionIndex == null
                || !PhpProjectConventions.isPhp(context.filePath())) {
            return List.of();
        }
        List<CodeAction> actions = new ArrayList<>();
        for (PhpDefinitionIndex.MissingInterfaceImplementation missing
                : definitionIndex.missingInterfaceImplementations(
                context.text(), context.filePath())) {
            if (!rangesIntersect(context.range(), missing.range())) continue;
            String title = text("action.implementInterface", "Implement interface");
            actions.add(CodeAction.quickFix(
                    title,
                    List.of(TextEdit.insert(
                            missing.insertion(), missing.implementation()))));
        }
        return List.copyOf(actions);
    }

    @Override
    public List<AutoCompleteItem> getCompletionSuggestions(IdeCompletionContext context) {
        if (context == null || !PhpProjectConventions.isPhp(context.filePath())) return List.of();
        if (!HtmlCompletionProvider.isPhpAt(context.text(), context.caretOffset())) {
            return htmlCompletion.suggestions(context.text(), context.caretOffset(),
                    context.triggerKind() != IdeCompletionTriggerKind.TYPING);
        }
        List<AutoCompleteItem> result = new ArrayList<>(originsCompletions(context.prefix()));
        process().ifPresent(lsp -> result.addAll(lsp.complete(
                context.filePath(), context.text(), context.caretLine(), context.caretCol(),
                context.prefix(), triggerOf(context)
        )));
        return result;
    }

    @Override
    public boolean shouldAutoTriggerCompletion(IdeCompletionContext context) {
        return context != null && PhpProjectConventions.isPhp(context.filePath());
    }

    @Override
    public boolean isAutoCompletionOnTypingEnabled() {
        return true;
    }

    @Override
    public Set<Character> getCompletionTriggerCharacters() {
        Set<Character> triggers = new java.util.HashSet<>(
                Set.of('$', '>', ':', '\\', '#', '<', '/', '"', '\'', '-', '@'));
        process().ifPresent(lsp -> triggers.addAll(lsp.completionTriggers()));
        return Set.copyOf(triggers);
    }

    @Override
    public HoverInfo getHover(IdeHoverContext context) {
        return context == null ? null : process().map(lsp -> lsp.hover(
                context.filePath(), context.text(), context.line(), context.col()
        )).orElse(null);
    }

    @Override
    public SignatureHelp provideSignatureHelp(IdeSignatureHelpContext context) {
        return context == null ? null : process().map(lsp -> lsp.signature(
                context.filePath(), context.text(), context.caretLine(), context.caretCol()
        )).orElse(null);
    }

    @Override
    public Set<Character> getSignatureTriggerCharacters() {
        return Set.of('(', ',');
    }

    @Override
    public Collection<String> getAdditionalSearchableExtensions() {
        return Set.of(".php", ".phtml", ".phpt", ".env", ".config");
    }

    @Override
    public List<Location> findDefinitions(IdeDefinitionContext context) {
        return definitions(context, false);
    }

    @Override
    public List<Location> findReferences(IdeDefinitionContext context) {
        return definitions(context, true);
    }

    private List<Location> definitions(IdeDefinitionContext context, boolean references) {
        if (context == null) return List.of();
        if (!references && definitionIndex != null) {
            List<Location> indexed = definitionIndex.preferredDefinitions(
                    context.text(), context.offset());
            if (!indexed.isEmpty()) return indexed;
        }
        List<Location> lspLocations = process().map(lsp -> lsp.definitions(
                context.filePath(), context.text(), context.line(), context.col(), references))
                .orElse(List.of());
        if (!lspLocations.isEmpty() || references) return lspLocations;
        if (!lspStarting.get()) startLanguageServer(lifecycle.get());
        return definitionIndex == null ? List.of()
                : definitionIndex.definitions(context.text(), context.offset());
    }

    @Override
    public boolean isGoToDeclarationEnabled() {
        return true;
    }

    @Override
    public boolean isGoToImplementationEnabled() {
        return true;
    }

    @Override
    public boolean isFindUsagesEnabled() {
        return true;
    }

    @Override
    public void onWordClick(IdeWordClickContext context) {
        if (context == null || !PhpProjectConventions.isPhp(context.filePath())) return;
        navigateAsync(new IdeDefinitionContext(
                context.text(), context.filePath(), context.line(), context.col(), context.startOffset()),
                context.editorContext(), false);
    }

    @Override
    public void onWordCaretChange(IdeWordCaretContext context) {
        long ticket = wordCaretTicket.incrementAndGet();
        SwingUtilities.invokeLater(this::hideCodeActionLamp);
        if (context == null || context.filePath() == null || context.editorContext() == null
                || definitionIndex == null
                || !PhpProjectConventions.isPhp(context.filePath())) {
            return;
        }
        codeActionDelayExecutor.schedule(
                () -> showCodeActionLampIfCaretStayed(context, ticket),
                600, TimeUnit.MILLISECONDS);
    }

    private void showCodeActionLampIfCaretStayed(
            IdeWordCaretContext context, long ticket) {
        if (ticket != wordCaretTicket.get()
                || !isSameCaretContext(context, context.editorContext())) {
            return;
        }
        Range caret = Range.point(context.line(), context.col());
        boolean hasAction = definitionIndex.missingInterfaceImplementations(
                        context.text(), context.filePath()).stream()
                .anyMatch(missing -> rangesIntersect(caret, missing.range()));
        if (!hasAction) return;
        SwingUtilities.invokeLater(() -> {
            if (ticket == wordCaretTicket.get()
                    && isSameCaretContext(context, context.editorContext())) {
                showCodeActionLamp(context);
            }
        });
    }

    private boolean isSameCaretContext(
            IdeWordCaretContext context, IdeEditorContext editorContext) {
        if (context == null || editorContext == null || editorContext.filePath() == null) {
            return false;
        }
        return Objects.equals(
                editorContext.filePath().toAbsolutePath().normalize(),
                context.filePath().toAbsolutePath().normalize())
                && editorContext.getCaretLine() == context.line()
                && editorContext.getCaretCol() == context.col()
                && Objects.equals(editorContext.getText(), context.text());
    }

    private void showCodeActionLamp(IdeWordCaretContext context) {
        Component editorComponent = resolveEditorComponent(context.editorContext());
        if (editorComponent == null || !editorComponent.isShowing()) return;
        JRootPane rootPane = SwingUtilities.getRootPane(editorComponent);
        if (rootPane == null) return;
        hideCodeActionLamp();

        CodeActionLamp lamp = new CodeActionLamp(loadCodeActionLampIcon());
        lamp.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                hideCodeActionLamp();
                requestShowCodeActions(context.filePath());
            }
        });

        Dimension size = lamp.getPreferredSize();
        Point location = codeActionLampLocation(editorComponent, context, size);
        JLayeredPane layeredPane = rootPane.getLayeredPane();
        SwingUtilities.convertPointFromScreen(location, layeredPane);
        lamp.setBounds(location.x, location.y, size.width, size.height);
        layeredPane.add(lamp, JLayeredPane.POPUP_LAYER);
        layeredPane.repaint(lamp.getBounds());
        codeActionLamp = lamp;
        codeActionLampLayer = layeredPane;
    }

    private Icon loadCodeActionLampIcon() {
        return ImageUtils.getIconByResource(
                        PhpIdeAdapter.class, "imgs/codeActionLampRed.svg")
                .map(icon -> ImageUtils.resizeIcon(icon, 18, 18))
                .orElseGet(() -> UIManager.getIcon("OptionPane.errorIcon"));
    }

    private Point codeActionLampLocation(
            Component editorComponent,
            IdeWordCaretContext context,
            Dimension lampSize) {
        Point screenLocation = editorComponent.getLocationOnScreen();
        int x = -lampSize.width + 2;
        int anchorY = caretLineCenterY(editorComponent, context);
        int y = Math.max(0, Math.min(
                anchorY - lampSize.height / 2,
                Math.max(0, editorComponent.getHeight() - lampSize.height)));
        return new Point(screenLocation.x + x, screenLocation.y + y);
    }

    private int caretLineCenterY(
            Component editorComponent, IdeWordCaretContext context) {
        if (editorComponent instanceof JTextComponent textComponent) {
            try {
                int offset = Math.max(0, Math.min(
                        context.startOffset(), textComponent.getDocument().getLength()));
                Rectangle2D rectangle = textComponent.modelToView2D(offset);
                if (rectangle != null) {
                    return (int) Math.round(
                            rectangle.getY() + rectangle.getHeight() / 2.0);
                }
            } catch (Exception e) {
                log.debug("Falha ao posicionar lâmpada de ações: {}", e.getMessage());
            }
        }
        return context.mouseY();
    }

    private void hideCodeActionLamp() {
        JComponent lamp = codeActionLamp;
        JLayeredPane layer = codeActionLampLayer;
        codeActionLamp = null;
        codeActionLampLayer = null;
        if (lamp != null && layer != null) {
            Rectangle bounds = lamp.getBounds();
            layer.remove(lamp);
            layer.repaint(bounds);
        }
    }

    private Component resolveEditorComponent(IdeEditorContext editorContext) {
        Object codeEditor = resolveField(editorContext, "codeEditor");
        Component textArea = invokeGetTextArea(codeEditor);
        if (textArea != null) return textArea;
        return codeEditor instanceof Component component ? component : null;
    }

    private Object resolveField(IdeEditorContext editorContext, String field) {
        if (editorContext == null) return null;
        try {
            Field declaredField = editorContext.getClass().getDeclaredField(field);
            declaredField.setAccessible(true);
            return declaredField.get(editorContext);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Component invokeGetTextArea(Object codeEditor) {
        if (codeEditor == null) return null;
        try {
            Method method = codeEditor.getClass().getMethod("getTextArea");
            Object textArea = method.invoke(codeEditor);
            return textArea instanceof Component component ? component : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public void onGoToDeclaration(IdeEditorContext context) {
        navigateFromEditor(context, false);
    }

    @Override
    public void onGoToImplementation(IdeEditorContext context) {
        navigateFromEditor(context, true);
    }

    @Override
    public void onFindUsages(IdeEditorContext context) {
        if (context == null || !PhpProjectConventions.isPhp(context.filePath())) return;
        IdeDefinitionContext definition = new IdeDefinitionContext(
                context.getText(), context.filePath(), context.getCaretLine(),
                context.getCaretCol(), context.getCaretOffset());
        Thread.ofVirtual().name("php-find-usages").start(() -> {
            List<Location> usages = definitionIndex == null ? List.of()
                    : definitionIndex.usages(definition.text(), definition.offset());
            if (usages.isEmpty()) usages = definitions(definition, true);
            if (usages.isEmpty()) {
                setStatusBarText(text("status.usagesNotFound", "PHP: no usages found"));
                return;
            }
            showUsageTargets(usages, definition.filePath(), definition.text(),
                    context, null, null,
                    PhpDefinitionIndex.wordAt(definition.text(), definition.offset()));
        });
    }

    @Override
    public void contributeEditorMenu(IdeMenuBuilder menu, IdeEditorContext editorContext) {
        if (menu == null || editorContext == null
                || !PhpProjectConventions.isPhp(editorContext.filePath())) return;
        menu.separator()
                .item(text("editor.goToDefinition", "Go to Definition"),
                        e -> onGoToDeclaration(editorContext))
                .item(text("editor.goToImplementation", "Go to Implementation"),
                        e -> onGoToImplementation(editorContext))
                .item(text("editor.findUsages", "Find Usages"),
                        e -> onFindUsages(editorContext));
    }

    private void navigateFromEditor(IdeEditorContext context, boolean implementation) {
        if (context == null || !PhpProjectConventions.isPhp(context.filePath())) return;
        navigateAsync(new IdeDefinitionContext(
                context.getText(), context.filePath(), context.getCaretLine(),
                context.getCaretCol(), context.getCaretOffset()), context, implementation);
    }

    private void navigateAsync(IdeDefinitionContext context,
                               IdeEditorContext sourceEditor,
                               boolean implementation) {
        Thread.ofVirtual().name("php-navigation").start(() -> {
            List<Location> locations;
            if (implementation) {
                locations = definitionIndex == null ? List.of()
                        : definitionIndex.implementations(
                                context.text(), context.offset(), context.filePath());
                if (locations.isEmpty()) {
                    locations = process().map(lsp -> lsp.implementations(
                            context.filePath(), context.text(), context.line(), context.col()))
                            .orElse(List.of());
                }
                if (locations.isEmpty()) locations = definitions(context, false);
            } else {
                locations = definitionIndex == null ? List.of()
                        : definitionIndex.preferredDefinitions(context.text(), context.offset());
                if (locations.isEmpty()) {
                    locations = process().map(lsp -> lsp.navigationDefinitions(
                            context.filePath(), context.text(), context.line(), context.col()))
                            .orElse(List.of());
                }
                if (locations.isEmpty() && definitionIndex != null) {
                    locations = definitionIndex.definitions(context.text(), context.offset());
                }
            }
            if (locations.isEmpty()) {
                setStatusBarText(text("status.definitionNotFound",
                        "PHP: definition not found"));
                return;
            }
            if (implementation) {
                showImplementationTargets(locations, context.filePath(), context.text(),
                        sourceEditor, null, null,
                        PhpDefinitionIndex.wordAt(context.text(), context.offset()));
            } else {
                openLocation(locations.getFirst(), context.filePath(), sourceEditor);
            }
        });
    }

    private void openLocation(Location location, Path sourceFile, IdeEditorContext sourceEditor) {
        if (location == null || location.range() == null || location.range().start() == null) return;
        int line = location.range().start().line();
        int col = location.range().start().col();
        Path target = location.isLocal() ? sourceFile : pathFromUri(location.uri());
        if (target == null || !Files.isRegularFile(target)) {
            log.debug("Definição PHP aponta para arquivo inválido: {}", location.uri());
            setStatusBarText(text("status.definitionFileNotFound",
                    "PHP: definition file not found"));
            return;
        }
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Path normalizedSource = sourceFile == null ? null : sourceFile.toAbsolutePath().normalize();
        SwingUtilities.invokeLater(() -> {
            if (normalizedTarget.equals(normalizedSource) && sourceEditor != null) {
                sourceEditor.setCaretPosition(line, col);
                return;
            }
            requestOpenFile(normalizedTarget);
            SwingUtilities.invokeLater(() -> setCaretPosition(line, col));
        });
    }

    private static Path pathFromUri(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null || uri.getScheme().isBlank()) {
                return Path.of(value).toAbsolutePath().normalize();
            }
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return Path.of(uri).toAbsolutePath().normalize();
            }
        } catch (Exception ignored) {
            try {
                return Path.of(value).toAbsolutePath().normalize();
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
        return null;
    }

    @Override
    public List<DocumentHighlight> getDocumentHighlights(IdeDocumentHighlightContext context) {
        if (context == null) return List.of();
        return process().map(lsp -> lsp.highlights(context.filePath(), context.text(),
                context.line(), context.col())).orElse(List.of());
    }

    @Override
    public List<CodeLens> getCodeLenses(IdeCodeLensContext context) {
        if (context == null || definitionIndex == null
                || !PhpProjectConventions.isPhp(context.filePath())) return List.of();
        Map<Integer, List<CodeLensItem>> itemsByLine = new LinkedHashMap<>();
        for (PhpDefinitionIndex.ImplementationAnchor anchor
                : definitionIndex.implementationAnchors(context.text(), context.filePath())) {
            List<Location> targets = definitionIndex.implementations(
                    context.text(), anchor.offset(), context.filePath());
            if (targets.isEmpty()) continue;
            int count = targets.size();
            String label = count == 1
                    ? text("lens.implementationOne", "1 implementation")
                    : text("lens.implementations", "{0} implementations")
                            .replace("{0}", String.valueOf(count));
            CodeLensItem item = CodeLensItem.builder()
                    .text(label)
                    .tooltip(text("lens.implementationTooltip",
                            "Go to implementation of {0}").replace("{0}", anchor.symbol()))
                    .cursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
                    .onClick(event -> {
                        MouseEvent mouse = event.mouseEvent();
                        Component invoker = mouse == null ? null : mouse.getComponent();
                        Point screen = mouse == null ? null : mouse.getLocationOnScreen();
                        showImplementationTargets(targets, context.filePath(), context.text(),
                                null, invoker, screen, anchor.symbol());
                    })
                    .build();
            itemsByLine.computeIfAbsent(anchor.line(), ignored -> new ArrayList<>()).add(item);
        }
        for (PhpDefinitionIndex.ImplementationAnchor anchor
                : definitionIndex.usageAnchors(context.text(), context.filePath())) {
            List<Location> usages = definitionIndex.usages(context.text(), anchor.offset());
            if (usages.isEmpty()) continue;
            int count = usages.size();
            String label = count == 1
                    ? text("lens.usageOne", "1 usage")
                    : text("lens.usages", "{0} usages")
                            .replace("{0}", String.valueOf(count));
            CodeLensItem item = CodeLensItem.builder()
                    .text(label)
                    .tooltip(text("lens.usagesTooltip",
                            "Show usages of {0}").replace("{0}", anchor.symbol()))
                    .cursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
                    .onClick(event -> {
                        MouseEvent mouse = event.mouseEvent();
                        Component invoker = mouse == null ? null : mouse.getComponent();
                        Point screen = mouse == null ? null : mouse.getLocationOnScreen();
                        showUsageTargets(usages, context.filePath(), context.text(),
                                null, invoker, screen, anchor.symbol());
                    })
                    .build();
            itemsByLine.computeIfAbsent(anchor.line(), ignored -> new ArrayList<>()).add(item);
        }
        return itemsByLine.entrySet().stream()
                .map(entry -> CodeLens.inline(entry.getKey(),
                        entry.getValue().toArray(CodeLensItem[]::new)))
                .toList();
    }

    private void showImplementationTargets(List<Location> targets,
                                           Path sourceFile,
                                           String sourceText,
                                           IdeEditorContext sourceEditor,
                                           Component invoker,
                                           Point screen,
                                           String symbol) {
        if (targets == null || targets.isEmpty()) return;
        if (targets.size() == 1) {
            openLocation(targets.getFirst(), sourceFile, sourceEditor);
            return;
        }
        List<PhpNavigationPopup.Item> items =
                buildNavigationItems(targets, sourceFile, sourceText, sourceEditor);
        if (items.isEmpty()) return;
        String header = text("popup.implementations", "{0} implementations of {1}")
                .replace("{0}", String.valueOf(items.size()))
                .replace("{1}", symbol == null ? "" : symbol);
        Window owner = invoker == null ? null : SwingUtilities.getWindowAncestor(invoker);
        PhpNavigationPopup.show(owner, screen, header, items);
    }

    private void showUsageTargets(List<Location> usages,
                                  Path sourceFile,
                                  String sourceText,
                                  IdeEditorContext sourceEditor,
                                  Component invoker,
                                  Point screen,
                                  String symbol) {
        List<PhpNavigationPopup.Item> items =
                buildNavigationItems(usages, sourceFile, sourceText, sourceEditor);
        if (items.isEmpty()) return;
        String header = (items.size() == 1
                ? text("popup.usageOne", "1 usage of {0}")
                : text("popup.usages", "{0} usages of {1}"))
                .replace("{0}", items.size() == 1
                        ? (symbol == null ? "" : symbol)
                        : String.valueOf(items.size()))
                .replace("{1}", symbol == null ? "" : symbol);
        Window owner = invoker == null ? null : SwingUtilities.getWindowAncestor(invoker);
        PhpNavigationPopup.show(owner, screen, header, items);
    }

    private List<PhpNavigationPopup.Item> buildNavigationItems(
            List<Location> locations,
            Path sourceFile,
            String sourceText,
            IdeEditorContext sourceEditor) {
        List<PhpNavigationPopup.Item> items = new ArrayList<>();
        Map<Path, List<String>> cache = new java.util.HashMap<>();
        Path root = projectRoot;
        for (Location location : locations) {
            if (location == null || location.range() == null
                    || location.range().start() == null) continue;
            Path path = location.isLocal() ? sourceFile : pathFromUri(location.uri());
            if (path == null || !Files.isRegularFile(path)) continue;
            Path normalized = path.toAbsolutePath().normalize();
            int line = location.range().start().line();
            String snippet = sourceLine(normalized, sourceFile, sourceText, line, cache);
            Path shown = root != null && normalized.startsWith(root)
                    ? root.relativize(normalized)
                    : normalized.getFileName();
            String display = (shown == null ? normalized : shown).toString() + ":" + (line + 1);
            items.add(new PhpNavigationPopup.Item(snippet, display,
                    () -> openLocation(location, sourceFile, sourceEditor)));
        }
        return List.copyOf(items);
    }

    private static String sourceLine(Path file, Path currentFile, String currentText,
                                     int line, Map<Path, List<String>> cache) {
        if (line < 0) return "";
        List<String> lines;
        if (currentText != null && currentFile != null
                && file.equals(currentFile.toAbsolutePath().normalize())) {
            lines = currentText.lines().toList();
        } else {
            lines = cache.computeIfAbsent(file, key -> {
                try {
                    return Files.readAllLines(key);
                } catch (Exception ignored) {
                    return List.of();
                }
            });
        }
        return line < lines.size() ? lines.get(line).strip() : "";
    }

    @Override
    public String formatCode(FormatCodeContext context) {
        if (context == null || !PhpProjectConventions.isPhp(context.file())) return null;
        String source = context.formatScope() == IdeFormatScope.SELECTION
                ? context.text() : context.fullText();
        return process().map(lsp -> lsp.format(context.file(), source,
                context.tabSize(), context.useSpacesForTab())).orElse(source);
    }

    @Override
    public Collection<RunConfigurationData> getStaticRunConfigurations() {
        return projectRoot == null ? List.of() : runSupport.configurations(projectRoot);
    }

    @Override
    public void onRunConfigurationChanged(RunConfigurationData data) {
        if (data == null || !isPhpRunConfiguration(data)) return;
        boolean debuggable = isPhpDebugConfiguration(data);
        // Se o XAMPP já estiver rodando (iniciado por nós), ao selecionar a config o botão deve
        // aparecer no estado "executando" para permitir parar.
        boolean xamppRunning = PhpRunSupport.TYPE_XAMPP.equals(data.getType())
                && runSupport != null && runSupport.isXamppServerRunning();
        SwingUtilities.invokeLater(() -> {
            requestSetRunButtonEnabled(true);
            requestSetDebugButtonEnabled(debuggable);
            requestSetRunButtonRunning(xamppRunning);
        });
    }

    private static boolean isPhpRunConfiguration(RunConfigurationData data) {
        String type = data.getType();
        return PhpRunSupport.TYPE_XAMPP.equals(type)
                || PhpRunSupport.TYPE_BUILTIN.equals(type)
                || PhpRunSupport.TYPE_CLI.equals(type)
                || PhpRunSupport.TYPE_XDEBUG_LISTEN.equals(type)
                || PhpRunSupport.TYPE_XDEBUG_CLI.equals(type)
                || PhpRunSupport.TYPE_XDEBUG_BUILTIN.equals(type);
    }

    private static boolean isPhpDebugConfiguration(RunConfigurationData data) {
        // O XAMPP inicia um servidor externo: a depuração via Xdebug não se aplica a esse modo.
        return isPhpRunConfiguration(data) && !PhpRunSupport.TYPE_XAMPP.equals(data.getType());
    }

    @Override
    public RunProcessHandle launch(RunConfigurationData data, RunExecutionContext context) throws Exception {
        RunProcessHandle handle = runSupport.launch(data, projectRoot, toolchain, false);
        openBrowserFor(data);
        if (data != null && PhpRunSupport.TYPE_XAMPP.equals(data.getType())) {
            SwingUtilities.invokeLater(() -> requestSetRunButtonRunning(true));
        }
        return handle;
    }

    @Override
    public RunProcessHandle launchDebug(RunConfigurationData data, RunExecutionContext context) throws Exception {
        Path php = toolchain.findPhp(projectRoot);
        XdebugStatus status = xdebugInstaller.inspect(php).get(30, java.util.concurrent.TimeUnit.SECONDS);
        if (!status.loaded()) {
            throw new IllegalStateException(status.summary()
                    + " Use PHP > Diagnosticar/instalar Xdebug antes de depurar.");
        }
        List<String> adapter = debugAdapterInstaller
                .ensure(getApplicationContext().getResourcesPath(), toolchain)
                .get(2, java.util.concurrent.TimeUnit.MINUTES);
        int port = debugPort(data);
        RunProcessHandle handle = debugService.launch(adapter, data, context, projectRoot,
                runSupport, toolchain, port, new PhpDapSession.Listener() {
                    @Override
                    public void onStopped(Path file, int oneBasedLine) {
                        showDebugLine(file, oneBasedLine);
                    }

                    @Override
                    public void onContinued() {
                        clearDebugLine();
                    }

                    @Override
                    public void onTerminated() {
                        clearDebugLine();
                    }
                });
        openBrowserFor(data, true);
        return handle;
    }

    @Override
    public void stop(RunConfigurationData data) {
        if (debugService != null) debugService.stop();
        clearDebugLine();
        try {
            runSupport.stop(data);
        } catch (Exception e) {
            log.warn("Falha ao parar configuração PHP", e);
        }
        if (data != null && PhpRunSupport.TYPE_XAMPP.equals(data.getType())) {
            SwingUtilities.invokeLater(() -> requestSetRunButtonRunning(false));
        }
    }

    @Override
    public boolean isBreakPointEnabled(Path fileOpen) {
        return PhpProjectConventions.isPhp(fileOpen);
    }

    @Override
    public boolean isConditionalBreakpointEnabled(Path fileOpen) {
        return PhpProjectConventions.isPhp(fileOpen);
    }

    @Override
    public void onBreakpointChanged(BreakpointChangedEvent event) {
        if (debugService != null) debugService.breakpointChanged(event);
    }

    private void openBrowserFor(RunConfigurationData data) {
        openBrowserFor(data, false);
    }

    private void openBrowserFor(RunConfigurationData data, boolean debug) {
        if (data == null) return;
        if (!settings.openBrowser()) return;
        String trigger = debug ? "XDEBUG_TRIGGER=ORION" : "";
        if (PhpRunSupport.TYPE_XAMPP.equals(data.getType())) {
            String url = String.valueOf(data.getProperties().getOrDefault("url", "http://localhost/"));
            openWebBrowser(withQuery(url, trigger));
        } else if (PhpRunSupport.TYPE_BUILTIN.equals(data.getType())
                || PhpRunSupport.TYPE_XDEBUG_BUILTIN.equals(data.getType())) {
            String host = String.valueOf(data.getProperties().getOrDefault("host", "localhost"));
            String port = String.valueOf(data.getProperties().getOrDefault("port", 8080));
            openWebBrowser(withQuery("http://" + host + ":" + port + "/", trigger));
        }
    }

    @Override
    public List<ProjectTreeIgnoreRule> resolveProjectTreeIgnoredFolders(Path projectPath) {
        return List.of(
                ProjectTreeIgnoreRule.any("vendor"),
                ProjectTreeIgnoreRule.any("runtime"),
                ProjectTreeIgnoreRule.any("log"),
                ProjectTreeIgnoreRule.any("node_modules")
        );
    }

    @Override
    public void contributeMenuBar(IdeMenuBarBuilder menu) {
        menu.submenu("phpMenu", "PHP", php -> php
                .item("phpOriginsExplorer", "Origins: módulos e endpoints",
                        event -> {
                            setupOriginsPanel();
                            if (originsPanelId != null) requestOpenToolPanel(originsPanelId);
                        })
                .item("phpRestartLsp", "Reiniciar Intelephense", event -> clearCaches())
                .separator()
                .item("phpXdebugInstall", "Diagnosticar/instalar Xdebug", event -> diagnoseXdebug())
                .item("phpDebugContinue", "Debug: continuar", event -> debugCommand("continue"))
                .item("phpDebugNext", "Debug: avançar linha", event -> debugCommand("next"))
                .item("phpDebugStepIn", "Debug: entrar", event -> debugCommand("stepIn"))
                .item("phpDebugStepOut", "Debug: sair", event -> debugCommand("stepOut"))
                .separator()
                .item("phpComposerInstall", "Composer install", event -> runComposer("install"))
                .item("phpComposerUpdate", "Composer update", event -> runComposer("update"))
                .item("phpComposerDump", "Composer dump-autoload", event -> runComposer("dump-autoload"))
                .item("phpComposerValidate", "Composer validate", event -> runComposer("validate")));
    }

    @Override
    public List<PluginSettingsPage> getSettingsPages() {
        return List.of(settingsPage);
    }

    private void diagnoseXdebug() {
        ensureServices();
        Path root = projectRoot;
        Path php;
        try {
            php = toolchain.findPhp(root);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, rootMessage(e), "PHP / Xdebug", JOptionPane.ERROR_MESSAGE);
            return;
        }
        showProgress("phpXdebug", "Verificando Xdebug");
        xdebugInstaller.inspect(php).whenComplete((status, error) -> SwingUtilities.invokeLater(() -> {
            hideProgress("phpXdebug");
            if (error != null) {
                JOptionPane.showMessageDialog(null, rootMessage(error),
                        "PHP / Xdebug", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (status.loaded()) {
                JOptionPane.showMessageDialog(null, status.summary(),
                        "PHP / Xdebug", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            int answer = JOptionPane.showConfirmDialog(null,
                    status.summary() + "\n\nA Orion pode baixar a DLL oficial, validar a compatibilidade, "
                            + "fazer backup do php.ini e configurá-la automaticamente.\nDeseja continuar?",
                    "Instalar Xdebug", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer == JOptionPane.YES_OPTION) installXdebug(php);
        }));
    }

    private void installXdebug(Path php) {
        showProgress("phpXdebug", "Instalando Xdebug");
        xdebugInstaller.install(php, getApplicationContext().getResourcesPath(), settings.xdebugPort())
                .whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
                    hideProgress("phpXdebug");
                    if (error != null) {
                        JOptionPane.showMessageDialog(null, rootMessage(error),
                                "Instalação do Xdebug", JOptionPane.ERROR_MESSAGE);
                    } else {
                        String backup = result.backupIni() == null ? ""
                                : "\nBackup: " + result.backupIni();
                        JOptionPane.showMessageDialog(null, result.message() + backup,
                                "Instalação do Xdebug", JOptionPane.INFORMATION_MESSAGE);
                    }
                }));
    }

    private void debugCommand(String command) {
        if (debugService == null || !debugService.active()) {
            setStatusBarText("PHP: nenhuma sessão Xdebug ativa");
            return;
        }
        if (!"pause".equals(command)) clearDebugLine();
        debugService.command(command);
    }

    private void showDebugLine(Path file, int oneBasedLine) {
        if (file == null) return;
        SwingUtilities.invokeLater(() -> {
            clearDebugLine();
            requestOpenFile(file);
            IdeEditorContext editor = getEditor(file, true);
            if (editor == null) return;
            int line = Math.max(0, oneBasedLine - 1);
            editor.setLineColor(line, new Color(70, 92, 50));
            editor.setCaretPosition(line, 0);
            debugEditor = editor;
            debugLine = line;
        });
    }

    private void clearDebugLine() {
        IdeEditorContext editor = debugEditor;
        int line = debugLine;
        debugEditor = null;
        debugLine = -1;
        if (editor != null && line >= 0) {
            SwingUtilities.invokeLater(() -> editor.removeLineColor(line));
        }
    }

    private int debugPort(RunConfigurationData data) {
        if (data == null || data.getProperties() == null) return settings.xdebugPort();
        String key;
        if (PhpRunSupport.TYPE_XDEBUG_BUILTIN.equals(data.getType())) key = "xdebugPort";
        else if (PhpRunSupport.TYPE_XDEBUG_LISTEN.equals(data.getType())
                || PhpRunSupport.TYPE_XDEBUG_CLI.equals(data.getType())) key = "port";
        else return settings.xdebugPort();
        Object value = data.getProperties().get(key);
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? settings.xdebugPort() : Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return settings.xdebugPort();
        }
    }

    private void persistXamppRoot(String root) {
        settings.xamppRoot(root);
        setProperty("orion.xampp.root", root);
    }

    private void applySettings() {
        setProperty("orion.php.executable", settings.phpExecutable());
        setProperty("orion.composer.executable", settings.composerExecutable());
        setProperty("orion.xampp.root", settings.xamppRoot());
        System.setProperty("orion.php.xdebugPort", Integer.toString(settings.xdebugPort()));
        System.setProperty("orion.php.serverPort", Integer.toString(settings.serverPort()));
    }

    private static void setProperty(String key, String value) {
        if (value == null || value.isBlank()) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static String withQuery(String url, String query) {
        if (query == null || query.isBlank()) return url;
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    private static boolean isHighlightedConfig(Path path) {
        String name = filename(path);
        return name.equals(".env") || name.startsWith(".env.")
                || name.equals(".htaccess") || name.equals("modules.config");
    }

    private static String filename(Path path) {
        return path == null || path.getFileName() == null ? ""
                : path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    }

    private void runComposer(String... args) {
        Path root = composerRoot();
        if (root == null) {
            setStatusBarText("PHP: composer.json não encontrado");
            return;
        }
        OutputPanelHandle panel = requestOutputPanel("Composer");
        if (panel == null) return;
        panel.show();
        OutputStream output = panel.getOutputStream();
        composerService.execute(toolchain.findComposer(), root, output, args)
                .whenComplete((exit, error) -> {
                    if (error != null) setStatusBarText("Composer falhou: " + rootMessage(error));
                    else setStatusBarText("Composer finalizado: código " + exit);
                });
    }

    private Path composerRoot() {
        if (projectRoot == null) return null;
        if (java.nio.file.Files.isRegularFile(projectRoot.resolve("composer.json"))) return projectRoot;
        return descriptor == null || descriptor.composerRoots().isEmpty()
                ? null : descriptor.composerRoots().getFirst();
    }

    private java.util.Optional<PhpLanguageServerProcess> process() {
        return languageService == null ? java.util.Optional.empty()
                : java.util.Optional.of(languageService.process()).filter(PhpLanguageServerProcess::isRunning);
    }

    private void onDiagnosticsPublished(Path file) {
        if (file != null) requestRefreshDiagnostics(file);
    }

    private void refreshOpenPhpFiles() {
        process().ifPresent(lsp -> openPhpTexts.forEach((file, source) -> {
            lsp.sync(file, source);
            requestInitialEditorFeatures(file);
        }));
    }

    private void requestInitialEditorFeatures(Path file) {
        requestRefreshDiagnostics(file);
        requestRefreshCodeLenses(file);
    }

    private static boolean hasMissingInterfaceDiagnostic(
            List<Diagnostic> diagnostics, Range classRange) {
        if (classRange == null) return false;
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic == null || diagnostic.severity() != DiagnosticSeverity.ERROR) continue;
            Range diagnosticRange = Range.of(
                    diagnostic.startLine(), diagnostic.startCol(),
                    diagnostic.endLine(), diagnostic.endCol());
            if (!rangesIntersect(diagnosticRange, classRange)) continue;
            String message = diagnostic.message() == null ? ""
                    : diagnostic.message().toLowerCase(java.util.Locale.ROOT);
            if (message.contains("abstract method")
                    || (message.contains("implement") && message.contains("method"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDuplicateConstantDiagnostic(
            List<Diagnostic> diagnostics, Range constantRange) {
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic == null || !rangesIntersect(constantRange, Range.of(
                    diagnostic.startLine(), diagnostic.startCol(),
                    diagnostic.endLine(), diagnostic.endCol()))) {
                continue;
            }
            String message = diagnostic.message() == null ? ""
                    : diagnostic.message().toLowerCase(java.util.Locale.ROOT);
            if (message.contains("duplicate") || message.contains("redeclare")
                    || message.contains("already declared") || message.contains("redefin")) {
                return true;
            }
        }
        return false;
    }

    private static boolean rangesIntersect(Range first, Range second) {
        if (first == null || second == null
                || first.start() == null || first.end() == null
                || second.start() == null || second.end() == null) {
            return false;
        }
        long firstStart = positionKey(first.start().line(), first.start().col());
        long firstEnd = positionKey(first.end().line(), first.end().col());
        long secondStart = positionKey(second.start().line(), second.start().col());
        long secondEnd = positionKey(second.end().line(), second.end().col());
        return firstStart <= secondEnd && secondStart <= firstEnd;
    }

    private static long positionKey(int line, int col) {
        return ((long) Math.max(0, line) << 32) | (Math.max(0, col) & 0xffffffffL);
    }

    private static Character triggerOf(IdeCompletionContext context) {
        if (context.triggerKind() == null || context.caretOffset() <= 0 || context.text() == null) return null;
        int index = Math.min(context.caretOffset(), context.text().length()) - 1;
        return index < 0 ? null : context.text().charAt(index);
    }

    private static List<AutoCompleteItem> originsCompletions(String prefix) {
        String value = prefix == null ? "" : prefix.toLowerCase();
        List<AutoCompleteItem> items = new ArrayList<>();
        addSnippet(items, value, "Controller", "#[Controller(\"${1:/path}\")]", "Origins controller");
        addSnippet(items, value, "Get", "#[Get(\"${1:/path}\")]", "Origins GET endpoint");
        addSnippet(items, value, "Post", "#[Post(\"${1:/path}\")]", "Origins POST endpoint");
        addSnippet(items, value, "Dependency", "#[Dependency]", "Origins dependency");
        addSnippet(items, value, "Inject", "#[Inject]", "Origins injection");
        addSnippet(items, value, "RequestBody", "#[RequestBody]", "Origins request body");
        addSnippet(items, value, "Valid", "#[Valid(${1:Request}::class)]", "ValidationIO validation");
        addSnippet(items, value, "AutoRender", "#[AutoRender(\"${1:Title}\")]", "TemplateViewer rendering");
        return items;
    }

    private static void addSnippet(List<AutoCompleteItem> items, String prefix,
                                   String label, String insert, String detail) {
        if (prefix.isBlank() || label.toLowerCase().startsWith(prefix.replace("#", ""))) {
            items.add(new AutoCompleteItem(insert, label, detail, "", null,
                    AutoCompleteItem.Kind.SNIPPET, List.of()));
        }
    }

    private static final class CodeActionLamp extends JComponent {
        private static final Color HOVER_BACKGROUND = new Color(128, 128, 128, 60);
        private static final Color HOVER_BORDER = new Color(128, 128, 128, 120);

        private final Icon icon;
        private boolean hovered;

        CodeActionLamp(Icon icon) {
            this.icon = icon;
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(text(
                    "tooltip.codeActions", "Mostrar ações de código (Alt+Enter)"));
            int width = (icon == null ? 16 : icon.getIconWidth()) + 8;
            int height = (icon == null ? 16 : icon.getIconHeight()) + 4;
            Dimension size = new Dimension(width, height);
            setPreferredSize(size);
            setSize(size);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent event) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    hovered = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                if (hovered) {
                    copy.setColor(HOVER_BACKGROUND);
                    copy.fillRoundRect(
                            0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                    copy.setColor(HOVER_BORDER);
                    copy.drawRoundRect(
                            0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                }
                if (icon != null) {
                    int x = (getWidth() - icon.getIconWidth()) / 2;
                    int y = (getHeight() - icon.getIconHeight()) / 2;
                    icon.paintIcon(this, copy, x, y);
                }
            } finally {
                copy.dispose();
            }
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
