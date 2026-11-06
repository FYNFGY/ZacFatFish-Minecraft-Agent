package dev.mcp.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Short-range direct walker: moves toward (x,z), hops one-block steps, and fails as soon as it stops
 * making progress. This is the movement primitive for the step-by-step (look-then-move) planner; it
 * does not use Baritone, so it never "bounces in place" while a long path fails.
 *
 * <p>Ph4 changes:</p>
 * <ul>
 *   <li>vegetation is cleared through {@link BlockMiner} (prediction-tracked), never through
 *   {@code gameMode.destroyBlock}; only real obstacles are cleared, and only after the player has
 *   been stuck for a few ticks (P0-1 + P1-9);</li>
 *   <li>a 60-tick progress watchdog fails the job with {@code stalled} (P1-8);</li>
 *   <li>a stuck player inside an all-air bubble is reported as {@code ghost_block} (P0-1).</li>
 * </ul>
 */
public final class WalkToJob implements Jobs.TickTask {
    private final double tx;
    private final double tz;
    private final double range;
    private final int maxTicks;
    private final boolean allowBreak;
    private final ProgressWatch watch = new ProgressWatch("walk_to");
    private int ticks;
    private int stuckTicks;
    private int jumpCooldown;
    private double lastX;
    private double lastZ;

    private BlockMiner plantMiner;
    private int plantCooldown;
    private int cleared;

    public WalkToJob(double tx, double tz, double range, int maxTicks) {
        this(tx, tz, range, maxTicks, true);
    }

    public WalkToJob(double tx, double tz, double range, int maxTicks, boolean allowBreak) {
        this.tx = tx;
        this.tz = tz;
        this.range = Math.max(0.5, range);
        this.maxTicks = MathUtil.clamp(maxTicks, 10, 1200);
        this.allowBreak = allowBreak;
    }

    private boolean done;

    public boolean done() {
        return done;
    }

    @Override
    public void start(Jobs.Job job) {
        job.metric("target", Json.doubleArray(tx, tz));
        Jobs manager = BridgeRuntime.jobs;
        if (manager != null) {
            manager.cancelTool("path_to");
        }
        BaritoneControl.cancel();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            lastX = player.getX();
            lastZ = player.getZ();
            watch.reset(player);
        }
    }

    /** True when the block is vegetation that can actually stop a player (leaves, berries, bushes). */
    public static boolean clearable(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        // Never treat building blocks as vegetation: "grass_block" contains "grass" and deleting it
        // client-side is exactly what created the invisible colliders (Ph4 P0-1).
        if (id.endsWith("_block") && !id.contains("leaves")) {
            return false;
        }
        boolean bush = id.contains("bush") || id.contains("berry");
        boolean vegetation = id.contains("leaves") || id.contains("vine") || bush
                || id.contains("grass") || id.contains("flower") || id.contains("fern")
                || id.contains("sapling") || id.contains("bamboo") || id.contains("sugar_cane")
                || id.contains("cactus") || id.contains("dead_bush") || id.contains("mushroom")
                || id.contains("lily_pad") || id.contains("dripleaf") || id.contains("azalea")
                || id.contains("moss") || id.contains("seagrass") || id.contains("kelp")
                || id.contains("roots") || id.contains("sprout") || id.contains("lichen");
        if (!vegetation) {
            return false;
        }
        return bush || id.contains("bamboo") || id.contains("cactus") || id.contains("sugar_cane")
                || !state.getCollisionShape(level, pos).isEmpty();
    }

    /** The plant cell that blocks the walk direction (3 wide, 2 high), or null. */
    private static BlockPos blockingPlant(ClientLevel level, LocalPlayer player, float yaw) {
        int dirX = (int) Math.round(-Math.sin(Math.toRadians(yaw)));
        int dirZ = (int) Math.round(Math.cos(Math.toRadians(yaw)));
        BlockPos feet = player.blockPosition();
        // Straight ahead first, then the diagonals: a 1-wide scan misses berry bushes and bamboo
        // clumps that the player rubs against while sliding along them.
        BlockPos[] probes = {
                feet.offset(dirX, 0, dirZ), feet.offset(dirX, 1, dirZ),
                feet, feet.above(),
                feet.offset(dirX, 0, dirZ + (dirX != 0 ? 1 : 0)),
                feet.offset(dirX + (dirZ != 0 ? 1 : 0), 0, dirZ),
                feet.offset(dirX, 0, dirZ - (dirX != 0 ? 1 : 0)),
                feet.offset(dirX - (dirZ != 0 ? 1 : 0), 0, dirZ),
        };
        for (BlockPos p : probes) {
            if (clearable(level, p)) {
                return p;
            }
        }
        return null;
    }

    @Override
    public void tick(Jobs.Job job) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            job.fail(Errors.DISCONNECTED, "player left the world");
            return;
        }
        // Finish a clearing break before moving on: the vanilla destroy progress is reset when the
        // aim moves away, so the plant must be broken in a short dedicated phase.
        if (plantMiner != null) {
            BlockMiner.Outcome outcome = plantMiner.tick(player, level);
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
            plantCooldown = 6;
            return;
        }
        if (plantCooldown > 0) {
            plantCooldown--;
        }
        double dx = tx - player.getX();
        double dz = tz - player.getZ();
        double dist = Math.hypot(dx, dz);
        if (dist <= range) {
            JsonObject r = new JsonObject();
            r.addProperty("x", MathUtil.round2(player.getX()));
            r.addProperty("y", MathUtil.round2(player.getY()));
            r.addProperty("z", MathUtil.round2(player.getZ()));
            r.addProperty("distance", MathUtil.round2(dist));
            r.addProperty("ticks", ticks);
            r.addProperty("cleared", cleared);
            done = true;
            job.succeed(r);
            return;
        }
        if (ticks++ > maxTicks) {
            job.fail(Errors.TIMEOUT, "walk_to did not arrive in " + maxTicks + " ticks (distance " + MathUtil.round2(dist) + ")");
            return;
        }
        if (watch.tick(player, 0)) {
            watch.reportStall(job.id);
            job.metric("watchdog", watch.toJson());
            job.fail(Errors.STALLED, "walk_to moved less than " + watch.windowTicks() / 20.0
                    + "s worth of blocks in " + watch.windowTicks() + " ticks at "
                    + player.blockPosition().toShortString() + " (distance " + MathUtil.round2(dist) + ")");
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        if (jumpCooldown > 0) {
            jumpCooldown--;
        }
        boolean headBlocked = !level.getBlockState(player.blockPosition().above(2)).isAir()
                && !level.getBlockState(player.blockPosition().above(2)).canBeReplaced();
        if (player.onGround() && player.horizontalCollision) {
            if (stuckTicks > 0 && stuckTicks % 12 == 0) {
                BlockPos ghost = Ghosts.checkStuck(player, level);
                if (ghost != null) {
                    Ghosts.report("invisible_wall", ghost, "walk_to is stuck inside an all-air bubble");
                    job.fail(Errors.GHOST_BLOCK, "ghost collider at " + ghost.toShortString()
                            + " (all 27 cells are air but the player is blocked)");
                    return;
                }
            }
            // Vegetation: clear it only once the walker is actually stuck (P1-9), through the
            // prediction-tracked miner (P0-1).
            if (stuckTicks >= 4 && plantCooldown == 0 && allowBreak) {
                BlockPos plant = blockingPlant(level, player, yaw);
                if (plant != null) {
                    plantMiner = new BlockMiner(plant, false);
                    job.metric("clearing", plant.toShortString());
                    plantMiner.tick(player, level);
                    return;
                }
            }
            if (jumpCooldown == 0 && !headBlocked) {
                player.jumpFromGround();
                jumpCooldown = 6;
            }
        } else if (player.onGround() && stuckTicks > 12 && jumpCooldown == 0 && !headBlocked) {
            // Aligned with a 1-block step: no horizontal collision is reported, so hop anyway.
            player.jumpFromGround();
            jumpCooldown = 8;
        }
        if (headBlocked) job.metric("headBlocked", player.blockPosition().above(2).toShortString());
        double speed = 0.28;
        double dy = player.getDeltaMovement().y;
        player.setDeltaMovement(dx / dist * speed, dy, dz / dist * speed);
        player.hurtMarked = true;

        double moved = Math.hypot(player.getX() - lastX, player.getZ() - lastZ);
        if (moved > 0.25) {
            lastX = player.getX();
            lastZ = player.getZ();
            stuckTicks = 0;
        } else {
            stuckTicks++;
            if (stuckTicks > 60) {
                job.fail(Errors.NO_PROGRESS, "walk_to made no progress at "
                        + player.blockPosition().toShortString() + " (distance " + MathUtil.round2(dist) + ")");
            }
        }
        job.metric("distance", MathUtil.round2(dist));
        job.progress(Math.max(1, Math.min(99, (int) (100 - dist))));
    }
}
