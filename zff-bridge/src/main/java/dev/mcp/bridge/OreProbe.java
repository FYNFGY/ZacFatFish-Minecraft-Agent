package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Ore probe stream (Ph4 P1-2): every time a block is broken, the six neighbours are probed and any
 * ore is recorded with its coordinates and pushed to the {@link EventJournal} as {@code ore_found}.
 *
 * <p>The tunnel jobs also put the last probe into their metrics, so a job result already explains
 * where an ore was seen. {@code ore_log} exposes the stream to the agent between jobs.</p>
 */
public final class OreProbe {
    private static final int MAX = 200;
    private static final Deque<JsonObject> FOUND = new ArrayDeque<>();
    private static int total;

    private OreProbe() {
    }

    public static boolean isOre(String id) {
        return id.contains("_ore") || id.contains("ancient_debris");
    }

    /**
     * Probes the six neighbours of {@code broken}.
     *
     * @return a JSON array of {pos, id, ore} entries for the non-air neighbours (always emitted, so
     * the caller can keep it as a metric), and records the ores in the global stream
     */
    public static JsonArray record(ClientLevel level, BlockPos broken, String jobId) {
        JsonArray out = new JsonArray();
        if (level == null) {
            return out;
        }
        for (Direction dir : Direction.values()) {
            BlockPos p = broken.relative(dir);
            if (!level.hasChunkAt(p)) {
                continue;
            }
            BlockState state = level.getBlockState(p);
            if (state.isAir()) {
                continue;
            }
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            JsonObject entry = new JsonObject();
            entry.add("pos", Json.intArray(p.getX(), p.getY(), p.getZ()));
            entry.addProperty("id", id);
            boolean ore = isOre(id);
            entry.addProperty("ore", ore);
            out.add(entry);
            if (ore) {
                JsonObject found = entry.deepCopy();
                found.addProperty("from", broken.toShortString());
                found.addProperty("jobId", jobId);
                found.addProperty("at", System.currentTimeMillis());
                add(found);
            }
        }
        return out;
    }

    private static synchronized void add(JsonObject o) {
        total++;
        FOUND.addLast(o);
        while (FOUND.size() > MAX) {
            FOUND.removeFirst();
        }
        EventJournal.push("ore_found", o.deepCopy());
    }

    public static synchronized JsonArray recent(int limit) {
        JsonArray a = new JsonArray();
        int skip = Math.max(0, FOUND.size() - Math.max(1, limit));
        int i = 0;
        for (JsonObject o : FOUND) {
            if (i++ >= skip) {
                a.add(o.deepCopy());
            }
        }
        return a;
    }

    public static synchronized int total() {
        return total;
    }

    public static synchronized void clear() {
        FOUND.clear();
        total = 0;
    }
}
