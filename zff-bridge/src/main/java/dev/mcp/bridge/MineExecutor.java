package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.List;

/** Mines a list of blocks with per-block verification and a hard stop on the first failure. */
public final class MineExecutor implements Jobs.TickTask {
    private final List<BlockPos> targets;
    private final JsonArray results = new JsonArray();
    private final java.util.Map<String, Integer> minedById = new java.util.TreeMap<>();
    private int index;
    private int mined;
    private int failed;
    private BlockMiner current;
    private int approachTicks;
    private double lastApproachDist = Double.MAX_VALUE;

    public MineExecutor(List<BlockPos> targets) {
        this.targets = targets;
    }

    @Override
    public void start(Jobs.Job job) {
        job.metric("total", targets.size());
        job.metric("blocksMined", 0);
        job.metric("failed", 0);
        // Only one job may steer the view: cancel walkers so the crosshair stays on the target.
        Jobs manager = BridgeRuntime.jobs;
        if (manager != null) {
            manager.cancelTool("path_to");
            manager.cancelTool("walk");
            manager.cancelTool("walk_to");
        }
        BaritoneControl.cancel();
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
        if (current == null) {
            if (index >= targets.size()) {
                finish(job);
                return;
            }
            BlockPos target = targets.get(index);
            // 2026-11-02: mine_block used to fail with out_of_reach for every block >4.5.
            // Walk toward the target first; once inside reach, mine normally.
            if (!level.getBlockState(target).isAir() && !withinReach(player, target)) {
                approach(job, player, target);
                return;
            }
            approachTicks = 0;
            lastApproachDist = Double.MAX_VALUE;
            current = new BlockMiner(target);
        }
        BlockMiner.Outcome outcome = current.tick(player, level);
        switch (outcome) {
            case DONE, ALREADY_DONE -> {
                if (current.brokenId() != null) {
                    minedById.merge(current.brokenId(), 1, Integer::sum);
                    job.metric("ores", Mining.oreSummary(minedById));
                }
                job.metric("lastProbe", OreProbe.record(level, current.pos(), job.id));
                mined++;
                results.add(Mining.result(current.pos(), "ok", null, current.ticks()));
                index++;
                current = null;
                job.metric("blocksMined", mined);
                job.progress(progress());
            }
            case FAILED -> {
                failed++;
                results.add(Mining.result(current.pos(), "failed", current.code(), current.ticks()));
                job.metric("failed", failed);
                job.metric("results", results);
                job.fail(current.code(), current.detail());
            }
            case WORKING -> {
                job.metric("target", Json.intArray(current.pos().getX(), current.pos().getY(), current.pos().getZ()));
                job.progress(progress());
            }
        }
    }

    private static boolean withinReach(LocalPlayer player, BlockPos pos) {
        double dx = pos.getX() + 0.5 - player.getX();
        double dy = pos.getY() + 0.5 - player.getEyeY();
        double dz = pos.getZ() + 0.5 - player.getZ();
        return dx * dx + dy * dy + dz * dz <= 4.2 * 4.2;
    }

    /** Direct short approach; hills are handled with a jump, walls end in a clean failure. */
    private void approach(Jobs.Job job, LocalPlayer player, BlockPos target) {
        double dx = target.getX() + 0.5 - player.getX();
        double dz = target.getZ() + 0.5 - player.getZ();
        double horizontal = Math.hypot(dx, dz);
        double dy = target.getY() + 0.5 - player.getEyeY();
        if (horizontal < 0.05) {
            job.fail(Errors.OUT_OF_REACH, "target " + target.toShortString()
                    + " is above/below and too far to walk to; use a path/tower first");
            return;
        }
        if (approachTicks++ == 0) lastApproachDist = horizontal;
        if (horizontal < lastApproachDist - 0.05) {
            lastApproachDist = horizontal;
            approachTicks = 1;
        }
        if (approachTicks > 100) {
            job.fail(Errors.NO_PROGRESS, "could not approach " + target.toShortString()
                    + " (still " + MathUtil.round2(horizontal) + " blocks away; blocked by terrain?)");
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        double speed = 0.30;
        player.setDeltaMovement(dx / horizontal * speed, player.getDeltaMovement().y, dz / horizontal * speed);
        player.hurtMarked = true;
        if (player.onGround() && (player.horizontalCollision
                || (dy > 0.4 && horizontal < 1.5))) {
            player.jumpFromGround();
        }
        job.metric("approach", target.toShortString());
        job.metric("distance", MathUtil.round2(horizontal));
    }

    private int progress() {
        return targets.isEmpty() ? 100 : (int) Math.round(100.0 * index / targets.size());
    }

    private void finish(Jobs.Job job) {
        JsonObject r = new JsonObject();
        r.addProperty("blocksMined", mined);
        r.addProperty("total", targets.size());
        r.addProperty("failed", failed);
        r.add("results", results);
        r.add("minedById", Mining.idSummary(minedById));
        r.add("ores", Mining.oreSummary(minedById));
        job.metric("results", results);
        job.metric("ores", Mining.oreSummary(minedById));
        job.succeed(r);
    }
}
