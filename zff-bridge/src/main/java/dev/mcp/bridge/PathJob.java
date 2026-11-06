package dev.mcp.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * Baritone path job with decidable distanceLeft/currentGoal/eta metrics.
 *
 * <p>Ph4 changes: no client-side {@code destroyBlock} (that was the ghost-block source), vegetation
 * is cleared only when the path is actually stuck and through {@link BlockMiner}, and a 60-tick
 * progress watchdog reports {@code stalled} instead of hanging (P0-1, P1-8, P1-9).</p>
 */
public final class PathJob implements Jobs.TickTask {
    private int clearTick;
    private final int x;
    private final int y;
    private final int z;
    private final int range;
    private double startDistance;
    private int idleTicks;
    private int stuckTicks;
    private double lastX;
    private double lastZ;
    private BlockMiner plantMiner;
    private int plantCooldown;
    private int cleared;
    private final ProgressWatch watch = new ProgressWatch("path_to");
    private final Boolean allowBreakOverride;
    private final Boolean allowPlaceOverride;

    public PathJob(int x, int y, int z, int range) {
        this(x, y, z, range, null, null);
    }

    public PathJob(int x, int y, int z, int range, Boolean allowBreak, Boolean allowPlace) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.range = range;
        this.allowBreakOverride = allowBreak;
        this.allowPlaceOverride = allowPlace;
    }

    @Override
    public void start(Jobs.Job job) {
        if (!BaritoneControl.isLoaded()) {
            throw Errors.unsupported("Baritone (mod id baritoe) is not installed");
        }
        BaritoneControl.configureForPathing();
        if (allowBreakOverride != null || allowPlaceOverride != null) {
            BaritoneControl.setMovementPolicy(
                    allowBreakOverride != null ? allowBreakOverride : PathPolicy.breakAllowed(),
                    allowPlaceOverride != null ? allowPlaceOverride : PathPolicy.placeAllowed());
        }
        BaritoneControl.start(x, y, z, range);
        BaritoneControl.steer(x, y, z);
        startDistance = BaritoneControl.distanceTo(x, y, z);
        if (Double.isNaN(startDistance)) {
            throw Errors.notLoaded("the player is not in a world");
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            lastX = player.getX();
            lastZ = player.getZ();
            watch.reset(player);
        }
        job.metric("distanceLeft", Json.round(Math.max(0, startDistance - range), 2));
        job.metric("currentGoal", BaritoneControl.goalJson(x, y, z, range));
        job.metric("startDistance", Json.round(startDistance, 2));
        job.metric("pathing", true);
    }

    private void restartPath() {
        BaritoneControl.start(x, y, z, range);
        BaritoneControl.steer(x, y, z);
    }

    @Override
    public void tick(Jobs.Job job) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            job.fail(Errors.DISCONNECTED, "the player left the world");
            return;
        }
        if (mc.isPaused()) {
            // Singleplayer freezes the integrated server on the pause screen; Baritone stops
            // reporting progress there, which must not be treated as a stalled path.
            idleTicks = 0;
            stuckTicks = 0;
            watch.reset(player);
            return;
        }
        if (plantMiner != null) {
            BlockMiner.Outcome outcome = plantMiner.tick(player, mc.level);
            if (outcome == BlockMiner.Outcome.WORKING) {
                return;
            }
            if (outcome == BlockMiner.Outcome.DONE || outcome == BlockMiner.Outcome.ALREADY_DONE) {
                cleared++;
                job.metric("cleared", cleared);
            } else {
                job.metric("clearFailed", plantMiner.code());
            }
            plantMiner = null;
            plantCooldown = 8;
            stuckTicks = 0;
            // The break cancelled Baritone; ask for the path again from the new position.
            restartPath();
            return;
        }
        if (plantCooldown > 0) {
            plantCooldown--;
        }
        double distance = BaritoneControl.distanceTo(x, y, z);
        if (Double.isNaN(distance)) {
            job.fail(Errors.DISCONNECTED, "player position is unavailable");
            return;
        }
        BaritoneControl.steer(x, y, z);
        double moved = Math.hypot(player.getX() - lastX, player.getZ() - lastZ);
        lastX = player.getX();
        lastZ = player.getZ();
        if (moved < 0.05) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        if (watch.tick(player, 0)) {
            watch.reportStall(job.id);
            job.metric("watchdog", watch.toJson());
            BaritoneControl.cancel();
            job.fail(Errors.STALLED, "path_to moved less than 1 block in " + watch.windowTicks()
                    + " ticks at " + player.blockPosition().toShortString()
                    + " (distanceLeft " + Json.round(distance, 2) + ")");
            return;
        }
        if (clearTick++ % 12 == 0) {
            BlockPos ghost = Ghosts.checkStuck(player, mc.level);
            if (ghost != null) {
                Ghosts.report("invisible_wall", ghost, "path_to is stuck inside an all-air bubble");
                BaritoneControl.cancel();
                job.fail(Errors.GHOST_BLOCK, "ghost collider at " + ghost.toShortString()
                        + " (all 27 cells are air but the player is blocked)");
                return;
            }
        }
        double left = Math.max(0, distance - range);
        job.metric("distanceLeft", Json.round(left, 2));
        job.metric("currentGoal", BaritoneControl.goalJson(x, y, z, range));
        if (distance <= range + 0.8) {
            BaritoneControl.cancel();
            BaritoneControl.setMovementPolicy(PathPolicy.breakAllowed(), PathPolicy.placeAllowed());
            // Finish level instead of leaving the camera tilted from the last steering tick.
            player.setXRot(0.0F);
            JsonObject result = new JsonObject();
            result.add("pos", Json.intArray(x, y, z));
            result.addProperty("distanceLeft", Json.round(left, 2));
            result.addProperty("elapsedMs", System.currentTimeMillis() - job.createdAt);
            result.addProperty("cleared", cleared);
            job.succeed(result);
            return;
        }
        BaritoneControl.Status status = BaritoneControl.status();
        if (status.error != null) {
            job.fail(Errors.UNSUPPORTED, "Baritone error: " + status.error);
            return;
        }
        job.metric("pathing", status.pathing);
        job.metric("active", status.active);
        job.metric("hasPath", status.hasPath);
        if (status.etaTicks != null) {
            job.metric("etaTicks", status.etaTicks);
        }
        if (status.pathLength != null) {
            job.metric("pathLength", status.pathLength);
        }
        if (status.pathIndex != null) {
            job.metric("pathIndex", status.pathIndex);
        }
        if (!status.pathing && !status.active) {
            idleTicks++;
            if (idleTicks > 40) {
                job.fail(Errors.INTERNAL, "Baritone stopped before reaching the goal");
                return;
            }
        } else {
            idleTicks = 0;
        }
        // Vegetation only when the path is really stuck and the blocking cell is a plant (P1-9).
        if (stuckTicks >= 6 && plantCooldown == 0) {
            float yaw = player.getYRot();
            BlockPos plant = blockingPlant(mc, player, yaw);
            if (plant != null) {
                BaritoneControl.cancel();
                plantMiner = new BlockMiner(plant, false);
                job.metric("clearing", plant.toShortString());
                plantMiner.tick(player, mc.level);
                return;
            }
        }
        double denominator = Math.max(1.0, startDistance - range);
        double done = Math.max(0, startDistance - distance);
        int pct = (int) Math.round(100.0 * done / denominator);
        job.progress(MathUtil.clamp(pct, 0, 99));
    }

    private static BlockPos blockingPlant(Minecraft mc, LocalPlayer player, float yaw) {
        int dirX = (int) Math.round(-Math.sin(Math.toRadians(yaw)));
        int dirZ = (int) Math.round(Math.cos(Math.toRadians(yaw)));
        BlockPos feet = player.blockPosition();
        BlockPos front = feet.offset(dirX, 0, dirZ);
        BlockPos[] cells = {feet, feet.above(), front, front.above()};
        for (BlockPos p : cells) {
            if (WalkToJob.clearable(mc.level, p)) {
                return p;
            }
        }
        return null;
    }

    @Override
    public void cancel(Jobs.Job job) {
        BaritoneControl.cancel();
        BaritoneControl.setMovementPolicy(PathPolicy.breakAllowed(), PathPolicy.placeAllowed());
    }
}
