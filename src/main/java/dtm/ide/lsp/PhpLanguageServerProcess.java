package dtm.ide.lsp;

import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
import dtm.stools.component.panels.editor.code.signature.ParameterInformation;
import dtm.stools.component.panels.editor.code.signature.SignatureHelp;
import dtm.stools.component.panels.editor.code.signature.SignatureInformation;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public final class PhpLanguageServerProcess {
    private static final long REQUEST_TIMEOUT_SECONDS = 4;
    private static final long NAVIGATION_TIMEOUT_MILLIS = 900;
    private final Object lock = new Object();
    private final Consumer<Path> diagnosticsListener;
    private final Map<String, Integer> versions = new ConcurrentHashMap<>();
    private final Map<String, String> syncedText = new ConcurrentHashMap<>();
    private final Map<String, List<JsonNode>> rawDiagnostics = new ConcurrentHashMap<>();
    private volatile Process process;
    private volatile PhpLspJsonRpcClient rpc;
    private volatile Set<Character> completionTriggers = Set.of('$', '>', ':', '\\');
    private volatile boolean running;
    private volatile String lastError;

    public PhpLanguageServerProcess(Consumer<Path> diagnosticsListener) {
        this.diagnosticsListener = diagnosticsListener;
    }

    public void start(List<String> command, Path workingDirectory, Path root, Path storage) {
        synchronized (lock) {
            if (isRunning()) return;
            try {
                ProcessBuilder builder = new ProcessBuilder(command);
                if (workingDirectory != null) builder.directory(workingDirectory.toFile());
                process = builder.start();
                drainStderr(process);
                rpc = new PhpLspJsonRpcClient(process.getInputStream(), process.getOutputStream());
                rpc.onNotification("textDocument/publishDiagnostics", this::publishDiagnostics);
                rpc.onRequest("workspace/configuration", ignored -> List.of(Map.of()));
                initialize(root, storage);
                running = true;
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("Falha ao iniciar Intelephense: {}", e.getMessage());
                stopInternal();
            }
        }
    }

    private void initialize(Path root, Path storage) throws Exception {
        String rootUri = root.toUri().toString();
        Map<String, Object> capabilities = Map.of(
                "textDocument", Map.of(
                        "synchronization", Map.of("didSave", true),
                        "completion", Map.of("completionItem", Map.of(
                                "snippetSupport", true,
                                "documentationFormat", List.of("markdown", "plaintext"),
                                "resolveSupport", Map.of("properties", List.of("documentation", "detail", "additionalTextEdits"))
                        )),
                        "hover", Map.of("contentFormat", List.of("markdown", "plaintext")),
                        "signatureHelp", Map.of(),
                        "definition", Map.of(),
                        "references", Map.of(),
                        "documentHighlight", Map.of(),
                        "formatting", Map.of()
                ),
                "workspace", Map.of("configuration", true)
        );
        Map<String, Object> init = new HashMap<>();
        init.put("processId", (int) ProcessHandle.current().pid());
        init.put("rootUri", rootUri);
        init.put("rootPath", root.toString());
        init.put("capabilities", capabilities);
        init.put("initializationOptions", Map.of(
                "storagePath", storage.toString(),
                "globalStoragePath", storage.getParent().toString()
        ));
        JsonNode result = rpc.request("initialize", init).get(30, TimeUnit.SECONDS);
        JsonNode triggers = result.path("capabilities").path("completionProvider").path("triggerCharacters");
        if (triggers.isArray()) {
            Set<Character> parsed = new LinkedHashSet<>();
            for (JsonNode trigger : triggers) {
                String text = trigger.asString("");
                if (!text.isEmpty()) parsed.add(text.charAt(0));
            }
            if (!parsed.isEmpty()) completionTriggers = Set.copyOf(parsed);
        }
        rpc.notify("initialized", Map.of());
        rpc.notify("workspace/didChangeConfiguration", Map.of("settings", Map.of(
                "intelephense", Map.of(
                        "environment", Map.of("phpVersion", "8.2.0"),
                        "files", Map.of("exclude", List.of("**/.git/**", "**/runtime/**", "**/log/**"))
                )
        )));
    }

    public boolean isRunning() {
        Process current = process;
        return running && current != null && current.isAlive();
    }

    public String lastError() {
        return lastError;
    }

    public Set<Character> completionTriggers() {
        return completionTriggers;
    }

    public void sync(Path file, String text) {
        PhpLspJsonRpcClient client = rpc;
        if (client == null || !isRunning() || file == null) return;
        String uri = uri(file);
        String safeText = text == null ? "" : text;
        if (safeText.equals(syncedText.put(uri, safeText))) return;
        Integer version = versions.get(uri);
        if (version == null) {
            versions.put(uri, 1);
            client.notify("textDocument/didOpen", Map.of("textDocument", Map.of(
                    "uri", uri, "languageId", "php", "version", 1, "text", safeText
            )));
        } else {
            int next = version + 1;
            versions.put(uri, next);
            client.notify("textDocument/didChange", Map.of(
                    "textDocument", Map.of("uri", uri, "version", next),
                    "contentChanges", List.of(Map.of("text", safeText))
            ));
        }
    }

    public void save(Path file, String text) {
        sync(file, text);
        if (rpc != null && isRunning()) {
            rpc.notify("textDocument/didSave", Map.of("textDocument", Map.of("uri", uri(file)), "text", text));
        }
    }

    public void close(Path file) {
        if (file == null) return;
        String uri = uri(file);
        versions.remove(uri);
        syncedText.remove(uri);
        rawDiagnostics.remove(uri);
        if (rpc != null && isRunning()) {
            rpc.notify("textDocument/didClose", Map.of("textDocument", Map.of("uri", uri)));
        }
    }

    public List<AutoCompleteItem> complete(Path file, String text, int line, int character,
                                           String prefix, Character trigger) {
        JsonNode result = positionalRequest("textDocument/completion", file, text, line, character,
                Map.of("context", trigger == null ? Map.of("triggerKind", 1)
                        : Map.of("triggerKind", 2, "triggerCharacter", String.valueOf(trigger))));
        JsonNode items = result == null ? null : (result.isArray() ? result : result.get("items"));
        if (items == null || !items.isArray()) return List.of();
        List<AutoCompleteItem> output = new ArrayList<>();
        for (JsonNode item : items) {
            String label = item.path("label").asString("");
            if (label.isBlank()) continue;
            String insert = firstNonBlank(item.path("textEdit").path("newText").asString(""),
                    item.path("insertText").asString(""), label);
            String detail = item.path("detail").asString("");
            String documentation = markup(item.get("documentation"));
            boolean snippet = item.path("insertTextFormat").asInt(1) == 2;
            output.add(new AutoCompleteItem(insert, label, detail, documentation, null,
                    snippet ? AutoCompleteItem.Kind.SNIPPET : completionKind(item.path("kind").asInt(1)),
                    parseEdits(item.get("additionalTextEdits"))));
            if (output.size() >= 250) break;
        }
        return output;
    }

    public HoverInfo hover(Path file, String text, int line, int character) {
        JsonNode result = positionalRequest("textDocument/hover", file, text, line, character, Map.of());
        if (result == null || result.isNull()) return null;
        String content = markup(result.get("contents"));
        return content.isBlank() ? null : HoverInfo.markdown(content);
    }

    public SignatureHelp signature(Path file, String text, int line, int character) {
        JsonNode result = positionalRequest("textDocument/signatureHelp", file, text, line, character, Map.of());
        if (result == null || !result.path("signatures").isArray()) return null;
        List<SignatureInformation> signatures = new ArrayList<>();
        for (JsonNode signature : result.path("signatures")) {
            List<ParameterInformation> parameters = new ArrayList<>();
            for (JsonNode parameter : signature.path("parameters")) {
                parameters.add(new ParameterInformation(parameter.path("label").asString(""),
                        markup(parameter.get("documentation"))));
            }
            signatures.add(new SignatureInformation(signature.path("label").asString(""),
                    markup(signature.get("documentation")), parameters));
        }
        return signatures.isEmpty() ? null : new SignatureHelp(signatures,
                result.path("activeSignature").asInt(0), result.path("activeParameter").asInt(0));
    }

    public List<Location> definitions(Path file, String text, int line, int character, boolean references) {
        return definitions(file, text, line, character, references, NAVIGATION_TIMEOUT_MILLIS);
    }

    public List<Location> navigationDefinitions(Path file, String text, int line, int character) {
        return definitions(file, text, line, character, false,
                TimeUnit.SECONDS.toMillis(REQUEST_TIMEOUT_SECONDS));
    }

    private List<Location> definitions(Path file, String text, int line, int character,
                                       boolean references, long timeoutMillis) {
        JsonNode result = positionalRequest(references ? "textDocument/references" : "textDocument/definition",
                file, text, line, character,
                references ? Map.of("context", Map.of("includeDeclaration", true)) : Map.of(),
                timeoutMillis);
        return locations(result);
    }

    public List<Location> implementations(Path file, String text, int line, int character) {
        JsonNode result = positionalRequest("textDocument/implementation",
                file, text, line, character, Map.of(),
                TimeUnit.SECONDS.toMillis(REQUEST_TIMEOUT_SECONDS));
        return locations(result);
    }

    private static List<Location> locations(JsonNode result) {
        if (result == null || result.isNull()) return List.of();
        List<Location> locations = new ArrayList<>();
        if (result.isArray()) result.forEach(node -> addLocation(locations, node));
        else addLocation(locations, result);
        return locations;
    }

    public List<dtm.ide.api.project.editor.DocumentHighlight> highlights(
            Path file, String text, int line, int character) {
        JsonNode result = positionalRequest("textDocument/documentHighlight", file, text, line, character, Map.of());
        if (result == null || !result.isArray()) return List.of();
        List<dtm.ide.api.project.editor.DocumentHighlight> output = new ArrayList<>();
        for (JsonNode node : result) {
            Range range = parseRange(node.get("range"));
            if (range != null) output.add(new dtm.ide.api.project.editor.DocumentHighlight(
                    range, dtm.ide.api.project.editor.DocumentHighlight.Kind.TEXT));
        }
        return output;
    }

    public String format(Path file, String text, int tabSize, boolean spaces) {
        sync(file, text);
        if (rpc == null || !isRunning()) return null;
        try {
            JsonNode result = rpc.request("textDocument/formatting", Map.of(
                    "textDocument", Map.of("uri", uri(file)),
                    "options", Map.of("tabSize", Math.max(1, tabSize), "insertSpaces", spaces)
            )).get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return applyEdits(text, result);
        } catch (Exception e) {
            log.debug("Formatação PHP falhou: {}", e.getMessage());
            return null;
        }
    }

    public List<Diagnostic> diagnostics(Path file) {
        List<JsonNode> nodes = rawDiagnostics.get(uri(file));
        if (nodes == null) return List.of();
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (JsonNode node : nodes) {
            Range range = parseRange(node.get("range"));
            if (range == null) continue;
            DiagnosticSeverity severity = switch (node.path("severity").asInt(1)) {
                case 2 -> DiagnosticSeverity.WARNING;
                case 3 -> DiagnosticSeverity.INFO;
                case 4 -> DiagnosticSeverity.HINT;
                default -> DiagnosticSeverity.ERROR;
            };
            diagnostics.add(new Diagnostic(range.start().line(), range.start().col(),
                    range.end().line(), range.end().col(), severity, node.path("message").asString("")));
        }
        return diagnostics;
    }

    private JsonNode positionalRequest(String method, Path file, String text, int line, int character,
                                       Map<String, Object> extra) {
        return positionalRequest(method, file, text, line, character, extra,
                TimeUnit.SECONDS.toMillis(REQUEST_TIMEOUT_SECONDS));
    }

    private JsonNode positionalRequest(String method, Path file, String text, int line, int character,
                                       Map<String, Object> extra, long timeoutMillis) {
        if (rpc == null || !isRunning() || file == null) return null;
        sync(file, text);
        Map<String, Object> params = new HashMap<>(extra);
        params.put("textDocument", Map.of("uri", uri(file)));
        params.put("position", Map.of("line", line, "character", character));
        try {
            return rpc.request(method, params).get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("{} falhou: {}", method, e.getMessage());
            return null;
        }
    }

    private void publishDiagnostics(JsonNode params) {
        if (params == null) return;
        String uri = params.path("uri").asString("");
        List<JsonNode> diagnostics = new ArrayList<>();
        params.path("diagnostics").forEach(diagnostics::add);
        rawDiagnostics.put(uri, diagnostics);
        try {
            if (diagnosticsListener != null) diagnosticsListener.accept(Path.of(java.net.URI.create(uri)));
        } catch (Exception ignored) {
        }
    }

    public void stop() {
        synchronized (lock) {
            stopInternal();
        }
    }

    private void stopInternal() {
        running = false;
        PhpLspJsonRpcClient client = rpc;
        if (client != null) {
            try { client.request("shutdown", Map.of()).get(2, TimeUnit.SECONDS); } catch (Exception ignored) {}
            client.notify("exit", Map.of());
            client.close();
        }
        rpc = null;
        Process current = process;
        if (current != null && current.isAlive()) current.destroy();
        process = null;
        versions.clear();
        syncedText.clear();
        rawDiagnostics.clear();
    }

    private static void drainStderr(Process process) {
        Thread.ofVirtual().name("php-lsp-stderr").start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) { }
            } catch (Exception ignored) { }
        });
    }

    private static String applyEdits(String text, JsonNode editsNode) {
        if (editsNode == null || !editsNode.isArray()) return null;
        record Edit(int start, int end, String text) {}
        List<Edit> edits = new ArrayList<>();
        for (JsonNode node : editsNode) {
            Range range = parseRange(node.get("range"));
            if (range == null) continue;
            edits.add(new Edit(offsetAt(text, range.start().line(), range.start().col()),
                    offsetAt(text, range.end().line(), range.end().col()), node.path("newText").asString("")));
        }
        edits.sort((left, right) -> Integer.compare(right.start(), left.start()));
        StringBuilder builder = new StringBuilder(text);
        for (Edit edit : edits) builder.replace(edit.start(), edit.end(), edit.text());
        return builder.toString();
    }

    private static int offsetAt(String text, int line, int col) {
        int offset = 0;
        int currentLine = 0;
        while (offset < text.length() && currentLine < line) {
            if (text.charAt(offset++) == '\n') currentLine++;
        }
        return Math.min(text.length(), offset + Math.max(0, col));
    }

    private static void addLocation(List<Location> output, JsonNode node) {
        String uri = node.path("uri").asString(node.path("targetUri").asString(""));
        Range range = parseRange(node.has("range") ? node.get("range") : node.get("targetSelectionRange"));
        if (range != null) output.add(uri.isBlank() ? Location.local(range) : Location.of(uri, range));
    }

    private static Range parseRange(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return Range.of(node.path("start").path("line").asInt(0),
                node.path("start").path("character").asInt(0),
                node.path("end").path("line").asInt(0),
                node.path("end").path("character").asInt(0));
    }

    private static List<TextEdit> parseEdits(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<TextEdit> edits = new ArrayList<>();
        for (JsonNode edit : node) {
            Range range = parseRange(edit.get("range"));
            if (range != null) edits.add(TextEdit.replace(range, edit.path("newText").asString("")));
        }
        return edits;
    }

    private static String markup(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isTextual()) return node.asString("");
        if (node.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode child : node) {
                if (!builder.isEmpty()) builder.append("\n\n");
                builder.append(markup(child));
            }
            return builder.toString();
        }
        return node.path("value").asString("");
    }

    private static AutoCompleteItem.Kind completionKind(int kind) {
        String value = switch (kind) {
            case 2 -> "METHOD"; case 3 -> "FUNCTION"; case 4 -> "CONSTRUCTOR";
            case 5 -> "FIELD"; case 6 -> "VARIABLE"; case 7 -> "CLASS";
            case 8 -> "INTERFACE"; case 9 -> "MODULE"; case 10 -> "PROPERTY";
            case 13 -> "ENUM"; case 14 -> "KEYWORD"; case 17 -> "FILE";
            case 20 -> "ENUM_MEMBER"; case 21 -> "CONSTANT"; case 22 -> "STRUCT";
            default -> "TEXT";
        };
        try { return AutoCompleteItem.Kind.valueOf(value); }
        catch (Exception ignored) { return AutoCompleteItem.Kind.TEXT; }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static String uri(Path file) {
        return file.toAbsolutePath().normalize().toUri().toString();
    }
}
