package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * Solid-rock tunnel A -&gt; B (Ph4 P2-1 / CubeTest).
 *
 * <p>Digs a 1-wide, 2-high corridor along the straight line from {@code from} to {@code to}, one
 * block per horizontal step (the Y component is distributed over the steps so a step never changes
 * Y by more than one block; steeper lines are refused and must use the stairs primitive). Every
 * block goes through {@link BlockMiner}, so the tunnel is prediction-tracked and ghost free; every
 * block is probed for ores. A torch is placed every 8 blocks when torches are in the hotbar, and the
 * job can walk back to A through the new tunnel to prove it is traversable.</p>
 *
 * <p>Differences to the plan's A*: the straight line is deterministic, re-routing is not attempted
 * (water/lava/unbreakable stops the job with a reason and the agent re-plans), which is enough for
 * CubeTest and for mining corridors. The dynamic re-route is left for the next iteration.</p>
 */
public final class TunnelJob implements Jobs.TickTask {
    private final BlockPos from;
    private final BlockPos to;
    private final int maxBlocks;
    private final boolean placeTorches;
    private final boolean returnTrip;

    private final List<BlockPos> path = new ArrayList<>();
    private final List<BlockPos> pending = new ArrayList<>();
    private int index;
    private int pendingIndex;
    private BlockMiner miner;
    private int state; // 0 plan, 1 dig, 2 walk, 3 return walk, 4 done
    private int mined;
    private int torchesPlaced;
    private int walkTicks;
    private int stall;
    private int returnIndex = -1;
    private int startY;
    private boolean returning;
    private final ProgressWatch watch = new ProgressWatch("dig_tunnel", 100, 1.0);

    public TunnelJob(BlockPos from, BlockPos to, int maxBlocks, boolean torches, boolean returnTrip) {
        this.from = from;
        this.to = to;
        this.maxBlocks = MathUtil.clamp(maxBlocks, 8, 20_000);
        this.placeTorches = torches;
        this.returnTrip = returnTrip;
    }

    @Override
    public void start(Jobs.Job job) {
        job.metric("from", Json.intArray(from.getX(), from.getY(), from.getZ()));
        job.metric("to", Json.intArray(to.getX(), to.getY(), to.getZ()));
        job.metric("blocksMined", 0);
        job.metric("steps", 0);
        Jobs manager = BridgeRuntime.jobs;
        if (manager != null) {
            manager.cancelTool("path_to");
            manager.cancelTool("walk");
            manager.cancelTool("walk_to");
            manager.cancelTool("dig_shaft");
        }
        BaritoneControl.cancel();
    }

    private int buildPath(Jobs.Job job, LocalPlayer player) {
        BlockPos a = new BlockPos(from.getX(), from.getY(), from.getZ());
        BlockPos b = new BlockPos(to.getX(), to.getY(), to.getZ());
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        int dy = b.getY() - a.getY();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) {
            job.fail(Errors.BLOCKED, "from and to are the same column; use dig_shaft for vertical shafts");
            return -1;
        }
        if (Math.abs(dy) > steps) {
            job.fail(Errors.BLOCKED, "the line is too steep (" + Math.abs(dy) + " blocks of Y in " + steps
                    + " steps); use dig_shaft mode=up/stair first");
            return -1;
        }
        path.clear();
        path.add(a);
        for (int i = 1; i <= steps; i++) {
            int x = a.getX() + Math.round((float) dx * i / steps);
            int z = a.getZ() + Math.round((float) dz * i / steps);
            int y = a.getY() + Math.round((float) dy * i / steps);
            BlockPos p = new BlockPos(x, y, z);
            if (!p.equals(path.get(path.size() - 1))) {
                path.add(p);
            }
        }
        index = 1;
        startY = player.blockPosition().getY();
        job.metric("length", path.size() - 1);
        job.metric("dyPerStep", dy);
        return 0;
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
        if (path.isEmpty()) {
            if (buildPath(job, player) < 0) {
                return;
            }
        }
        if (mined > maxBlocks) {
            job.fail(Errors.BUDGET_EXCEEDED, "dug " + mined + " blocks, budget is " + maxBlocks);
            return;
        }
        if (returning) {
            tickWalk(job, player, level, true);
            return;
        }
        switch (state) {
            case 0 -> {
                if (index >= path.size()) {
                    job.metric("steps", path.size() - 1);
                    if (!returnTrip) {
                        finish(job, player, false);
                        return;
                    }
                    returning = true;
                    returnIndex = path.size() - 2;
                    state = 3;
                    job.metric("state", "return");
                    watch.restart();
                    waitWalk(player);
                    return;
                }
                BlockPos target = path.get(index);
                BlockPos previous = path.get(index - 1);
                BlockPos feet = player.blockPosition();
                if (feet.equals(target)) {
                    afterArrive(job, player, level, target);
                    index++;
                    return;
                }
                pending.clear();
                // Falling gravel/sand can refill the tunnel. Clear falling ceiling blocks at
                // target+2..+5 top-down before the head and feet cells.
                for (int dy = 5; dy >= 2; dy--) {
                    BlockPos p = target.above(dy);
                    if (isFalling(level.getBlockState(p))) pending.add(p);
                }
                pending.add(target.above());
                pending.add(target);
                if (target.getY() > previous.getY()) {
                    pending.add(target.above(2));
                    // The player also needs headroom in its *current* cell to start the jump; the
                    // old code only cleared the target cell and then reported "could not walk into
                    // step" when the ceiling above the player was solid (natural 1x2 tunnel).
                    BlockPos playerHead2 = feet.above(2);
                    if (!level.getBlockState(playerHead2).isAir()
                            && !level.getBlockState(playerHead2).canBeReplaced()
                            && !pending.contains(playerHead2)) {
                        pending.add(playerHead2);
                    }
                }
                // 2026-11-02: a tunnel must not walk into a target whose floor is missing; that was
                // how a "slope" test dropped the player several blocks below the path and then failed
                // with "could not walk into step".
                if (!DigUtil.solid(level, target.below())) {
                    job.fail(Errors.BLOCKED, "no solid floor under tunnel cell " + target.toShortString()
                            + " (would fall); stop and bridge/stairs first");
                    return;
                }
                for (BlockPos p : pending) {
                    if (DigUtil.fluid(level, p)) {
                        job.fail(Errors.WATER_RISK, "water/lava at " + p.toShortString() + " - stopped before flooding the tunnel");
                        return;
                    }
                    if (DigUtil.unbreakable(level, p)) {
                        job.fail(Errors.UNBREAKABLE, "unbreakable block at " + p.toShortString());
                        return;
                    }
                }
                pendingIndex = 0;
                miner = null;
                watch.restart();
                state = 1;
            }
            case 1 -> {
                if (pendingIndex >= pending.size()) {
                    state = 2;
                    walkTicks = 0;
                    return;
                }
                BlockPos p = pending.get(pendingIndex);
                if (miner == null) {
                    // Only skip already-air cells before a miner starts: while a miner is running the
                    // block is already predicted-air and must still reach DONE to be counted.
                    if (level.getBlockState(p).isAir()) {
                        pendingIndex++;
                        return;
                    }
                    miner = new BlockMiner(p, true);
                }
                BlockMiner.Outcome outcome = miner.tick(player, level);
                switch (outcome) {
                    case DONE, ALREADY_DONE -> {
                        if (miner.brokenId() != null) {
                            mined++;
                            job.metric("blocksMined", mined);
                            job.metric("lastProbe", OreProbe.record(level, p, job.id));
                        }
                        pendingIndex++;
                        miner = null;
                    }
                    case FAILED -> job.fail(miner.code(), miner.detail());
                    case WORKING -> {
                    }
                }
            }
            case 2 -> tickWalk(job, player, level, false);
            default -> job.fail(Errors.INTERNAL, "bad state " + state);
        }
    }

    private void waitWalk(LocalPlayer player) {
        walkTicks = 0;
        stall = 0;
    }

    /** Walks to the current target cell centre; on arrival advances the state machine. */
    private void tickWalk(Jobs.Job job, LocalPlayer player, ClientLevel level, boolean back) {
        BlockPos target = back ? path.get(Math.max(0, returnIndex)) : path.get(index);
        if (!back && (!level.getBlockState(target).isAir() || !level.getBlockState(target.above()).isAir())) {
            // Falling gravel refilled the tunnel after the mining phase; re-mine instead of walking
            // into it for the full watchdog window.
            pending.clear();
            miner = null;
            pendingIndex = 0;
            state = 0;
            return;
        }
        double tx = target.getX() + 0.5;
        double tz = target.getZ() + 0.5;
        double dx = tx - player.getX();
        double dz = tz - player.getZ();
        double dist = Math.hypot(dx, dz);
        double dyFeet = target.getY() - player.getY();
        if (dist < 0.36 && Math.abs(dyFeet) < 0.75) {
            if (back) {
                returnIndex--;
                if (returnIndex < 0) {
                    finish(job, player, true);
                    return;
                }
            } else {
                afterArrive(job, player, level, target);
                index++;
                // Back to the planning state so the *next* cell is dug before walking into it.
                state = 0;
            }
            stall = 0;
            walkTicks = 0;
            watch.restart();
            return;
        }
        walkTicks++;
        if (watch.tick(player, mined + (back ? 0 : index))) {
            watch.reportStall(job.id);
            job.metric("watchdog", watch.toJson());
            job.fail(Errors.STALLED, "dig_tunnel could not reach " + target.toShortString()
                    + " from " + player.blockPosition().toShortString() + " (distance " + MathUtil.round2(dist) + ")");
            return;
        }
        if (dist > 0.09) {
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            player.setYRot(yaw);
            player.setYHeadRot(yaw);
            player.setDeltaMovement(dx / dist * 0.30, player.getDeltaMovement().y, dz / dist * 0.30);
            player.hurtMarked = true;
        }
        // A pure 1-block step with no horizontal distance never sets horizontalCollision, so the old
        // jump rule could not climb it (caves, stair landings). Jump whenever the target is one block
        // above and the player is standing.
        if (player.onGround()) {
            if (dyFeet > 0.4 && dist < 2.0) {
                player.jumpFromGround();
            } else if (player.horizontalCollision && stall % 6 == 0) {
                player.jumpFromGround();
            }
        }
        stall++;
        if (walkTicks > 120) {
            job.fail(Errors.BLOCKED, "could not walk into " + target.toShortString()
                    + " from " + player.blockPosition().toShortString());
            return;
        }
        job.progress(MathUtil.clamp((int) Math.round(100.0 * (back ? path.size() - returnIndex : index) / Math.max(1, path.size())), 1, 99));
    }

    private void afterArrive(Jobs.Job job, LocalPlayer player, ClientLevel level, BlockPos cell) {
        job.metric("at", Json.intArray(cell.getX(), cell.getY(), cell.getZ()));
        if (placeTorches && (index % 8 == 0) && torchesPlaced < 32) {
            JsonObject args = new JsonObject();
            args.addProperty("x", cell.getX());
            args.addProperty("y", cell.getY() + 1);
            args.addProperty("z", cell.getZ());
            try {
                JsonObject r = LightTools.torchAt(args);
                if (r != null && Json.b(r, "placed", false)) {
                    torchesPlaced++;
                    job.metric("torches", torchesPlaced);
                }
            } catch (Throwable t) {
                // no torches in the hotbar: keep digging, lighting is optional
                job.metric("torchError", t.getMessage() == null ? t.toString() : t.getMessage());
            }
        }
    }

    private void finish(Jobs.Job job, LocalPlayer player, boolean returned) {
        JsonObject r = new JsonObject();
        r.add("from", Json.intArray(from.getX(), from.getY(), from.getZ()));
        r.add("to", Json.intArray(to.getX(), to.getY(), to.getZ()));
        r.addProperty("length", path.size() - 1);
        r.addProperty("blocksMined", mined);
        r.addProperty("torches", torchesPlaced);
        r.addProperty("returnedToStart", returned);
        r.add("endPos", Json.intArray(player.blockPosition().getX(), player.blockPosition().getY(),
                player.blockPosition().getZ()));
        job.metric("steps", path.size() - 1);
        job.succeed(r);
    }

    private static boolean isFalling(net.minecraft.world.level.block.state.BlockState state) {
        if (state == null || state.isAir()) return false;
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return id.contains("gravel") || id.contains("sand") || id.contains("concrete_powder");
    }

    /** Path cells of the last tunnel (for tests/evidence). */
    public List<BlockPos> path() {
        return path;
    }
}
