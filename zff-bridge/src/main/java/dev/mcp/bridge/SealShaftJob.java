package dev.mcp.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Sealed downward shaft (Ph5 P5-1): dig straight down one block, fall, then plug the cell above the
 * head with a building block, repeat. The column behind the player is back-filled, so an ocean or an
 * aquifer above cannot flood the shaft - this is the primitive that makes underwater work possible.
 *
 * <p>Refuses lava, refuses to fall into an open hole (the block two under the feet must be solid),
 * and stops when the feet reach {@code targetY}. The shaft is 1x1: the way out is to dig a stair from
 * the bottom, which is intentional (it keeps the seal perfect while descending).</p>
 */
public final class SealShaftJob implements Jobs.TickTask {
    private final int targetY;
    private final int minY;
    private int state; // 0 plan, 1 mine, 2 fall, 3 plug
    private int ticks;
    private int steps;
    private int sealed;
    private BlockMiner miner;
    private int placeCooldown;

    public SealShaftJob(int targetY) {
        this.targetY = targetY;
        this.minY = -63;
    }

    @Override
    public void start(Jobs.Job job) {
        job.metric("targetY", targetY);
        job.metric("steps", 0);
    }

    @Override
    public void tick(Jobs.Job job) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            job.fail(Errors.DISCONNECTED, "the player left the world");
            return;
        }
        ticks++;
        if (ticks > 12_000) {
            job.fail(Errors.TIMEOUT, "sealed shaft did not reach y=" + targetY);
            return;
        }
        BlockPos feet = player.blockPosition();
        switch (state) {
            case 0 -> {
                if (feet.getY() <= Math.max(targetY, minY)) {
                    finish(job, player);
                    return;
                }
                BlockPos below = feet.below();
                BlockPos below2 = below.below();
                if (DigUtil.fluid(level, below) || DigUtil.fluid(level, below2)) {
                    job.fail(Errors.WATER_RISK, "water/lava below " + shortPos(below) + " - refusing to dig");
                    return;
                }
                if (!DigUtil.solid(level, below)) {
                    job.fail(Errors.BLOCKED, "nothing solid under the feet at " + shortPos(below));
                    return;
                }
                if (!DigUtil.solid(level, below2)) {
                    job.fail(Errors.BLOCKED, "the block under " + shortPos(below) + " is not solid (would fall into a hole)");
                    return;
                }
                if (!hasBuildingBlock(player)) {
                    job.fail(Errors.UNSUPPORTED, "no cobblestone/dirt in the hotbar to seal the shaft with");
                    return;
                }
                miner = new BlockMiner(below);
                state = 1;
            }
            case 1 -> {
                BlockMiner.Outcome outcome = miner.tick(player, level);
                if (outcome == BlockMiner.Outcome.WORKING) {
                    return;
                }
                if (outcome == BlockMiner.Outcome.FAILED) {
                    job.fail(miner.code(), miner.detail());
                    return;
                }
                miner = null;
                state = 2;
                ticks = 0;
            }
            case 2 -> {
                // wait for the one-block fall, then seal the cell above the head
                if (!player.onGround()) {
                    if (ticks > 60) {
                        job.fail(Errors.NO_PROGRESS, "did not land after mining " + shortPos(feet.below()));
                    }
                    return;
                }
                state = 3;
                placeCooldown = 0;
            }
            case 3 -> {
                BlockPos plug = player.blockPosition().above(2);
                if (DigUtil.solid(level, plug)) {
                    // already sealed (e.g. the shaft is under a solid ceiling)
                    steps++;
                    job.metric("steps", steps);
                    state = 0;
                    return;
                }
                if (placeCooldown > 0) {
                    placeCooldown--;
                    return;
                }
                if (!placeBlockAt(plug, player, level)) {
                    job.fail(Errors.UNSUPPORTED, "could not place the sealing block at " + shortPos(plug));
                    return;
                }
                sealed++;
                steps++;
                job.metric("sealed", sealed);
                job.metric("steps", steps);
                job.metric("depth", Math.max(0, targetY - player.blockPosition().getY()));
                job.progress(Math.min(99, steps));
                placeCooldown = 2;
                state = 0;
            }
            default -> state = 0;
        }
    }

    private void finish(Jobs.Job job, LocalPlayer player) {
        JsonObject r = new JsonObject();
        r.addProperty("y", MathUtil.round2(player.getY()));
        r.addProperty("steps", steps);
        r.addProperty("sealed", sealed);
        r.addProperty("dry", !player.isInWater());
        r.addProperty("air", player.getAirSupply());
        job.succeed(r);
    }

    private static boolean hasBuildingBlock(LocalPlayer player) {
        return Actions.findItemSlot(player, "minecraft:cobblestone") >= 0
                || Actions.findItemSlot(player, "minecraft:dirt") >= 0
                || Actions.findItemSlot(player, "minecraft:stone") >= 0;
    }

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
            slot = Actions.findItemSlot(player, "minecraft:stone");
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

    private static String shortPos(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }
}
