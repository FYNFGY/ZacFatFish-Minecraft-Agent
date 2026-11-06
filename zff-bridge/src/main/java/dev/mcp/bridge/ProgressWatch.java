package dev.mcp.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;

/**
 * Movement-progress watchdog (Ph4 P1-8).
 *
 * <p>Every movement/dig job ticks this every client tick. When the player does not move at least
 * {@link #DEFAULT_MIN_DISTANCE} blocks within the window (60 ticks = 3 s by default) - and the job
 * did not complete any unit of work in the meantime (a mined block, a stair step, ...) - the job
 * fails with {@code stalled} instead of waiting for its timeout. A {@code job_stalled} event is
 * pushed to the {@link EventJournal} first, so an agent can react while the job is still running.</p>
 */
public final class ProgressWatch {
    /** Default window: 120 ticks (6 s) without one block of movement. */
    public static final int DEFAULT_WINDOW = 120;
    public static final double DEFAULT_MIN_DISTANCE = 1.0;

    private final String label;
    private final int windowTicks;
    private final double minDistance;
    private double anchorX;
    private double anchorY;
    private double anchorZ;
    private double best;
    private int ticks;
    private long token;
    private boolean initialised;

    public ProgressWatch(String label) {
        this(label, DEFAULT_WINDOW, DEFAULT_MIN_DISTANCE);
    }

    public ProgressWatch(String label, int windowTicks, double minDistance) {
        this.label = label;
        this.windowTicks = Math.max(10, windowTicks);
        this.minDistance = Math.max(0.1, minDistance);
    }

    public void reset(LocalPlayer player) {
        reset(player == null ? 0 : player.getX(), player == null ? 0 : player.getY(),
                player == null ? 0 : player.getZ(), token);
    }

    private void reset(double x, double y, double z, long progressToken) {
        anchorX = x;
        anchorY = y;
        anchorZ = z;
        token = progressToken;
        best = 0.0;
        ticks = 0;
        initialised = true;
    }

    /**
     * @param progressToken monotonically increasing counter of completed work (blocks mined, steps
     *                      climbed...). A change counts as progress and restarts the window.
     * @return true when the job is stalled and must fail
     */
    public boolean tick(LocalPlayer player, long progressToken) {
        if (player == null) {
            return false;
        }
        if (!initialised) {
            reset(player.getX(), player.getY(), player.getZ(), progressToken);
            return false;
        }
        ticks++;
        if (progressToken != token) {
            reset(player.getX(), player.getY(), player.getZ(), progressToken);
            return false;
        }
        double dx = player.getX() - anchorX;
        double dy = player.getY() - anchorY;
        double dz = player.getZ() - anchorZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance > best) {
            best = distance;
        }
        if (best >= minDistance) {
            reset(player.getX(), player.getY(), player.getZ(), progressToken);
            return false;
        }
        if (ticks < windowTicks) {
            return false;
        }
        return true;
    }

    public double bestDistance() {
        return Json.round(best, 2);
    }

    public int windowTicks() {
        return windowTicks;
    }

    public String label() {
        return label;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("windowTicks", windowTicks);
        o.addProperty("minDistance", minDistance);
        o.addProperty("bestDistanceInWindow", Json.round(best, 2));
        o.addProperty("ticksInWindow", ticks);
        return o;
    }

    /** Pushes the stall event before the job fails, so wait_events sees it. */
    public void reportStall(String jobId) {
        JsonObject o = new JsonObject();
        o.addProperty("jobId", jobId);
        o.addProperty("tool", label);
        o.addProperty("windowTicks", windowTicks);
        o.addProperty("minDistance", minDistance);
        o.addProperty("bestDistance", Json.round(best, 2));
        net.minecraft.client.player.LocalPlayer p = net.minecraft.client.Minecraft.getInstance().player;
        if (p != null) {
            o.add("player", Json.doubleArray(p.getX(), p.getY(), p.getZ()));
            o.add("block", Json.intArray(p.blockPosition().getX(), p.blockPosition().getY(),
                    p.blockPosition().getZ()));
        }
        EventJournal.push("job_stalled", o);
    }

    public void restart() {
        initialised = false;
        ticks = 0;
        best = 0.0;
    }
}
