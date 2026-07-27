package dtm.ide.lsp;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

@Slf4j
final class PhpLspJsonRpcClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final InputStream input;
    private final OutputStream output;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<String, Consumer<JsonNode>> notifications = new ConcurrentHashMap<>();
    private final Map<String, Function<JsonNode, Object>> requests = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "php-lsp-rpc");
        thread.setDaemon(true);
        return thread;
    });
    private final Future<?> reader;
    private final Object writeLock = new Object();
    private volatile boolean closed;

    PhpLspJsonRpcClient(InputStream input, OutputStream output) {
        this.input = input;
        this.output = output;
        this.reader = executor.submit(this::readLoop);
    }

    CompletableFuture<JsonNode> request(String method, Object params) {
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        ObjectNode message = MAPPER.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.set("params", MAPPER.valueToTree(params == null ? Map.of() : params));
        try {
            write(message);
        } catch (Exception e) {
            pending.remove(id);
            future.completeExceptionally(e);
        }
        return future;
    }

    void notify(String method, Object params) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.set("params", MAPPER.valueToTree(params == null ? Map.of() : params));
        try {
            write(message);
        } catch (Exception e) {
            log.debug("Falha ao enviar {} ao Intelephense: {}", method, e.getMessage());
        }
    }

    void onNotification(String method, Consumer<JsonNode> handler) {
        notifications.put(method, handler);
    }

    void onRequest(String method, Function<JsonNode, Object> handler) {
        requests.put(method, handler);
    }

    void close() {
        closed = true;
        try { input.close(); } catch (IOException ignored) {}
        try { output.close(); } catch (IOException ignored) {}
        pending.values().forEach(future -> future.completeExceptionally(new IOException("LSP encerrado")));
        pending.clear();
        reader.cancel(true);
        executor.shutdownNow();
    }

    private void write(ObjectNode message) throws IOException {
        byte[] body = MAPPER.writeValueAsBytes(message);
        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        synchronized (writeLock) {
            output.write(header);
            output.write(body);
            output.flush();
        }
    }

    private void readLoop() {
        try {
            while (!closed) {
                JsonNode message = read();
                if (message == null) break;
                dispatch(message);
            }
        } catch (Exception e) {
            if (!closed) log.debug("Leitor do Intelephense encerrado: {}", e.getMessage());
        }
    }

    private void dispatch(JsonNode message) {
        JsonNode id = message.get("id");
        JsonNode methodNode = message.get("method");
        if (id != null && id.canConvertToLong() && (message.has("result") || message.has("error"))) {
            CompletableFuture<JsonNode> future = pending.remove(id.asLong());
            if (future == null) return;
            if (message.has("error")) future.completeExceptionally(new IOException(message.get("error").toString()));
            else future.complete(message.get("result"));
            return;
        }
        if (methodNode == null) return;
        String method = methodNode.asString();
        if (id != null) {
            Object result = null;
            Function<JsonNode, Object> handler = requests.get(method);
            if (handler != null) {
                try { result = handler.apply(message.get("params")); }
                catch (Exception e) { log.debug("Request LSP {} falhou: {}", method, e.getMessage()); }
            }
            ObjectNode response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", id);
            if (result == null) response.putNull("result");
            else response.set("result", MAPPER.valueToTree(result));
            try { write(response); } catch (IOException ignored) {}
            return;
        }
        Consumer<JsonNode> handler = notifications.get(method);
        if (handler != null) handler.accept(message.get("params"));
    }

    private JsonNode read() throws IOException {
        int length = -1;
        StringBuilder line = new StringBuilder();
        while (true) {
            int c = input.read();
            if (c < 0) return null;
            if (c == '\r' && input.read() == '\n') {
                String header = line.toString();
                line.setLength(0);
                if (header.isEmpty()) break;
                int colon = header.indexOf(':');
                if (colon > 0 && header.substring(0, colon).trim().toLowerCase(Locale.ROOT)
                        .equals("content-length")) {
                    length = Integer.parseInt(header.substring(colon + 1).trim());
                }
            } else {
                line.append((char) c);
            }
        }
        if (length < 0) throw new IOException("Resposta LSP sem Content-Length");
        byte[] body = input.readNBytes(length);
        if (body.length != length) throw new IOException("Resposta LSP incompleta");
        return MAPPER.readTree(body);
    }
}
