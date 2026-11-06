package dev.mcp.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Fishbone branch mining: a 1x2 main corridor with perpendicular branches every {@code spacing}
 * blocks, returning to the corridor after each branch. Probes the next blocks for water/lava and
 * places torches when available. Horizontal only: the caller decides the level (Y=-54 recommended).
 */
public final class BranchMineJob implements Jobs.TickTask {
    private record Segment(int dx, int dz, int length, boolean mining, String label) {
    }

    private final int corridorLength;
    private final int spacing;
    private final int branchLength;
    private final int branches;
    private final float yaw;
    private final boolean leftSide;
    private final int maxBlocks;

    private final Deque<Segment> plan = new ArrayDeque<>();
    private Segment segment;
    private int remaining;
    private int state; // 0 plan, 1 mining, 2 walking, 3 done
    private int blockIndex;
    private final List<BlockPos> pending = new ArrayList<>();
    private BlockMiner miner;
    private final java.util.Map<String, Integer> minedById = new java.util.TreeMap<>();
    private int mined;
    private int steps;
    private int ticks;
    private int branchesDone;
    private int torchesPlaced;
    private int freeSteps;
    private int walkTicks;
    private double startX;
    private double startZ;
    private int startY;

    public BranchMineJob(int corridorLength, int spacing, int branchLength, int branches, float yaw,
                         boolean leftSide, int maxBlocks) {
        this.corridorLength = MathUtil.clamp(corridorLength, 4, 256);
        this.spacing = MathUtil.clamp(spacing, 2, 8);
        this.branchLength = MathUtil.clamp(branchLength, 2, 128);
        this.branches = MathUtil.clamp(branches, 0, 64);
        this.yaw = yaw;
        this.leftSide = leftSide;
        this.maxBlocks = MathUtil.clamp(maxBlocks, 8, 20_000);
    }

    @Override
    public void start(Jobs.Job job) {
        float useYaw = yaw;
        if (Float.isNaN(useYaw)) {
            LocalPlayer p = Minecraft.getInstance().player;
            useYaw = p == null ? 180.0f : p.getYRot();
        }
        begin(job, useYaw);
    }

    private void begin(Jobs.Job job, float useYaw) {
        int[] c = dirOf(useYaw);
        int sx = leftSide ? c[1] : -c[1];
        int sz = leftSide ? -c[0] : c[0];
        int used = 0;
        for (int i = 0; i < branches; i++) {
            int run = spacing;
            plan.addLast(new Segment(c[0], c[1], run, true, "corridor"));
            used += run;
            plan.addLast(new Segment(sx, sz, branchLength, true, "branch"));
            plan.addLast(new Segment(-sx, -sz, branchLength, false, "return"));
        }
        int tail = Math.max(0, corridorLength - used);
        if (tail > 0) {
            plan.addLast(new Segment(c[0], c[1], tail, true, "corridor"));
        }
        job.metric("total", plan.size());
        job.metric("segments", plan.size());
        job.metric("blocksMined", 0);
        job.metric("branchesDone", 0);
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
        if (Actions.touchingWater(player, level)) {
            job.fail(Errors.WATER_RISK, "water reached the player while mining - aborted at "
                    + player.blockPosition().toShortString());
            return;
        }
        if (ticks++ > 20_000) {
            job.fail(Errors.TIMEOUT, "branch mine did not finish within 20000 ticks");
            return;
        }
        if (mined >= maxBlocks) {
            job.fail(Errors.BUDGET_EXCEEDED, "block budget of " + maxBlocks + " exhausted");
            return;
        }
        switch (state) {
            case 0 -> nextSegment(job);
            case 1 -> mineStep(job, player, level);
            case 2 -> walkStep(job, player);
            default -> job.fail(Errors.INTERNAL, "bad state");
        }
        job.metric("blocksMined", mined);
        job.metric("branchesDone", branchesDone);
        job.metric("torchesPlaced", torchesPlaced);
    }

    private void nextSegment(Jobs.Job job) {
        segment = plan.pollFirst();
        if (segment == null) {
            state = 3;
            job.succeed(result());
            return;
        }
        remaining = segment.length;
        if (!segment.mining) {
            state = 2;
            walkTicks = 0;
            startX = 0;
            return;
        }
        state = 1;
        pending.clear();
        blockIndex = 0;
        miner = null;
        if (segment.label.equals("branch")) {
            branchesDone++;
        }
    }

    private void mineStep(Jobs.Job job, LocalPlayer player, ClientLevel level) {
        if (pending.isEmpty()) {
            BlockPos feet = player.blockPosition();
            int y = feet.getY();
            BlockPos front = feet.offset(segment.dx, 0, segment.dz);
            BlockState frontState = level.getBlockState(front);
            if (frontState.isAir() && level.getBlockState(front.above()).isAir()) {
                // Open space ahead (cave / room): walk through it instead of refusing, but stop after
                // a few steps so the job does not wander into a large cavern.
                boolean floorSolid = DigUtil.solid(level, front.below());
                if (!floorSolid) {
                    job.fail(Errors.BLOCKED, "drop ahead at " + shortPos(front) + " (no floor)");
                    return;
                }
                freeSteps++;
                if (freeSteps > 8) {
                    job.fail(Errors.BLOCKED, "cave ahead at " + shortPos(front) + " (walked " + freeSteps + " free steps)");
                    return;
                }
                pending.clear();
                state = 2;
                walkTicks = 0;
                startX = 0;
                return;
            }
            freeSteps = 0;
            if (DigUtil.fluidAhead(level, feet, segment.dx, segment.dz, 2, y, y + 1)) {
                job.fail(Errors.WATER_RISK, "water/lava ahead of " + shortPos(front) + " - refusing to dig");
                return;
            }
            pending.clear();
            // 2026-11-02: gravel/sand in the ceiling above the corridor drops into the 1x2 tunnel and
            // blocks it. Clear the falling ceiling first (top-down); the falling block is then mined
            // again as part of the head/feet cells below.
            BlockPos aboveHead3 = front.above(3);
            BlockPos aboveHead2 = front.above(2);
            if (isFalling(level.getBlockState(aboveHead3))) pending.add(aboveHead3);
            if (isFalling(level.getBlockState(aboveHead2))) pending.add(aboveHead2);
            pending.add(front.above());
            pending.add(front);
            blockIndex = 0;
            miner = null;
        }
        if (blockIndex >= pending.size()) {
            maybeTorch(player, level, pending.get(0));
            pending.clear();
            state = 2;
            walkTicks = 0;
            startX = 0;
            return;
        }
        if (miner == null) {
            miner = new BlockMiner(pending.get(blockIndex), false);
        }
        BlockMiner.Outcome outcome = miner.tick(player, level);
        switch (outcome) {
            case DONE, ALREADY_DONE -> {
                if (miner.brokenId() != null) {
                    minedById.merge(miner.brokenId(), 1, Integer::sum);
                    job.metric("ores", Mining.oreSummary(minedById));
                }
                job.metric("lastProbe", OreProbe.record(level, miner.pos(), job.id));
                mined++;
                blockIndex++;
                miner = null;
            }
            case FAILED -> job.fail(miner.code(), miner.detail());
            case WORKING -> {
            }
        }
    }

    private void walkStep(Jobs.Job job, LocalPlayer player) {
        // Falling gravel can refill the head cell after mineStep finished. If the next cell is no
        // longer clear, go back to mining instead of walking into it for 3 seconds (no_progress).
        ClientLevel level = (ClientLevel) player.level();
        BlockPos ahead = player.blockPosition().offset(segment.dx, 0, segment.dz);
        if (!level.getBlockState(ahead).isAir() || !level.getBlockState(ahead.above()).isAir()) {
            pending.clear();
            miner = null;
            blockIndex = 0;
            walkTicks = 0;
            state = 0;
            return;
        }
        if (walkTicks == 0) {
            startX = player.getX();
            startZ = player.getZ();
        }
        walkTicks++;
        double moved = Math.hypot(player.getX() - startX, player.getZ() - startZ);
        // In cramped spots (village rooms, rubble) the player must hop over a one-block lip.
        if (walkTicks > 6 && moved < 0.2 && player.onGround() && player.horizontalCollision) {
            player.jumpFromGround();
        }
        if (moved >= 0.85 || walkTicks > 25) {
            if (moved < 0.3) {
                job.fail(Errors.NO_PROGRESS, "walk step made no progress at " + shortPos(player.blockPosition()));
                return;
            }
            remaining--;
            steps++;
            walkTicks = 0;
            if (remaining <= 0) {
                state = 0;
                pending.clear();
                blockIndex = 0;
                miner = null;
            } else {
                state = segment.mining ? 1 : 2;
            }
            return;
        }
        float dir = yawOf(segment.dx, segment.dz);
        DigUtil.walk(player, dir, 0.22);
    }

    private void maybeTorch(LocalPlayer player, ClientLevel level, BlockPos front) {
        if (steps % 7 != 0) {
            return;
        }
        int slot = Actions.findItemSlot(player, "minecraft:torch");
        if (slot < 0) {
            return;
        }
        Actions.selectSlot(player, slot);
        BlockPos floor = front.below();
        BlockState floorState = level.getBlockState(floor);
        if (floorState.isAir() || floorState.getCollisionShape(level, floor).isEmpty()) {
            return;
        }
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(floor), Direction.UP, floor, false);
        Minecraft.getInstance().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND);
        torchesPlaced++;
    }

    private JsonObject result() {
        JsonObject r = new JsonObject();
        r.addProperty("blocksMined", mined);
        r.addProperty("branchesDone", branchesDone);
        r.addProperty("steps", steps);
        r.addProperty("torchesPlaced", torchesPlaced);
        return r;
    }

    private static boolean isFalling(BlockState state) {
        if (state == null || state.isAir()) return false;
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return id.contains("gravel") || id.contains("sand") || id.contains("concrete_powder");
    }

    private static int[] dirOf(float yaw) {
        double r = Math.toRadians(yaw);
        double dx = -Math.sin(r);
        double dz = Math.cos(r);
        if (Math.abs(dx) >= Math.abs(dz)) {
            return new int[]{(int) Math.signum(Math.round(dx * 10) / 10.0), 0};
        }
        return new int[]{0, (int) Math.signum(Math.round(dz * 10) / 10.0)};
    }

    private static float yawOf(int dx, int dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    private static String shortPos(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }
}
