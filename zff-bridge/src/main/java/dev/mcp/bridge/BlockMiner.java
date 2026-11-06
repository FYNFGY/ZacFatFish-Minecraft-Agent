package dev.mcp.bridge;

import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One-block mining state machine: start once, continue every tick, verify the change, stop with a
 * reason. Used by mine_block (via MineExecutor) and by every dig/branch job.
 *
 * <p>Ph4 P0-1 rules enforced here:</p>
 * <ul>
 *   <li>the block is only mutated through {@code startDestroyBlock}/{@code continueDestroyBlock}, the
 *   prediction-tracked vanilla path - never through {@code gameMode.destroyBlock};</li>
 *   <li>a block counts as broken only when the world state changed <b>and stayed changed for
 *   {@link Mining#CONFIRM_TICKS} ticks</b>; a revert is reported as {@code ghost_block};</li>
 *   <li>a valuable block (ore / needs-the-right-tool) is never mined with a tool that has fewer than
 *   {@link Mining#TOOL_LOW_THRESHOLD} uses left - {@code tool_low} is returned before any damage.</li>
 * </ul>
 */
public final class BlockMiner {
    public enum Outcome {WORKING, DONE, ALREADY_DONE, FAILED}

    private final BlockPos pos;
    private BlockState original;
    private BlockState predicted;
    private boolean started;
    private boolean broke;
    private int confirmTicks;
    private Direction face = Direction.UP;
    private int ticks;
    private int maxTicks;
    private int slot = -1;
    private String brokenId;
    private String code;
    private String detail;
    private String toolId;

    private final boolean strict;

    public BlockMiner(BlockPos pos) {
        this(pos, true);
    }

    public BlockMiner(BlockPos pos, boolean strict) {
        this.strict = strict;
        this.pos = pos;
    }

    public BlockPos pos() {
        return pos;
    }

    public int ticks() {
        return ticks;
    }

    public String code() {
        return code;
    }

    public String detail() {
        return detail;
    }

    /** Registry id of the block this miner broke (null while it has not finished). */
    public String brokenId() {
        return brokenId;
    }

    /** Registry id of the tool that was used (null when mining by hand). */
    public String toolId() {
        return toolId;
    }

    public Outcome tick(LocalPlayer player, ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        if (original == null) {
            return begin(mc, player, level);
        }
        if (broke) {
            return confirm(level);
        }
        // Keep the crosshair on this block for the whole sequence; otherwise the vanilla hit result
        // changes every tick and the destroy progress is reset (cracks, no break).
        Actions.faceBlock(player, pos);
        if (Mining.changed(level, pos, original)) {
            broke = true;
            predicted = level.getBlockState(pos);
            confirmTicks = 0;
            return Outcome.WORKING;
        }
        ticks++;
        if (slot >= 0 && player.getMainHandItem().isEmpty()) {
            // The pickaxe broke mid-block: swap in the next usable tool and keep going instead of
            // failing the whole job (long digs burn through stone pickaxes).
            int next = Mining.bestToolSlot(player, original);
            if (next < 0 && Mining.fetchToolToHotbar(player, original, slot) >= 0) {
                next = Mining.bestToolSlot(player, original);
            }
            if (next >= 0) {
                slot = next;
                Actions.selectSlot(player, slot);
                try {
                    mc.gameMode.startDestroyBlock(pos, face);
                } catch (Throwable ignored) {
                }
                return Outcome.WORKING;
            }
            code = Errors.TOOL_BROKEN;
            detail = "the tool broke while mining and there is no replacement";
            return Outcome.FAILED;
        }
        if (ticks > maxTicks) {
            try {
                mc.gameMode.stopDestroyBlock();
            } catch (Throwable ignored) {
            }
            code = Errors.NO_PROGRESS;
            detail = "no progress after " + ticks + " ticks";
            return Outcome.FAILED;
        }
        mc.gameMode.continueDestroyBlock(pos, face);
        if (Mining.changed(level, pos, original)) {
            broke = true;
            predicted = level.getBlockState(pos);
            confirmTicks = 0;
        }
        return Outcome.WORKING;
    }

    private Outcome begin(Minecraft mc, LocalPlayer player, ClientLevel level) {
        original = level.getBlockState(pos);
        ticks = 0;
        started = false;
        slot = -1;
        if (original.isAir()) {
            return Outcome.ALREADY_DONE;
        }
        // Ph8: automatic mining stops on the last use of the current hand tool. Do this before
        // bestToolSlot can silently swap in a spare; CR must decide whether to repair/switch.
        ItemStack held = player.getMainHandItem();
        int heldRemaining = Mining.remaining(held);
        if (heldRemaining >= 0 && heldRemaining <= 1) {
            EventPush.pushToolLow(held, heldRemaining);
            code = Errors.TOOL_LOW;
            detail = "current hand tool has " + heldRemaining + " use(s) left; automatic mining stopped";
            return Outcome.FAILED;
        }
        float hardness = original.getDestroySpeed(level, pos);
        if (hardness < 0) {
            code = Errors.UNBREAKABLE;
            detail = "block is unbreakable";
            return Outcome.FAILED;
        }
        if (!Mining.inReach(player, pos)) {
            code = Errors.OUT_OF_REACH;
            detail = "target is beyond 4.5 blocks from the eyes";
            return Outcome.FAILED;
        }
        slot = Mining.bestToolSlot(player, original);
        if (slot < 0) {
            // the pickaxe may simply be in the bag: move it to the hotbar and look again
            if (Mining.fetchToolToHotbar(player, original, 0) >= 0) {
                slot = Mining.bestToolSlot(player, original);
            }
        }
        if (slot < 0 && !strict) {
            // Tunnelling (stairs / branch corridors) must keep going even through an ore we cannot
            // harvest: break it with the fastest tool and accept the lost drop.
            slot = Mining.quickestSlot(player, original);
        }
        ItemStack tool;
        if (slot >= 0) {
            Actions.selectSlot(player, slot);
            tool = player.getInventory().getItem(slot);
        } else if (strict && original.requiresCorrectToolForDrops()) {
            code = Errors.WRONG_TOOL;
            detail = "no pickaxe good enough for "
                    + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(original.getBlock())
                    + " (mining it would yield no drops)";
            return Outcome.FAILED;
        } else {
            tool = player.getMainHandItem();
            // An empty hand is no tool at all - vanilla lets you punch a log or dirt by hand (slow).
            // Only a *held* tool that is used up counts as broken.
            if (!tool.isEmpty() && Mining.tooWorn(tool)) {
                code = Errors.TOOL_BROKEN;
                detail = "the held tool is used up and there is no replacement";
                return Outcome.FAILED;
            }
        }
        if (!tool.isEmpty()) {
            toolId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(tool.getItem()).toString();
        }
        int remaining = Mining.remaining(tool);
        if (remaining >= 0 && remaining <= 1) {
            EventPush.pushToolLow(tool, remaining);
            code = Errors.TOOL_LOW;
            detail = "selected tool has " + remaining + " use(s) left; automatic mining stopped";
            return Outcome.FAILED;
        }
        if (hardness > 0 && Mining.valuable(original, level, pos) && remaining >= 0
                && remaining < Mining.TOOL_LOW_THRESHOLD && Mining.tierRank(tool) >= 2) {
            // A good tool is about to die on a valuable block: use a healthy spare when one exists.
            int healthier = Mining.healthyToolSlot(player, original, Mining.TOOL_LOW_THRESHOLD);
            if (healthier >= 0) {
                slot = healthier;
                Actions.selectSlot(player, slot);
                tool = player.getInventory().getItem(slot);
                remaining = Mining.remaining(tool);
                if (!tool.isEmpty()) {
                    toolId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(tool.getItem()).toString();
                }
            }
            if (remaining >= 0 && remaining < Mining.TOOL_LOW_THRESHOLD) {
                code = Errors.TOOL_LOW;
                detail = "the best available tool has " + remaining + " uses left and "
                        + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(original.getBlock())
                        + " is valuable - craft or repair a spare first";
                return Outcome.FAILED;
            }
        }
        maxTicks = Mining.maxTicks(level, pos, original);
        face = Actions.parseDirection(null, player, pos);
        if (!mc.gameMode.startDestroyBlock(pos, face)) {
            code = Errors.NO_PROGRESS;
            detail = "could not start breaking";
            return Outcome.FAILED;
        }
        started = true;
        return Outcome.WORKING;
    }

    /** Waits for the server to confirm the change; a revert is a ghost block. */
    private Outcome confirm(ClientLevel level) {
        BlockState now = level.getBlockState(pos);
        if (now.equals(predicted)) {
            confirmTicks++;
            if (confirmTicks >= Mining.CONFIRM_TICKS) {
                brokenId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(original.getBlock()).toString();
                return Outcome.DONE;
            }
            return Outcome.WORKING;
        }
        if (now.equals(original)) {
            code = Errors.GHOST_BLOCK;
            detail = "block at " + pos.toShortString()
                    + " reappeared after mining - the server rejected the break (client/server desync)";
            Ghosts.report("mined_revert", pos, detail);
            return Outcome.FAILED;
        }
        // Something else changed the cell again (water, gravel fall...): keep waiting on the new state.
        predicted = now;
        confirmTicks = 0;
        return Outcome.WORKING;
    }

    /** True once the current stack changed (used to detect a swapped/broken tool). */
    public boolean holding(ItemStack stack) {
        return !stack.isEmpty();
    }
}
