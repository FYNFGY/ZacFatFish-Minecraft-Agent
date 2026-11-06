package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ph6 M1 §2.8: push every chat line out of the game as soon as it arrives.
 *
 * <p>Why a push instead of the old poll: the whole point of {@code Flag_User_Chat} is that the Node
 * side learns about a player message <b>while</b> the agent is stuck inside a blocking tool call. A
 * poll in the agent cannot do that, so the mod itself pushes each line to the daemon
 * ({@code POST http://127.0.0.1:57611/chat_event}), which forwards it to the MCP server. All the
 * matching (the {@code ^鱼} rule), the unread queue, the cursor and the flag live in Node; the mod only
 * pushes and, when asked, sends.</p>
 *
 * <p>This class touches no Minecraft class at all: {@link ChatLog} hands it a finished JSON object from
 * the client thread and it is posted from a single background thread. The client tick is never blocked
 * (a bounded queue drops the oldest line under pressure, and losing a chat line must never hang the
 * game).</p>
 */
public final class ChatPush {
    private static final int QUEUE_CAPACITY = 1024;
    private static final int MAX_BATCH = 64;
    private static final long CONNECT_TIMEOUT_MS = 1500;
    private static final long REQUEST_TIMEOUT_MS = 3000;
    private static final long ERROR_LOG_INTERVAL_MS = 30_000;

    public static final AtomicLong SENT = new AtomicLong();
    public static final AtomicLong DROPPED = new AtomicLong();
    public static final AtomicLong FAILED = new AtomicLong();
    public static volatile long lastSentAt;
    public static volatile String lastError;
    private static volatile long lastErrorLogAt;

    private static volatile BlockingQueue<JsonObject> queue;
    private static volatile String url;
    private static volatile String token;
    private static volatile Thread worker;
    private static volatile HttpClient http;
    private static final Object START_LOCK = new Object();

    private ChatPush() {
    }

    /** Idempotent; called once from {@link BridgeRuntime#start()} on the client. */
    public static void start(String pushUrl, String bearerToken) {
        synchronized (START_LOCK) {
            if (worker != null && worker.isAlive()) {
                url = pushUrl;
                token = bearerToken;
                return;
            }
            url = pushUrl;
            token = bearerToken;
            queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
            try {
                http = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                        .build();
            } catch (Throwable t) {
                lastError = "http client: " + t;
                return;
            }
            Thread t = new Thread(ChatPush::loop, "mcpbridge-chat-push");
            t.setDaemon(true);
            t.start();
            worker = t;
        }
    }

    /** Called on the client thread; never blocks, never throws. */
    public static void enqueue(JsonObject event) {
        BlockingQueue<JsonObject> q = queue;
        if (q == null || event == null) {
            return;
        }
        if (!q.offer(event)) {
            // Keep the newest lines: dropping the oldest keeps the buffer useful for the agent.
            q.poll();
            if (!q.offer(event)) {
                DROPPED.incrementAndGet();
            }
        }
    }

    private static void loop() {
        while (true) {
            try {
                BlockingQueue<JsonObject> q = queue;
                if (q == null) {
                    return;
                }
                JsonObject first = q.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<JsonObject> batch = new ArrayList<>(MAX_BATCH);
                batch.add(first);
                while (batch.size() < MAX_BATCH) {
                    JsonObject next = q.poll(5, TimeUnit.MILLISECONDS);
                    if (next == null) {
                        break;
                    }
                    batch.add(next);
                }
                post(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                // A malformed payload must never kill the push loop.
                noteError(t.toString());
            }
        }
    }

    private static void post(List<JsonObject> batch) {
        String target = url;
        String bearer = token;
        HttpClient client = http;
        if (target == null || client == null) {
            return;
        }
        JsonArray events = new JsonArray();
        for (JsonObject o : batch) {
            events.add(o);
        }
        JsonObject body = new JsonObject();
        body.add("events", events);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), java.nio.charset.StandardCharsets.UTF_8));
            if (bearer != null && !bearer.isBlank()) {
                builder.header("Authorization", "Bearer " + bearer);
            }
            HttpResponse<String> res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                SENT.addAndGet(batch.size());
                lastSentAt = System.currentTimeMillis();
            } else {
                FAILED.addAndGet(batch.size());
                noteError("HTTP " + res.statusCode() + " from " + target + ": " + shorten(res.body()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            FAILED.addAndGet(batch.size());
            noteError("push failed: " + t);
        }
    }

    private static void noteError(String message) {
        lastError = message;
        long now = System.currentTimeMillis();
        if (now - lastErrorLogAt < ERROR_LOG_INTERVAL_MS) {
            return;
        }
        lastErrorLogAt = now;
        System.out.println("[zff] chat push: " + message);
    }

    private static String shorten(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 160 ? s : s.substring(0, 160) + "...";
    }

    /** Diagnostics for {@code bridge_info}. */
    public static JsonObject stats() {
        JsonObject o = new JsonObject();
        o.addProperty("url", url == null ? "" : url);
        o.addProperty("queued", queue == null ? 0 : queue.size());
        o.addProperty("sent", SENT.get());
        o.addProperty("failed", FAILED.get());
        o.addProperty("dropped", DROPPED.get());
        o.addProperty("lastSentAt", lastSentAt);
        if (lastError != null) {
            o.addProperty("lastError", lastError);
        }
        return o;
    }
}
