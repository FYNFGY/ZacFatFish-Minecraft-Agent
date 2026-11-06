package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Ghost-block / client-server desync detector.
 *
 * <p>Root cause (Ph4 P0-1): a client-side block removal without a server round trip desynchronises
 * the two worlds. The client renders air while the server still has the block, so the server rejects
 * the player's movement and pushes them back - the player "sticks to an invisible object". The
 * vanilla fix is to never mutate the level directly: all breaking goes through
 * {@code startDestroyBlock}/{@code continueDestroyBlock}, which is prediction-tracked and reverted
 * by the server ack when the server disagrees.</p>
 *
 * <p>This class watches for the three observable symptoms:</p>
 * <ul>
 *   <li>{@code position_jump} - the server teleported the player backwards (&gt; 1 block in one tick,
 *       horizontally) while a job was walking. That is how a rejected move looks from the client.</li>
 *   <li>{@code mined_revert} - a block that the client saw break reappears (the prediction was
 *       reverted by the server, i.e. the break was rejected).</li>
 *   <li>{@code invisible_wall} - the player is pressed against something while all 27 cells around
 *       them are air and no entity touches them (the classic "invisible collider").</li>
 * </ul>
 */
public final class Ghosts {
    private static final int MAX_EVENTS = 120;
    private static final long DEDUPE_MS = 750;
    private static final Deque<JsonObject> EVENTS = new ArrayDeque<>();

    private static double lastX;
    private static double lastY;
    private static double lastZ;
    private static boolean haveLast;
    private static String lastDimension = "";
    private static int total;
    private static long lastAt;
    private static String lastKey = "";
    private static int suppressJumps;

    private Ghosts() {
    }

    /** Called once per client tick from BridgeRuntime.tick(). */
    public static synchronized void tick(LocalPlayer player) {
        if (player == null || player.level() == null) {
            haveLast = false;
            return;
        }
        String dimension = player.level().dimension().location().toString();
        double dx = player.getX() - lastX;
        double dz = player.getZ() - lastZ;
        if (haveLast && dimension.equals(lastDimension)) {
            double horizontal = Math.hypot(dx, dz);
            if (horizontal > 1.0 && horizontal < 128.0) {
                if (suppressJumps > 0) {
                    // A /tp from the test harness is not a desync: skip the next report.
                    suppressJumps--;
                } else {
                    JsonObject o = event("position_jump", player.blockPosition());
                    o.addProperty("distance", Json.round(horizontal, 2));
                    o.addProperty("teleport", horizontal > 8.0);
                    o.add("from", Json.doubleArray(lastX, lastY, lastZ));
                    o.add("to", Json.doubleArray(player.getX(), player.getY(), player.getZ()));
                    add(o);
                }
            }
        }
        lastX = player.getX();
        lastY = player.getY();
        lastZ = player.getZ();
        lastDimension = dimension;
        haveLast = true;
    }

    /** Called by the command tool before a /tp: the next big position jump is expected. */
    public static synchronized void expectTeleport() {
        suppressJumps = Math.max(suppressJumps, 2);
    }

    /** Records a ghost/sync event; deduplicated by kind+position within {@link #DEDUPE_MS}. */
    public static synchronized void report(String kind, BlockPos pos, String detail) {        JsonObject o = event(kind, pos);
        if (detail != null) {
            o.addProperty("detail", detail);
        }
        add(o);
    }

    private static JsonObject event(String kind, BlockPos pos) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        o.add("pos", Json.intArray(pos.getX(), pos.getY(), pos.getZ()));
        o.addProperty("at", System.currentTimeMillis());
        o.addProperty("gameTime", GameTime.now());
        LocalPlayer p = net.minecraft.client.Minecraft.getInstance().player;
        if (p != null) {
            o.add("player", Json.doubleArray(p.getX(), p.getY(), p.getZ()));
        }
        return o;
    }

    private static void add(JsonObject o) {
        String key = o.get("kind").getAsString() + "|" + o.get("pos").toString();
        long now = System.currentTimeMillis();
        if (key.equals(lastKey) && now - lastAt < DEDUPE_MS) {
            return;
        }
        lastKey = key;
        lastAt = now;
        total++;
        EVENTS.addLast(o);
        while (EVENTS.size() > MAX_EVENTS) {
            EVENTS.removeFirst();
        }
        EventJournal.push("ghost_block", o.deepCopy());
    }

    public static synchronized JsonArray recent(int limit) {
        JsonArray a = new JsonArray();
        int skip = Math.max(0, EVENTS.size() - Math.max(1, limit));
        int i = 0;
        for (JsonObject o : EVENTS) {
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
        EVENTS.clear();
        total = 0;
    }

    /**
     * Detector used by movement jobs when they are stuck: the player reports a horizontal collision
     * while every cell of the 3x3x3 bubble around the feet is air and no entity touches them. In a
     * consistent world that is impossible, so it means the collision comes from a stale server state.
     *
     * @return the feet position when the signature matches, otherwise null
     */
    public static BlockPos checkStuck(LocalPlayer player, ClientLevel level) {
        if (player == null || level == null || !player.horizontalCollision) {
            return null;
        }
        if (player.getDeltaMovement().horizontalDistanceSqr() > 0.0025) {
            return null;
        }
        BlockPos feet = player.blockPosition();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    BlockPos p = feet.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(p);
                    if (!state.isAir()) {
                        return null;
                    }
                }
            }
        }
        AABB box = player.getBoundingBox().inflate(0.35);
        for (Entity e : level.getEntities(player, box, e -> e != player && e.isAlive() && !e.isSpectator())) {
            if (e.getBoundingBox().intersects(box)) {
                return null;
            }
        }
        return feet;
    }

    /** Small helper so events carry the game time even when called outside a tick. */
    static final class GameTime {
        private GameTime() {
        }

        static long now() {
            ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
            return level == null ? 0L : level.getGameTime();
        }
    }
}
