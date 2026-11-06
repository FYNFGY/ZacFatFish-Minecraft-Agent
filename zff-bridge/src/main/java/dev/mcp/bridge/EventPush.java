package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;

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
 * Ph7 M1-3: push client-side edge events (fall / damage / hunger / inventory_full) to the daemon at
 * {@code POST /event}, which forwards them to the MCP server's interrupt engine.
 *
 * <p>Like {@link ChatPush}, this class never touches the client tick beyond a non-blocking
 * {@code offer()} into a bounded queue; all HTTP happens on one daemon thread.</p>
 */
public final class EventPush {
    private static final int QUEUE_CAPACITY = 256;
    private static final int MAX_BATCH = 16;
    private static final long CONNECT_TIMEOUT_MS = 1200;
    private static final long REQUEST_TIMEOUT_MS = 2500;
    private static final long ERROR_LOG_INTERVAL_MS = 30_000;

    public static final AtomicLong SENT = new AtomicLong();
    public static final AtomicLong DROPPED = new AtomicLong();
    public static final AtomicLong FAILED = new AtomicLong();
    public static volatile String lastError;
    private static volatile long lastErrorLogAt;

    private static volatile BlockingQueue<JsonObject> queue;
    private static volatile String url;
    private static volatile String token;
    private static volatile Thread worker;
    private static volatile HttpClient http;
    private static final Object START_LOCK = new Object();

    // Edge state, only touched on the client thread.
    private static java.util.UUID lastPlayer;
    private static float lastHealth = -1;
    private static int lastFood = -1;
    private static boolean falling;
    private static boolean full;

    private EventPush() {
    }

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
            Thread t = new Thread(EventPush::loop, "mcpbridge-event-push");
            t.setDaemon(true);
            t.start();
            worker = t;
        }
    }

    /** Called from the client tick; never blocks, never throws. */
    public static void tick(LocalPlayer player) {
        if (player == null) {
            reset();
            return;
        }
        // Proactive self-protect must run even if the event HTTP push is not configured.
        try {
            SelfProtect.tick(player);
        } catch (Throwable ignored) {
            // self-protect must never break the client tick
        }
        if (queue == null) {
            reset();
            return;
        }
        if (lastPlayer == null || !lastPlayer.equals(player.getUUID())) {
            reset();
            lastPlayer = player.getUUID();
            lastHealth = player.getHealth();
            lastFood = player.getFoodData().getFoodLevel();
            return;
        }
        // ---- damage: health dropped since the previous tick
        float health = player.getHealth();
        if (lastHealth >= 0 && health < lastHealth - 0.01f) {
            JsonObject data = new JsonObject();
            data.addProperty("amount", MathUtil.round2(lastHealth - health));
            data.addProperty("health", MathUtil.round2(health));
            data.addProperty("x", MathUtil.round2(player.getX()));
            data.addProperty("y", MathUtil.round2(player.getY()));
            data.addProperty("z", MathUtil.round2(player.getZ()));
            var source = player.getLastDamageSource();
            String cause = source == null ? "unknown" : source.getMsgId();
            data.addProperty("source", cause);
            String detail = "took " + MathUtil.round2(lastHealth - health) + " damage (" + cause + "), health " + MathUtil.round2(health);
            enqueue("damage", detail, data);
            try {
                SelfProtect.onDamage(player, source == null ? null : source.getEntity());
            } catch (Throwable ignored) {
                // self-protect must never break the event loop
            }
        }
        lastHealth = health;

        // ---- fall: falling more than 2.5 blocks, edge-triggered until landing
        boolean nowFalling = player.fallDistance > 2.5f && !player.onGround();
        if (nowFalling && !falling) {
            JsonObject data = new JsonObject();
            data.addProperty("fallDistance", MathUtil.round2(player.fallDistance));
            data.addProperty("y", MathUtil.round2(player.getY()));
            data.addProperty("x", MathUtil.round2(player.getX()));
            data.addProperty("z", MathUtil.round2(player.getZ()));
            data.addProperty("suggest", "mlg_water");
            enqueue("fall", "falling " + MathUtil.round2(player.fallDistance) + " blocks at y=" + MathUtil.round2(player.getY()), data);
        }
        falling = nowFalling;

        // ---- hunger: first time food <= 6 (reset only after eating back above it)
        int food = player.getFoodData().getFoodLevel();
        if (food <= 6 && lastFood > 6) {
            JsonObject data = new JsonObject();
            data.addProperty("food", food);
            data.addProperty("saturation", MathUtil.round2(player.getFoodData().getSaturationLevel()));
            enqueue("hunger", "food dropped to " + food + "/20", data);
        }
        lastFood = food;

        // ---- inventory_full: all 36 main slots occupied (edge)
        int used = 0;
        for (int i = 0; i < 36; i++) {
            if (!player.getInventory().getItem(i).isEmpty()) {
                used++;
            }
        }
        boolean nowFull = used >= 36;
        if (nowFull && !full) {
            JsonObject data = new JsonObject();
            data.addProperty("used", used);
            data.addProperty("total", 36);
            data.addProperty("lastItem", player.getInventory().getItem(35).isEmpty() ? "" : player.getInventory().getItem(35).getItem().toString());
            enqueue("inventory_full", "all 36 inventory slots are full", data);
        }
        full = nowFull;
    }

    private static void reset() {
        lastPlayer = null;
        lastHealth = -1;
        lastFood = -1;
        falling = false;
        full = false;
    }

    /** Called by ScanSurface: an ore-discovery side event (16x16x16, centred on the player). */
    public static void pushOre(JsonArray ores) {
        if (ores == null || ores.isEmpty()) return;
        JsonObject data = new JsonObject();
        data.add("ores", ores.deepCopy());
        enqueue("ore", "ores nearby (" + ores.size() + ")", data);
    }

    /** Ph8: the automatic mining job stopped because the current tool is on its last use. */
    public static void pushToolLow(net.minecraft.world.item.ItemStack stack, int remaining) {
        if (stack == null || stack.isEmpty()) return;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();
        int max = stack.getMaxDamage();
        JsonObject data = new JsonObject();
        data.addProperty("tool", id);
        data.addProperty("remaining", remaining);
        data.addProperty("max", max);
        enqueue("tool_low", "tool " + id + " has " + remaining + " use(s) left", data);
    }

    /** Ph8 combat/weapon failure line for InfoTxt. */
    public static void pushCombat(String detail) {
        JsonObject data = new JsonObject();
        data.addProperty("detail", detail == null ? "" : detail);
        enqueue("combat", detail == null ? "combat failed" : detail, data);
    }

    private static void enqueue(String type, String detail, JsonObject data) {
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        event.addProperty("detail", detail);
        event.addProperty("time", System.currentTimeMillis());
        if (data != null) {
            event.add("data", data);
        }
        BlockingQueue<JsonObject> q = queue;
        if (q == null) {
            return;
        }
        if (!q.offer(event)) {
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
            } else {
                FAILED.addAndGet(batch.size());
                noteError("HTTP " + res.statusCode() + " from " + target);
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
        System.out.println("[zff] event push: " + message);
    }

    public static JsonObject stats() {
        JsonObject o = new JsonObject();
        o.addProperty("url", url == null ? "" : url);
        o.addProperty("queued", queue == null ? 0 : queue.size());
        o.addProperty("sent", SENT.get());
        o.addProperty("failed", FAILED.get());
        o.addProperty("dropped", DROPPED.get());
        if (lastError != null) {
            o.addProperty("lastError", lastError);
        }
        return o;
    }
}
