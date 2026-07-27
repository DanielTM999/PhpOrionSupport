package dtm.ide.debug;

import dtm.ide.api.extension.event.BreakpointChangedEvent;
import dtm.ide.api.extension.runconfig.RunBreakpointData;
import dtm.ide.api.project.editor.BreakpointIde;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public final class PhpDapSession {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public interface Listener {
        void onStopped(Path file, int oneBasedLine);
        void onContinued();
        void onTerminated();
    }

    private final List<String> command;
    private final Path projectRoot;
    private final int port;
    private final Listener listener;
    private final OutputStream console;
    private final Map<Path, NavigableMap<Integer, String>> breakpoints = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch configured = new CountDownLatch(1);
    private final Object writeLock = new Object();
    private volatile Process process;
    private volatile OutputStream input;
    private volatile int stoppedThread;

    public PhpDapSession(List<String> command, Path projectRoot, int port,
                         List<RunBreakpointData> initialBreakpoints,
                         OutputStream console, Listener listener) {
        this.command = List.copyOf(command);
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.port = Math.clamp(port, 1, 65535);
        this.console = console;
        this.listener = listener;
        seed(initialBreakpoints);
    }

    public void start() throws IOException {
        process = new ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .start();
        input = process.getOutputStream();
        Thread reader = daemon(this::readLoop, "php-dap-reader");
        reader.start();
        daemon(() -> pump(process.getErrorStream()), "php-dap-stderr").start();

        ObjectNode initialize = MAPPER.createObjectNode();
        initialize.put("adapterID", "php");
        initialize.put("clientID", "orion-php");
        initialize.put("clientName", "Orion IDE");
        initialize.put("linesStartAt1", true);
        initialize.put("columnsStartAt1", true);
        initialize.put("pathFormat", "path");
        initialize.put("supportsRunInTerminalRequest", false);
        try {
            request("initialize", initialize).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            terminate();
            throw new IOException("Falha ao inicializar o adaptador Xdebug: " + rootMessage(e), e);
        }

        ObjectNode launch = MAPPER.createObjectNode();
        launch.put("name", "PHP Debug");
        launch.put("type", "php");
        launch.put("request", "launch");
        launch.put("hostname", "127.0.0.1");
        launch.put("port", port);
        launch.put("stopOnEntry", false);
        launch.put("log", false);
        launch.set("pathMappings", MAPPER.valueToTree(Map.of()));
        request("launch", launch).exceptionally(error -> {
            writeConsole("[debug] falha no listener Xdebug: " + rootMessage(error) + "\n");
            return null;
        });
        try {
            if (!configured.await(15, TimeUnit.SECONDS)) {
                throw new IOException("O adaptador Xdebug não concluiu a configuração em 15 segundos.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Inicialização do Xdebug interrompida.", e);
        }
        writeConsole("[debug] escutando Xdebug em 127.0.0.1:" + port + ".\n");
    }

    public boolean isAlive() {
        Process value = process;
        return !closed.get() && value != null && value.isAlive();
    }

    public void applyBreakpoint(BreakpointChangedEvent event) {
        if (event == null || event.getFile() == null || event.getBreakpointIde() == null) return;
        Path file = event.getFile().toAbsolutePath().normalize();
        BreakpointIde breakpoint = event.getBreakpointIde();
        NavigableMap<Integer, String> lines = breakpoints.computeIfAbsent(file, ignored -> new TreeMap<>());
        synchronized (lines) {
            if (event.isBreakpointAdded() && breakpoint.active()) {
                lines.put(breakpoint.line(), normalizeCondition(
                        event.getCondition() == null ? breakpoint.condition() : event.getCondition()));
            } else {
                lines.remove(breakpoint.line());
            }
        }
        sendBreakpoints(file, lines);
    }

    public void resume() {
        threadCommand("continue");
    }

    public void next() {
        threadCommand("next");
    }

    public void stepIn() {
        threadCommand("stepIn");
    }

    public void stepOut() {
        threadCommand("stepOut");
    }

    public void pause() {
        threadCommand("pause");
    }

    private void threadCommand(String command) {
        int thread = stoppedThread;
        ObjectNode args = MAPPER.createObjectNode();
        if (thread > 0) args.put("threadId", thread);
        request(command, args).exceptionally(error -> {
            writeConsole("[debug] " + command + " falhou: " + rootMessage(error) + "\n");
            return null;
        });
    }

    public void terminate() {
        if (!closed.compareAndSet(false, true)) return;
        if (!Thread.currentThread().getName().equals("php-dap-reader")) {
            try {
                request("disconnect", Map.of("terminateDebuggee", false)).get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        Process value = process;
        if (value != null && value.isAlive()) value.destroy();
        pending.values().forEach(future ->
                future.completeExceptionally(new IOException("Sessão Xdebug encerrada.")));
        pending.clear();
        configured.countDown();
        if (listener != null) listener.onTerminated();
    }

    private void seed(List<RunBreakpointData> values) {
        if (values == null) return;
        for (RunBreakpointData data : values) {
            if (data == null || data.getFile() == null) continue;
            NavigableMap<Integer, String> lines = breakpoints.computeIfAbsent(
                    data.getFile().toAbsolutePath().normalize(), ignored -> new TreeMap<>());
            if (data.getBreakpoints() != null && !data.getBreakpoints().isEmpty()) {
                for (BreakpointIde breakpoint : data.getBreakpoints()) {
                    if (breakpoint != null && breakpoint.active()) {
                        lines.put(breakpoint.line(), normalizeCondition(breakpoint.condition()));
                    }
                }
            } else if (data.getLines() != null) {
                data.getLines().forEach(line -> lines.put(line, null));
            }
        }
    }

    private void configure() {
        List<CompletableFuture<JsonNode>> requests = new ArrayList<>();
        breakpoints.forEach((file, lines) -> requests.add(sendBreakpoints(file, lines)));
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
                .exceptionally(error -> {
                    writeConsole("[debug] alguns breakpoints não foram aceitos: " + rootMessage(error) + "\n");
                    return null;
                })
                .thenCompose(unused -> request("setExceptionBreakpoints", Map.of("filters", List.of())))
                .thenCompose(unused -> request("configurationDone", Map.of()))
                .whenComplete((unused, error) -> {
                    if (error != null) {
                        writeConsole("[debug] configuração falhou: " + rootMessage(error) + "\n");
                    }
                    configured.countDown();
                });
    }

    private CompletableFuture<JsonNode> sendBreakpoints(Path file, NavigableMap<Integer, String> values) {
        Map<Integer, String> snapshot;
        synchronized (values) {
            snapshot = new LinkedHashMap<>(values);
        }
        ObjectNode args = MAPPER.createObjectNode();
        ObjectNode source = args.putObject("source");
        source.put("path", file.toString());
        source.put("name", file.getFileName() == null ? file.toString() : file.getFileName().toString());
        ArrayNode array = args.putArray("breakpoints");
        snapshot.forEach((line, condition) -> {
            ObjectNode breakpoint = array.addObject();
            breakpoint.put("line", line + 1);
            if (condition != null) breakpoint.put("condition", condition);
        });
        return request("setBreakpoints", args);
    }

    private CompletableFuture<JsonNode> request(String command, Object arguments) {
        int id = sequence.getAndIncrement();
        ObjectNode message = MAPPER.createObjectNode();
        message.put("seq", id);
        message.put("type", "request");
        message.put("command", command);
        message.set("arguments", MAPPER.valueToTree(arguments == null ? Map.of() : arguments));
        CompletableFuture<JsonNode> result = new CompletableFuture<>();
        pending.put(id, result);
        try {
            write(message);
        } catch (Exception e) {
            pending.remove(id);
            result.completeExceptionally(e);
        }
        return result;
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                JsonNode message = read();
                if (message == null) break;
                dispatch(message);
            }
        } catch (Exception e) {
            if (!closed.get()) writeConsole("[debug] canal DAP encerrado: " + e.getMessage() + "\n");
        } finally {
            if (!closed.get()) terminate();
        }
    }

    private void dispatch(JsonNode message) {
        String type = text(message, "type");
        if ("response".equals(type)) {
            int requestSeq = message.path("request_seq").asInt();
            CompletableFuture<JsonNode> future = pending.remove(requestSeq);
            if (future == null) return;
            if (!message.path("success").asBoolean(true)) {
                future.completeExceptionally(new IOException(text(message, "message")));
            } else {
                future.complete(message.path("body"));
            }
            return;
        }
        if ("request".equals(type)) {
            respondUnsupported(message);
            return;
        }
        if (!"event".equals(type)) return;
        String event = text(message, "event");
        JsonNode body = message.path("body");
        switch (event) {
            case "initialized" -> daemon(this::configure, "php-dap-configure").start();
            case "stopped" -> {
                stoppedThread = body.path("threadId").asInt();
                stoppedLocation(stoppedThread);
            }
            case "continued" -> {
                if (listener != null) listener.onContinued();
            }
            case "output" -> writeConsole(body.path("output").asString());
            case "terminated", "exited" -> terminate();
            default -> {
            }
        }
    }

    private void stoppedLocation(int threadId) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("threadId", threadId);
        args.put("startFrame", 0);
        args.put("levels", 1);
        request("stackTrace", args).whenComplete((body, error) -> {
            if (error != null) {
                writeConsole("[debug] não foi possível obter a pilha: " + rootMessage(error) + "\n");
                return;
            }
            JsonNode frame = body.path("stackFrames").path(0);
            String source = frame.path("source").path("path").asString("");
            int line = frame.path("line").asInt(1);
            if (!source.isBlank() && listener != null) {
                try {
                    listener.onStopped(Path.of(source), line);
                } catch (Exception e) {
                    writeConsole("[debug] caminho recebido é inválido: " + source + "\n");
                }
            }
        });
    }

    private void respondUnsupported(JsonNode request) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("seq", sequence.getAndIncrement());
        response.put("type", "response");
        response.put("request_seq", request.path("seq").asInt());
        response.put("command", text(request, "command"));
        response.put("success", false);
        response.put("message", "A Orion não oferece terminal externo ao adaptador PHP.");
        try {
            write(response);
        } catch (IOException ignored) {
        }
    }

    private void write(ObjectNode message) throws IOException {
        byte[] body = MAPPER.writeValueAsBytes(message);
        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        synchronized (writeLock) {
            if (input == null) throw new IOException("Canal DAP não iniciado.");
            input.write(header);
            input.write(body);
            input.flush();
        }
    }

    private JsonNode read() throws IOException {
        InputStream output = process.getInputStream();
        int length = -1;
        StringBuilder line = new StringBuilder();
        while (true) {
            int value = output.read();
            if (value < 0) return null;
            if (value == '\r') {
                int next = output.read();
                if (next != '\n') throw new IOException("Cabeçalho DAP inválido.");
                String header = line.toString();
                line.setLength(0);
                if (header.isEmpty()) break;
                int colon = header.indexOf(':');
                if (colon > 0 && "content-length".equals(
                        header.substring(0, colon).strip().toLowerCase(Locale.ROOT))) {
                    length = Integer.parseInt(header.substring(colon + 1).strip());
                }
            } else {
                line.append((char) value);
            }
        }
        if (length < 0) throw new IOException("Mensagem DAP sem Content-Length.");
        byte[] body = output.readNBytes(length);
        if (body.length != length) throw new IOException("Mensagem DAP incompleta.");
        return MAPPER.readTree(body);
    }

    private void pump(InputStream stream) {
        try (stream) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                synchronized (console) {
                    console.write(buffer, 0, count);
                    console.flush();
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void writeConsole(String text) {
        if (console == null || text == null || text.isEmpty()) return;
        try {
            synchronized (console) {
                console.write(text.getBytes(StandardCharsets.UTF_8));
                console.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private static String text(JsonNode node, String key) {
        return node.path(key).asString("");
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static String normalizeCondition(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
