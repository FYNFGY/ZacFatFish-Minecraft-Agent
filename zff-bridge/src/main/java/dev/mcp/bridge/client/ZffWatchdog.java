package dev.mcp.bridge.client;

import com.google.gson.JsonObject;
import dev.mcp.bridge.BridgeRuntime;
import dev.mcp.bridge.EventJournal;
import dev.mcp.bridge.Jobs;
import dev.mcp.bridge.MathUtil;
import dev.mcp.bridge.ZffConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The job watchdog, provided by the mod itself (Ph5 T3): a Java client-tick sampler instead of the
 * Node 500 ms poll.
 *
 * <p>Every {@code reportIntervalMs} (default 500 ms) the player position is sampled once, and for every
 * <b>running job</b> the plugin compares it with the last position that counted as movement:</p>
 * <ul>
 *   <li>{@code tower}: |dy| &lt; {@code towerDeltaY} for {@code watchdogMs} → {@code stuck{mode:tower}}</li>
 *   <li>{@code walk/path}: |dxz| &lt; {@code walkDeltaXZ} for {@code watchdogMs} → {@code stuck{mode:walk}}</li>
 *   <li>{@code dig/mine}: no downward progress and no horizontal progress → {@code stuck{mode:dig}}</li>
 * </ul>
 *
 * <p>A stall is published to the {@link EventJournal} (so the MCP side reads it with
 * {@code wait_events}) and counted in the job's {@code metrics.stuck}. When the job moves again the
 * detector re-arms, which lets a long job report several stalls.</p>
 */
public final class ZffWatchdog {
    private static final Map<String, Watch> WATCHES = new HashMap<>();
    private static long lastSampleAt;
    private static long events;

    private ZffWatchdog() {
    }

    private static final class Watch {
        final String mode;
        double x;
        double y;
        double z;
        long movedAt;
        boolean reported;
        int stuckCount;

        Watch(String mode, double x, double y, double z, long now) {
            this.mode = mode;
            this.x = x;
            this.y = y;
            this.z = z;
            this.movedAt = now;
        }
    }

    /** Called from {@code ClientTickEvent.Post}; cheap when the sample interval has not elapsed. */
    public static void tick() {
        if (!ZffConfig.watchdogInMod) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSampleAt < Math.max(100, ZffConfig.reportIntervalMs)) {
            return;
        }
        lastSampleAt = now;
        try {
            sample(now);
        } catch (Throwable t) {
            // A watchdog must never break the client tick loop.
            System.out.println("[zff] watchdog sample failed: " + t);
        }
    }

    private static void sample(long now) {
        LocalPlayer player = Minecraft.getInstance().player;
        Jobs jobs = BridgeRuntime.jobs;
        if (player == null || jobs == null) {
            WATCHES.clear();
            return;
        }
        Set<String> live = new HashSet<>();
        for (Jobs.Job job : jobs.active()) {
            String mode = modeOf(job.tool);
            if (mode == null) {
                continue;
            }
            live.add(job.id);
            Watch watch = WATCHES.get(job.id);
            if (watch == null) {
                watch = new Watch(mode, player.getX(), player.getY(), player.getZ(), now);
                WATCHES.put(job.id, watch);
                continue;
            }
            double dxz = Math.hypot(player.getX() - watch.x, player.getZ() - watch.z);
            double dy = player.getY() - watch.y;
            boolean moved = switch (watch.mode) {
                case "tower" -> Math.abs(dy) >= ZffConfig.towerDeltaY;
                case "dig" -> Math.abs(dy) >= 0.5 || dxz >= ZffConfig.walkDeltaXZ;
                default -> dxz >= ZffConfig.walkDeltaXZ;
            };
            if (moved) {
                watch.x = player.getX();
                watch.y = player.getY();
                watch.z = player.getZ();
                watch.movedAt = now;
                watch.reported = false;
                continue;
            }
            // The sampler can only observe movement once per reportInterval, so the measured window is
            // watchdogMs..watchdogMs+interval. Bias the trigger by half an interval so the *reported*
            // stall still arrives inside the 3 s + 0.5 s budget the requirement allows.
            long window = now - watch.movedAt;
            long threshold = Math.max(500L, ZffConfig.watchdogMs - Math.max(0, ZffConfig.reportIntervalMs) / 2L);
            if (!watch.reported && window >= threshold) {
                watch.reported = true;
                watch.stuckCount++;
                publish(job, watch, dxz, dy, window, player);
            }
        }
        WATCHES.keySet().retainAll(live);
    }

    private static void publish(Jobs.Job job, Watch watch, double dxz, double dy, long window, LocalPlayer player) {
        events++;
        try {
            job.metric("stuck", watch.stuckCount);
            job.metric("stuckMode", watch.mode);
            job.metric("stuckWindowMs", window);
        } catch (Throwable ignored) {
            // metrics are best effort
        }
        JsonObject event = new JsonObject();
        event.addProperty("taskId", job.id);
        event.addProperty("jobId", job.id);
        event.addProperty("tool", job.tool);
        event.addProperty("mode", watch.mode);
        event.addProperty("x", MathUtil.round2(player.getX()));
        event.addProperty("y", MathUtil.round2(player.getY()));
        event.addProperty("z", MathUtil.round2(player.getZ()));
        event.addProperty("dxz", MathUtil.round2(dxz));
        event.addProperty("dy", MathUtil.round2(dy));
        event.addProperty("windowMs", window);
        event.addProperty("count", watch.stuckCount);
        event.addProperty("wd", ZffConfig.watchdogMs);
        event.addProperty("triggerMs", Math.max(500L, ZffConfig.watchdogMs - ZffConfig.reportIntervalMs / 2L));
        event.addProperty("sampleMs", ZffConfig.reportIntervalMs);
        event.addProperty("source", "mod");
        event.addProperty("suggestion", switch (watch.mode) {
            case "tower" -> "y 不变：头顶有方块或没有可放置方块";
            case "dig" -> "y/xz 都不变：目标方块不可挖、工具不对或被挡住";
            default -> "xz 不变：前方被挡/植物/水/掉落物在头顶或脚下";
        });
        EventJournal.push("stuck", event);
    }

    /** tool -> watchdog mode; null means "this job has no meaningful movement metric". */
    private static String modeOf(String tool) {
        if (tool == null) {
            return null;
        }
        return switch (tool) {
            case "tower" -> "tower";
            case "walk_to", "path_to", "walk", "path_open", "boat_sail", "eye_throw" -> "walk";
            case "dig_shaft", "dig_tunnel", "dig_sealed_shaft", "branch_mine", "mine_block" -> "dig";
            default -> null;
        };
    }

    /** Number of stall events published since the client started (reported by bridge_info). */
    public static long eventCount() {
        return events;
    }
}
