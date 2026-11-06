package dev.mcp.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Safe downward digging: a 45-degree staircase (default) or a 1x2 shaft (alternating columns).
 * Every cell is fluid-probed before mining and the block below the next step must be solid, so the
 * player never breaks into an aquifer, a lava lake or a cave.
 */
public final class DigJob implements Jobs.TickTask {
    public enum Mode {STAIR, SHAFT, UP, DESCEND, SPIRAL}

    private final int targetY;
    private final Mode mode;
    private float yaw;
    private final int maxBlocks;
    private final List<BlockPos> pending = new ArrayList<>();
    private int state;
    private int blockIndex;
    private int mined;
    private int steps;
    private int ticks;
    private int walkTicks;
    private boolean jumpSent;
    private int stall;
    private int startY;
    private double startX;
    private double startZ;
    private BlockMiner miner;
    private BlockPos stepPos;
    private boolean placeSent;
    private int placeTicks;
    private boolean afterMinePlan;
    private final java.util.Map<String, Integer> minedById = new java.util.TreeMap<>();
    private final ProgressWatch watch = new ProgressWatch("dig_shaft", 70, 1.0);

    public DigJob(int targetY, Mode mode, float yaw, int maxBlocks) {
        this.targetY = targetY;
        this.mode = mode;
        this.yaw = yaw;
        this.maxBlocks = maxBlocks;
    }

    @Override
    public void start(Jobs.Job job) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (Float.isNaN(yaw)) {
            yaw = player == null ? 180.0f : player.getYRot();
        }
        job.metric("targetY", targetY);
        job.metric("mode", mode.name().toLowerCase());
        job.metric("blocksMined", 0);
        job.metric("steps", 0);
        Jobs manager = BridgeRuntime.jobs;
        if (manager != null) {
            manager.cancelTool("path_to");
            manager.cancelTool("walk");
            manager.cancelTool("walk_to");
        }
        BaritoneControl.cancel();
        if (player != null) {
            player.setSprinting(false);
        }
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
            job.fail(Errors.WATER_RISK, "water reached the player while digging - aborted at "
                    + player.blockPosition().toShortString());
            return;
        }
        BlockPos feet = player.blockPosition();
        boolean reached = mode == Mode.UP ? feet.getY() >= targetY : feet.getY() <= targetY;
        if (reached) {
            succeed(job, player);
            return;
        }
        if (ticks++ > 12000) {
            job.fail(Errors.TIMEOUT, "dig did not reach the target Y in 12000 ticks");
            return;
        }
        if (mined >= maxBlocks) {
            job.fail(Errors.BUDGET_EXCEEDED, "block budget of " + maxBlocks + " exhausted at y=" + feet.getY());
            return;
        }
        switch (state) {
            case 0 -> plan(job, player, level, feet);
            case 1 -> mineNext(job, player, level);
            case 2 -> walkForward(job, player, feet);
            case 3 -> placeStep(job, player, level);
            default -> job.fail(Errors.INTERNAL, "bad state " + state);
        }
        job.metric("blocksMined", mined);
        job.metric("steps", steps);
    }

    private void plan(Jobs.Job job, LocalPlayer player, ClientLevel level, BlockPos feet) {
        int[] dir = dirOf(yaw);
        BlockPos front = feet.offset(dir[0], 0, dir[1]);
        int y = feet.getY();
        pending.clear();
        if (mode == Mode.STAIR || mode == Mode.SPIRAL) {
            BlockPos support = new BlockPos(front.getX(), y - 2, front.getZ());
            if (!DigUtil.solid(level, support)) {
                // The facing cell has no floor (a cave or the end of a tunnel): turn until a solid
                // step exists instead of failing the whole descent.
                boolean found = false;
                for (float off : new float[]{45, -45}) {
                    float cand = (yaw + off + 360.0f) % 360.0f;
                    int[] d2 = dirOf(cand);
                    BlockPos f2 = feet.offset(d2[0], 0, d2[1]);
                    if (DigUtil.solid(level, new BlockPos(f2.getX(), y - 2, f2.getZ()))) {
                        yaw = cand;
                        front = f2;
                        found = true;
                        job.metric("turnedTo", Math.round(cand));
                        break;
                    }
                }
                if (!found) {
                    job.fail(Errors.BLOCKED, "no solid step in any direction around " + shortPos(feet)
                            + " - stop here and dig a horizontal approach");
                    return;
                }
            }
        }
        if (mode != Mode.UP && mode != Mode.DESCEND && mode != Mode.SHAFT) {
            BlockPos support = new BlockPos(front.getX(), y - 2, front.getZ());
            if (!DigUtil.solid(level, support)) {
                job.fail(Errors.BLOCKED, "the step below " + shortPos(front) + " is not solid (would fall into a hole)");
                return;
            }
        }
        if (mode == Mode.SPIRAL && steps > 0 && steps % 4 == 0) {
            yaw = (yaw + 90.0f) % 360.0f;
        }
        if (mode == Mode.DESCEND || mode == Mode.SHAFT) {
            // Controlled vertical descent (SHAFT is now a true 1x1 shaft, not a stair planner).
            BlockPos below = new BlockPos(feet.getX(), y - 1, feet.getZ());
            BlockPos below2 = below.below();
            if (!DigUtil.solid(level, below)) {
                job.fail(Errors.BLOCKED, "nothing solid under the feet at " + shortPos(below));
                return;
            }
            if (!DigUtil.solid(level, below2)) {
                job.fail(Errors.BLOCKED, "the block under " + shortPos(below) + " is not solid (would fall into a hole)");
                return;
            }
            if (DigUtil.fluid(level, below) || DigUtil.fluid(level, below2)) {
                job.fail(Errors.WATER_RISK, "water/lava under the feet at " + shortPos(below) + " - refusing to dig");
                return;
            }
            pending.add(below);
        } else if (mode == Mode.UP) {
            // Climb a staircase: keep the step block at (front, y) solid, clear the two cells above it
            // and the ceiling above the player so the jump is not capped. If the step is missing, place
            // a block there (the "stairs from blocks" case on ridges and ledges).
            BlockPos step = new BlockPos(front.getX(), y, front.getZ());
            BlockPos head1 = step.above();
            BlockPos head2 = step.above(2);
            if (!DigUtil.solid(level, step)) {
                if (!DigUtil.solid(level, step.below())) {
                    // No floor for the step (cave floor, overhang): build one under the step from the
                    // nearest solid neighbour, then re-plan on the next tick.
                    if (placeBlockAt(step.below(), player, level)) {
                        job.metric("builtFloor", step.below().toShortString());
                        return;
                    }
                    job.fail(Errors.BLOCKED, "no floor below " + shortPos(step)
                            + " to build a step on and no block to build it with");
                    return;
                }
                pending.clear();
                if (!level.getBlockState(head1).isAir()) {
                    pending.add(head1);
                }
                if (!level.getBlockState(head2).isAir()) {
                    pending.add(head2);
                }
                if (!pending.isEmpty()) {
                    blockIndex = 0;
                    miner = null;
                    startY = y;
                    afterMinePlan = true;
                    state = 1;
                    return;
                }
                stepPos = step;
                placeSent = false;
                placeTicks = 0;
                state = 3;
                return;
            }
            pending.add(head1);
            pending.add(head2);
            pending.add(new BlockPos(feet.getX(), y + 2, feet.getZ()));
        } else if (mode == Mode.SHAFT) {
            pending.add(new BlockPos(front.getX(), y - 1, front.getZ()));
        } else {
            // A walked staircase needs clearance for the body *while moving*: the dest cell must be
            // air at Y-1 (the hole we drop into), Y and Y+1 (the head while crossing the lip).
            pending.add(new BlockPos(front.getX(), y - 1, front.getZ()));
            pending.add(new BlockPos(front.getX(), y, front.getZ()));
            pending.add(new BlockPos(front.getX(), y + 1, front.getZ()));
        }
        for (BlockPos p : pending) {
            if (DigUtil.fluid(level, p)) {
                job.fail(Errors.WATER_RISK, "water/lava at " + shortPos(p) + " - refusing to dig");
                return;
            }
            if (DigUtil.unbreakable(level, p)) {
                job.fail(Errors.UNBREAKABLE, "unbreakable block at " + shortPos(p));
                return;
            }
        }
        startY = feet.getY();
        startX = player.getX();
        startZ = player.getZ();
        blockIndex = 0;
        miner = null;
        state = 1;
    }

    private void mineNext(Jobs.Job job, LocalPlayer player, ClientLevel level) {
        if (blockIndex >= pending.size()) {
            if (afterMinePlan) {
                afterMinePlan = false;
                state = 0;
            } else {
                state = 2;
                walkTicks = 0;
            }
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
            case FAILED -> {
                job.fail(miner.code(), miner.detail());
            }
            case WORKING -> {
            }
        }
    }

    /** Waits for the one-block drop after digging the block under the feet (startY is pre-mining). */
    private void walkDown(Jobs.Job job, LocalPlayer player) {
        walkTicks++;
        int nowY = player.blockPosition().getY();
        if (nowY < startY) {
            steps++;
            stall = 0;
            walkTicks = 0;
            state = 0;
            return;
        }
        if (walkTicks > 20 && player.onGround()) {
            // Nudge off the ledge edge: the block under the feet is gone but a neighbour still holds us.
            DigUtil.walk(player, yaw, 0.16);
        }
        if (walkTicks > 40 && player.onGround()) {
            stall++;
            if (stall >= 3) {
                job.fail(Errors.NO_PROGRESS, "did not drop after digging at y=" + nowY);
                return;
            }
            walkTicks = 0;
            state = 0;
        }
    }

    /**
     * Careful one-block climb (Ph4 P1-3), rewritten as a deterministic loop:
     * <ol>
     *   <li>walk until the player touches the step face (the old code walked toward the <b>centre</b>
     *   of the solid step, which is unreachable - that was the "could not reach the step" bug);</li>
     *   <li>jump once with a forward push; the 2-high clearance above the step caps the jump at just
     *   over one block, which is exactly what is needed;</li>
     *   <li>verify <code>feetY == startY + 1 &amp;&amp; onGround</code>, otherwise retry up to 3 times.</li>
     * </ol>
     */
    private void walkUp(Jobs.Job job, LocalPlayer player) {
        ClientLevel level = (ClientLevel) player.level();
        int[] dir = dirOf(yaw);
        BlockPos feet = player.blockPosition();
        BlockPos step = new BlockPos(feet.getX() + dir[0], feet.getY(), feet.getZ() + dir[1]);
        if (walkTicks == 0) {
            startY = feet.getY();
            jumpSent = false;
        }
        walkTicks++;
        if (feet.getY() > startY && player.onGround()) {
            steps++;
            stall = 0;
            walkTicks = 0;
            jumpSent = false;
            state = 0;
            return;
        }
        double dx = step.getX() + 0.5 - player.getX();
        double dz = step.getZ() + 0.5 - player.getZ();
        double dist = Math.hypot(dx, dz);
        // 0.5 = half block to the step centre, 0.3 = half of the player's 0.6 width: touching the face.
        if (!jumpSent && dist > 0.82) {
            float face = (float) Math.toDegrees(Math.atan2(-dx, dz));
            player.setYRot(face);
            player.setYHeadRot(face);
            DigUtil.walk(player, face, 0.20);
            if (walkTicks > 40) {
                stall++;
                walkTicks = 0;
                if (stall >= 3) {
                    job.fail(Errors.NO_PROGRESS, "could not reach the step face at "
                            + shortPos(step) + " (distance " + MathUtil.round2(dist) + ")");
                }
            }
            return;
        }
        if (player.onGround() && !jumpSent) {
            player.jumpFromGround();
            jumpSent = true;
        }
        DigUtil.walk(player, yaw, 0.26);
        if (walkTicks > 25 && player.onGround()) {
            // Landed again in the old cell: retry from the face instead of bouncing forever.
            stall++;
            jumpSent = false;
            walkTicks = 0;
            if (stall >= 3) {
                job.fail(Errors.NO_PROGRESS, "could not climb onto the step at " + shortPos(step));
            }
        }
    }

    /** Places one block as the next stair step, then hops onto it. */
    private void placeStep(Jobs.Job job, LocalPlayer player, ClientLevel level) {
        BlockPos step = stepPos;
        if (step == null) {
            job.fail(Errors.INTERNAL, "no step position");
            return;
        }
        if (DigUtil.solid(level, step)) {
            state = 2;
            walkTicks = 0;
            return;
        }
        if (!placeSent) {
            int slot = Actions.findItemSlot(player, "minecraft:dirt");
            if (slot < 0) {
                slot = Actions.findItemSlot(player, "minecraft:cobblestone");
            }
            if (slot < 0) {
                job.fail(Errors.UNSUPPORTED, "no building block in the hotbar for a stair step");
                return;
            }
            Actions.selectSlot(player, slot);
            BlockPos support = step.below();
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support), Direction.UP, support, false);
            Minecraft.getInstance().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND);
            placeSent = true;
            placeTicks = 0;
            return;
        }
        placeTicks++;
        if (placeTicks > 12) {
            job.fail(Errors.NO_PROGRESS, "could not place a step block at " + shortPos(step));
        }
    }

    private void walkForward(Jobs.Job job, LocalPlayer player, BlockPos feet) {
        if (mode == Mode.UP) {
            walkUp(job, player);
            return;
        }
        if (mode == Mode.DESCEND || mode == Mode.SHAFT) {
            walkDown(job, player);
            return;
        }
        if (watch.tick(player, steps)) {
            watch.reportStall(job.id);
            job.metric("watchdog", watch.toJson());
            BlockPos ghost = Ghosts.checkStuck(player, (ClientLevel) player.level());
            if (ghost != null) {
                Ghosts.report("invisible_wall", ghost, "dig_shaft is stuck inside an all-air bubble");
                job.fail(Errors.GHOST_BLOCK, "ghost collider at " + ghost.toShortString()
                        + " (all 27 cells are air but the player is blocked)");
                return;
            }
            job.fail(Errors.STALLED, "dig_shaft is not moving in mode " + mode.name().toLowerCase()
                    + " at " + feet.toShortString() + " (blocksMined=" + mined + ", steps=" + steps + ")");
            return;
        }
        if (walkTicks == 0) {
            startX = player.getX();
            startZ = player.getZ();
            startY = player.blockPosition().getY();
        }
        walkTicks++;
        int nowY = player.blockPosition().getY();
        // Ph4 P1-4: a stair step only counts when the player actually dropped one block. Moving
        // 0.85 blocks inside the same cell used to count as a step, so the job "descended" without
        // descending and wandered uphill on sloped terrain.
        if (nowY < startY) {
            steps++;
            stall = 0;
            walkTicks = 0;
            state = 0;
            return;
        }
        if (walkTicks > 60) {
            stall++;
            if (stall >= 3) {
                job.fail(Errors.NO_PROGRESS, "did not descend after digging at y=" + nowY
                        + " (stuck at " + player.blockPosition().toShortString() + ")");
                return;
            }
            walkTicks = 0;
            state = 0;
            return;
        }
        // Walk into the hole that was just dug in front of the player; do not jump (jumping climbs
        // the next lip and turns a descent into a climb).
        DigUtil.walk(player, yaw, 0.24);
    }

    private void succeed(Jobs.Job job, LocalPlayer player) {
        JsonObject r = new JsonObject();
        r.addProperty("reachedY", player.blockPosition().getY());
        r.addProperty("steps", steps);
        r.addProperty("blocksMined", mined);
        r.addProperty("mode", mode.name().toLowerCase());
        r.add("pos", Json.intArray(player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ()));
        job.succeed(r);
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

    private static String shortPos(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    /**
     * Places a building block at {@code target} by right-clicking the first solid neighbour.
     * Used to build a floor under the next step when the stair climbs a cave/overhang.
     */
    private boolean placeBlockAt(BlockPos target, LocalPlayer player, ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null) {
            return false;
        }
        int slot = Actions.findItemSlot(player, "minecraft:cobblestone");
        if (slot < 0) {
            slot = Actions.findItemSlot(player, "minecraft:dirt");
        }
        if (slot < 0) {
            return false;
        }
        Actions.selectSlot(player, slot);
        for (Direction dir : Direction.values()) {
            BlockPos support = target.relative(dir);
            if (!DigUtil.solid(level, support)) {
                continue;
            }
            Direction face = dir.getOpposite();
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support), face, support, false);
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        return false;
    }
}
