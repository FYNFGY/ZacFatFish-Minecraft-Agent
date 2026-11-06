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

/**
 * Vanilla "tower up": jump, then place the held block under the feet at the top of the jump, land on it
 * and repeat. Every block is verified before the next jump, so a rejected placement stops cleanly.
 */
public final class TowerJob implements Jobs.TickTask {
    private final int blocks;
    private final String item;
    private int placed;
    private final java.util.List<BlockPos> headroom = new java.util.ArrayList<>();
    private BlockMiner headroomMiner;
    private int phase;
    private int ticks;
    private BlockPos support;

    public TowerJob(int blocks, String item) {
        this.blocks = MathUtil.clamp(blocks, 1, 64);
        this.item = item;
    }

    @Override
    public void start(Jobs.Job job) {
        job.metric("total", blocks);
        job.metric("placed", 0);
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            throw Errors.notLoaded("the player is not in a world");
        }
        // Towering only works when the feet rest on a full block: on stairs/slabs the jump window
        // can never clear the next block. Fail fast with the reason instead of spinning to timeout.
        BlockPos below = BlockPos.containing(player.getX(), player.getY() - 0.5, player.getZ());
        BlockState floor = mc.level.getBlockState(below);
        double floorHeight = floor.getCollisionShape(mc.level, below).max(Direction.Axis.Y);
        if (floorHeight < 0.99) {
            throw Errors.unsupported("the tower needs a full block floor, standing on "
                    + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(floor.getBlock())
                    + " (height " + MathUtil.round2(floorHeight) + ")");
        }
        if (Actions.findItemSlot(player, item) < 0) {
            throw Errors.unsupported("no matching block item in the hotbar");
        }
        Jobs manager = BridgeRuntime.jobs;
        if (manager != null) {
            manager.cancelTool("path_to");
        }
        BaritoneControl.cancel();
        // The jump needs ~3 blocks of headroom; mine whatever blocks the ceiling within that window.
        for (int dy = 2; dy <= 4; dy++) {
            BlockPos p = new BlockPos(player.blockPosition().getX(), player.blockPosition().getY() + dy, player.blockPosition().getZ());
            BlockState s = mc.level.getBlockState(p);
            if (!s.isAir() && !s.canBeReplaced()) {
                headroom.add(p);
            }
        }
        job.metric("headroomBlocks", headroom.size());
    }

    /** First solid block in the 3-cell window above the head, or null when the window is clear. */
    private BlockPos ceilingBlocker(LocalPlayer player, ClientLevel level) {
        BlockPos feet = player.blockPosition();
        for (int dy = 2; dy <= 4; dy++) {
            BlockPos p = new BlockPos(feet.getX(), feet.getY() + dy, feet.getZ());
            BlockState s = level.getBlockState(p);
            if (s.isAir() || s.canBeReplaced()) {
                continue;
            }
            return p;
        }
        return null;
    }

    /**
     * 0 = clear, 1 = a soft block was queued for mining, 2 = a hard ceiling blocks the jump (give up:
     * pillaring inside a tunnel would mean tunnelling through rock, which is what stairs are for).
     */
    private int headroomState(LocalPlayer player, ClientLevel level) {
        BlockPos feet = player.blockPosition();
        for (int dy = 2; dy <= 4; dy++) {
            BlockPos p = new BlockPos(feet.getX(), feet.getY() + dy, feet.getZ());
            BlockState s = level.getBlockState(p);
            if (s.isAir() || s.canBeReplaced()) {
                continue;
            }
            float hardness = s.getDestroySpeed(level, p);
            if (hardness > 1.2f) {
                return 2;
            }
            if (!headroom.contains(p)) {
                headroom.add(p);
            }
            return 1;
        }
        return 0;
    }

    @Override
    public void tick(Jobs.Job job) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            job.fail(Errors.DISCONNECTED, "the player left the world");
            return;
        }
        if (placed >= blocks) {
            JsonObject result = new JsonObject();
            result.addProperty("placed", placed);
            result.addProperty("y", MathUtil.round2(player.getY()));
            job.succeed(result);
            return;
        }
        // Water interrupt: mining the ceiling can open a water pocket and wash the player away; the
        // building logic must stop at once instead of fighting the current.
        if (Actions.touchingWater(player, mc.level)) {
            job.fail(Errors.WATER_RISK, "water reached the player while building - aborted at "
                    + player.blockPosition().toShortString());
            return;
        }
        // Clear the ceiling first: the jump needs ~3 blocks of headroom or the placement window is
        // never reached (this used to loop silently for minutes). Stone/deepslate ceilings are mined
        // (escape from a cave); harder blocks (obsidian, bedrock) still stop the job.
        BlockPos blocker = ceilingBlocker(player, mc.level);
        if (blocker != null) {
            float hardness = mc.level.getBlockState(blocker).getDestroySpeed(mc.level, blocker);
            if (hardness > 4.0f || hardness < 0) {
                job.fail(Errors.BLOCKED, "ceiling too low ("
                        + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(blocker).getBlock())
                        + ") - use stairs instead of pillaring underground");
                return;
            }
            job.metric("ceilingMined", blocker.toShortString());
            if (!headroom.contains(blocker)) {
                headroom.add(blocker);
            }
        }
        if (!headroom.isEmpty()) {
            if (headroomMiner == null) {
                headroomMiner = new BlockMiner(headroom.get(0));
            }
            BlockMiner.Outcome outcome = headroomMiner.tick(player, mc.level);
            if (outcome == BlockMiner.Outcome.DONE || outcome == BlockMiner.Outcome.ALREADY_DONE) {
                headroom.remove(0);
                headroomMiner = null;
                job.metric("headroomLeft", headroom.size());
            } else if (outcome == BlockMiner.Outcome.FAILED) {
                job.fail(headroomMiner.code(), "headroom: " + headroomMiner.detail());
            }
            return;
        }
        switch (phase) {
            case 0 -> {
                if (!player.onGround()) {
                    return;
                }
                // Stand in the middle of the column: a player hugging an edge catches the ceiling edge and
                // the jump/placement silently fails.
                BlockPos column = player.blockPosition();
                double cx = column.getX() + 0.5;
                double cz = column.getZ() + 0.5;
                double offX = cx - player.getX();
                double offZ = cz - player.getZ();
                if (Math.abs(offX) > 0.06 || Math.abs(offZ) > 0.06) {
                    player.setDeltaMovement(offX * 0.35, player.getDeltaMovement().y, offZ * 0.35);
                    player.hurtMarked = true;
                    ticks++;
                    if (ticks > 40) {
                        job.fail(Errors.NO_PROGRESS, "could not centre on the column at "
                                + player.blockPosition().toShortString());
                    }
                    return;
                }
                int slot = Actions.findItemSlot(player, item);
                if (slot < 0) {
                    job.fail(Errors.UNSUPPORTED, "no matching block item in the hotbar");
                    return;
                }
                Actions.selectSlot(player, slot);
                support = BlockPos.containing(player.getX(), player.getY() - 0.5, player.getZ());
                player.jumpFromGround();
                phase = 1;
                ticks = 0;
            }
            case 1 -> {
                ticks++;
                if (player.getY() >= support.getY() + 2.02 && ticks >= 4) {
                    int slot = Actions.findItemSlot(player, item);
                    if (slot < 0) {
                        job.fail(Errors.UNSUPPORTED, "no matching block item in the hotbar");
                        return;
                    }
                    Actions.selectSlot(player, slot);
                    BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support), Direction.UP, support, false);
                    mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                    player.swing(InteractionHand.MAIN_HAND);
                    phase = 2;
                    ticks = 0;
                } else if (ticks > 20) {
                    // Missed the window: something above the head (leaves, a ceiling) clipped the jump.
                    // Queue it for mining instead of silently retrying the same jump forever.
                    BlockPos ceiling = player.blockPosition().above(2);
                    if (!mc.level.getBlockState(ceiling).isAir()
                            && !mc.level.getBlockState(ceiling).canBeReplaced()
                            && !headroom.contains(ceiling)) {
                        headroom.add(ceiling);
                        job.metric("headroomQueued", ceiling.toShortString());
                    }
                    phase = 0;
                    ticks = 0;
                }
            }
            case 2 -> {
                ticks++;
                BlockState placedState = mc.level.getBlockState(support.above());
                if (placedState.isAir()) {
                    if (player.onGround()) {
                        phase = 0;
                        ticks = 0;
                    } else if (ticks > 30) {
                        job.fail(Errors.INTERNAL, "the tower block was not placed");
                    }
                    return;
                }
                if (player.onGround()) {
                    placed++;
                    job.metric("placed", placed);
                    job.progress(Math.min(99, placed * 100 / Math.max(1, blocks)));
                    phase = 0;
                    ticks = 0;
                } else if (ticks > 40) {
                    job.fail(Errors.INTERNAL, "did not land on the new tower block");
                }
            }
            default -> {
            }
        }
    }

    @Override
    public void cancel(Jobs.Job job) {
        // nothing to undo; the client keeps whatever blocks were already placed
    }
}

