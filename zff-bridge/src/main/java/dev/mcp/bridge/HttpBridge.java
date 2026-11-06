package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Loopback-only HTTP control surface. */
public final class HttpBridge {
    private static final Logger LOG = LoggerFactory.getLogger("mcpbridge/http");

    private final byte[] token;
    private final Jobs jobs;
    private HttpServer server;
    private ExecutorService executor;

    public HttpBridge(String token, Jobs jobs) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.jobs = jobs;
    }

    public int start(int requestedPort) throws IOException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        server = HttpServer.create(new InetSocketAddress(loopback, requestedPort), 0);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "mcpbridge-http");
            thread.setDaemon(true);
            return thread;
        };
        executor = Executors.newCachedThreadPool(factory);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() {
        try {
            if (server != null) {
                server.stop(0);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (executor != null) {
                executor.shutdownNow();
                executor.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (Throwable ignored) {
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        int status = 200;
        try {
            if ("/health".equals(path)) {
                if (!"GET".equalsIgnoreCase(method)) {
                    status = 405;
                    sendText(exchange, 405, "method not allowed\n");
                    return;
                }
                sendText(exchange, 200, "ok\n");
                return;
            }
            if (!authorized(exchange)) {
                status = 401;
                sendJson(exchange, 401, Json.error(Errors.NO_PERMISSION, "missing or invalid bearer token"));
                return;
            }
            if ("/info".equals(path)) {
                if (!"GET".equalsIgnoreCase(method)) {
                    status = 405;
                    sendJson(exchange, 405, Json.error(Errors.UNSUPPORTED, "use GET"));
                    return;
                }
                sendJson(exchange, 200, info());
                return;
            }
            if ("/command".equals(path)) {
                if (!"POST".equalsIgnoreCase(method)) {
                    status = 405;
                    sendJson(exchange, 405, Json.error(Errors.UNSUPPORTED, "use POST"));
                    return;
                }
                handleCommand(exchange);
                return;
            }
            if ("/rpc".equals(path)) {
                if (!"POST".equalsIgnoreCase(method)) {
                    status = 405;
                    sendJson(exchange, 405, Json.error(Errors.UNSUPPORTED, "use POST"));
                    return;
                }
                handleRpc(exchange);
                return;
            }
            if ("/jobs".equals(path)) {
                JsonObject o = Json.ok();
                o.add("jobs", jobs == null ? new JsonArray() : jobs.list());
                sendJson(exchange, 200, o);
                return;
            }
            if (path.startsWith("/jobs/")) {
                String rest = path.substring("/jobs/".length());
                if (rest.endsWith("/cancel")) {
                    String id = rest.substring(0, rest.length() - "/cancel".length());
                    cancelJob(exchange, id);
                } else {
                    statusOfJob(exchange, rest);
                }
                return;
            }
            status = 404;
            sendJson(exchange, 404, Json.error(Errors.UNSUPPORTED, "unknown endpoint"));
        } catch (Errors.BridgeException e) {
            status = 200;
            JsonObject error = Json.error(e.code, e.getMessage());
            error.addProperty("partial", e.partial);
            sendJson(exchange, 200, error);
        } catch (Throwable t) {
            status = 500;
            LOG.warn("HTTP {} {} failed: {}", method, path, t.toString());
            sendJson(exchange, 500, Json.error(Errors.INTERNAL, t.toString()));
        } finally {
            if (LOG.isDebugEnabled()) {
                LOG.debug("HTTP {} {} -> {}", method, path, status);
            }
            exchange.close();
        }
    }

    private void handleCommand(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        int timeout = parseTimeout(exchange, 30);
        JsonObject result = Tools.command(body, timeout);
        sendJson(exchange, 200, result);
    }

    private void handleRpc(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (Throwable t) {
            sendJson(exchange, 400, Json.error(Errors.INTERNAL, "body is not valid JSON"));
            return;
        }
        if (!parsed.isJsonObject()) {
            sendJson(exchange, 400, Json.error(Errors.INTERNAL, "body must be a JSON object"));
            return;
        }
        JsonObject request = parsed.getAsJsonObject();
        String tool = Json.str(request, "tool", null);
        if (tool == null || tool.isBlank()) {
            sendJson(exchange, 400, Json.error(Errors.INTERNAL, "tool is required"));
            return;
        }
        JsonObject args = Json.sub(request, "args");
        int timeout = Json.i(request, "timeoutSeconds", 0);
        Object result = Tools.call(tool, args == null ? new JsonObject() : args, timeout);
        if (result instanceof Jobs.Job job) {
            JsonObject o = Json.ok();
            o.addProperty("jobId", job.id);
            o.addProperty("tool", job.tool);
            o.addProperty("state", job.state().name().toLowerCase());
            o.add("job", job.toJson());
            sendJson(exchange, 200, o);
        } else if (result instanceof JsonObject obj) {
            JsonObject o = Json.ok();
            o.add("result", obj);
            sendJson(exchange, 200, o);
        } else {
            JsonObject o = Json.ok();
            o.add("result", Json.GSON.toJsonTree(result));
            sendJson(exchange, 200, o);
        }
    }

    private void statusOfJob(HttpExchange exchange, String id) throws IOException {
        Jobs.Job job = jobs == null ? null : jobs.get(id);
        if (job == null) {
            sendJson(exchange, 404, Json.error(Errors.INTERNAL, "unknown job id"));
            return;
        }
        JsonObject o = Json.ok();
        o.add("job", job.toJson());
        sendJson(exchange, 200, o);
    }

    private void cancelJob(HttpExchange exchange, String id) throws IOException {
        if (jobs == null) {
            sendJson(exchange, 404, Json.error(Errors.INTERNAL, "job manager is not available"));
            return;
        }
        if (jobs.get(id) == null) {
            sendJson(exchange, 404, Json.error(Errors.INTERNAL, "unknown job id"));
            return;
        }
        JsonObject result = MainThread.await(MainThread.submit("job_cancel", () -> {
            jobs.cancel(id);
            return jobs.get(id).toJson();
        }), 5_000);
        JsonObject o = Json.ok();
        o.add("job", result);
        sendJson(exchange, 200, o);
    }

    private JsonObject info() {
        JsonObject o = Tools.bridgeInfo();
        o.addProperty("port", server == null ? 0 : server.getAddress().getPort());
        return o;
    }

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        byte[] provided = header.substring("Bearer ".length()).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(token, provided);
    }

    private static int parseTimeout(HttpExchange exchange, int def) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return def;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && "timeout".equals(part.substring(0, eq))) {
                try {
                    return MathUtil.clamp(Integer.parseInt(part.substring(eq + 1)), 1, 600);
                } catch (NumberFormatException ignored) {
                    return def;
                }
            }
        }
        return def;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(BridgeConfig.MAX_BODY_BYTES + 1);
            if (bytes.length > BridgeConfig.MAX_BODY_BYTES) {
                throw new Errors.BridgeException(Errors.INTERNAL, "request body is too large");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static void sendJson(HttpExchange exchange, int status, JsonElement body) throws IOException {
        String json = Json.GSON.toJson(body);
        send(exchange, status, "application/json; charset=utf-8", json);
    }

    private static void sendText(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "text/plain; charset=utf-8", body);
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
