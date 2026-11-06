package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Ph6 §2.4: the <b>mod store</b>, {@code <gameDir>/zff/mod-store.json}.
 *
 * <p>The two stores are deliberately not synchronised. The mod store is written by the mod only (the
 * full chat ring, the last environment snapshot, a cheap inventory digest) and the agent may read it
 * through {@code store_get {store:"mod"}}; the CR store is written by the agent only. Same key name in
 * both stores means two different values, by design.</p>
 *
 * <p>Written every 5 s from the client thread, atomically (tmp + move) so a reader never sees a
 * half-written file. The ring is capped at {@link ChatLog#MAX} lines, i.e. 2000.</p>
 */
public final class ModStore {
    private static final long INTERVAL_MS = 5_000;
    private static volatile long lastWriteAt;
    private static volatile String lastError;
    private static volatile long writes;

    private ModStore() {
    }

    public static void tick() {
        long now = System.currentTimeMillis();
        if (now - lastWriteAt < INTERVAL_MS) {
            return;
        }
        lastWriteAt = now;
        write();
    }

    /** Forces a write (used after a chat burst and by the {@code mod_store_flush} tool). */
    public static JsonObject flush() {
        lastWriteAt = System.currentTimeMillis();
        write();
        JsonObject o = new JsonObject();
        o.addProperty("ok", lastError == null);
        Path file = file();
        if (file != null) {
            o.addProperty("path", file.toString());
        }
        o.addProperty("writes", writes);
        if (lastError != null) {
            o.addProperty("error", lastError);
        }
        return o;
    }

    public static Path file() {
        Path gameDir = BridgeRuntime.gameDir;
        if (gameDir == null) {
            return null;
        }
        return BridgeConfig.modStoreDir(gameDir).resolve("mod-store.json");
    }

    private static void write() {
        Path gameDir = BridgeRuntime.gameDir;
        if (gameDir == null) {
            return;
        }
        try {
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.addProperty("updatedAt", System.currentTimeMillis());
            root.addProperty("gameDir", gameDir.toString());
            root.addProperty("modVersion", BridgeConfig.VERSION);
            root.addProperty("protocol", BridgeConfig.PROTOCOL);
            root.add("backend", Backend.toJson());
            root.addProperty("player", BridgeRuntime.playerName == null ? "" : BridgeRuntime.playerName);
            root.add("chat", ChatLog.storeHistory(ChatLog.MAX));
            JsonObject env = EnvSampler.lastSnapshot();
            if (env != null) {
                root.add("env", env);
            }
            JsonObject inv = inventoryDigest();
            if (inv != null) {
                root.add("inventory", inv);
            }
            Path dir = BridgeConfig.modStoreDir(gameDir);
            Files.createDirectories(dir);
            Path target = dir.resolve("mod-store.json");
            Path tmp = dir.resolve("mod-store.json.tmp");
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            lastError = null;
            writes++;
        } catch (Throwable t) {
            lastError = t.toString();
        }
    }

    /** Cheap summary only: the full inventory belongs to {@code get_state}, not to a 5 s file write. */
    private static JsonObject inventoryDigest() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return null;
        }
        try {
            JsonObject o = new JsonObject();
            var inv = player.getInventory();
            int used = 0;
            JsonArray items = new JsonArray();
            for (int i = 0; i < 36; i++) {
                ItemStack s = inv.getItem(i);
                if (s == null || s.isEmpty()) {
                    continue;
                }
                used++;
                String id = EnvSampler.itemId(s);
                if (id == null) {
                    continue;
                }
                JsonObject it = new JsonObject();
                it.addProperty("slot", i);
                it.addProperty("id", id);
                it.addProperty("count", s.getCount());
                items.add(it);
            }
            o.addProperty("usedSlots", used);
            o.addProperty("freeSlots", 36 - used);
            o.addProperty("selectedSlot", inv.selected);
            o.add("items", items);
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    public static JsonObject status() {
        JsonObject o = new JsonObject();
        Path file = file();
        o.addProperty("path", file == null ? "" : file.toString());
        o.addProperty("writes", writes);
        o.addProperty("lastWriteAt", lastWriteAt);
        if (lastError != null) {
            o.addProperty("lastError", lastError);
        }
        if (file != null && Files.exists(file)) {
            try {
                o.addProperty("size", Files.size(file));
            } catch (Throwable ignored) {
                // size is diagnostic only
            }
        }
        return o;
    }
}
