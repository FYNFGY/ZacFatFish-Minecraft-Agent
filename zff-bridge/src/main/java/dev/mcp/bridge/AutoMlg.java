package dev.mcp.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Ph9 self-protect addition: when the player is falling and a water bucket is available, place
 * water just before landing, then recover the water into the bucket after landing.
 */
public final class AutoMlg {
    private static int cooldown;
    private static boolean placedThisFall;
    private static double lastY = Double.NaN;
    private static double fallStartY;
    private static int airTicks;
    private static BlockPos waterPos;
    private static int recoveryTicks;

    private AutoMlg() {
    }

    public static void tick(LocalPlayer player) {
        try {
            tick0(player);
        } catch (Throwable t) {
            EventPush.pushCombat("self-protect: auto MLG error: " + t);
        }
    }

    private static void tick0(LocalPlayer player) {
        if (cooldown > 0) cooldown--;
        if (player == null || !SelfProtect.enabled()) return;

        // Landed in/next to the water we just placed: pick it back up before doing anything else.
        if (waterPos != null) {
            if (!player.isAlive()) {
                waterPos = null;
                return;
            }
            if (player.onGround() || player.isInWater()) {
                recover(player);
                return;
            }
        }

        double y = player.getY();
        if (Double.isNaN(lastY)) {
            lastY = y;
            return;
        }
        double prevY = lastY;
        boolean descending = y < prevY - 0.05;
        lastY = y;

        if (player.isInWater() || player.isInLava()) {
            reset(y);
            return;
        }
        if (descending) {
            if (airTicks == 0) fallStartY = y;
            airTicks++;
        } else if (player.onGround()) {
            reset(y);
            return;
        } else if (y > prevY + 0.25) {
            // Jump / knockback, not a landing fall.
            airTicks = 0;
        }

        if (airTicks < 3 || fallStartY - y < 4.0) return;
        if (placedThisFall || cooldown > 0) return;

        int slot = Actions.findItemSlotAll(player, "minecraft:water_bucket");
        if (slot < 0) return;
        if (slot > 8) {
            int hotbar = player.getInventory().selected;
            if (!InventoryActions.quickSwap(player, slot, hotbar)) return;
            slot = hotbar;
        }

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        BlockPos ground = null;
        BlockPos feet = player.blockPosition();
        for (int dy = 1; dy <= 12; dy++) {
            BlockPos pos = feet.below(dy);
            BlockState state = level.getBlockState(pos);
            if (!state.getCollisionShape(level, pos).isEmpty() || !state.getFluidState().isEmpty()) {
                ground = pos;
                break;
            }
        }
        if (ground == null) return;

        // Reach is measured from the eye (~1.62 above the feet); keep the block within 4.5.
        double gap = player.getY() - (ground.getY() + 1);
        if (gap > 2.8) return;

        Actions.selectSlot(player, slot);
        Actions.faceBlock(player, ground);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(ground), Direction.UP, ground, false);
        InteractionResult result = Minecraft.getInstance().gameMode.useItemOn(
                player, InteractionHand.MAIN_HAND, hit);
        if (!result.consumesAction()) {
            Minecraft.getInstance().gameMode.useItem(player, InteractionHand.MAIN_HAND);
        }
        player.swing(InteractionHand.MAIN_HAND);

        // Only count it as done if the water block actually exists; otherwise retry while falling.
        if (level.getBlockState(ground.above()).getFluidState().isEmpty()) {
            cooldown = 2;
            return;
        }
        placedThisFall = true;
        waterPos = ground.above();
        recoveryTicks = 0;
        cooldown = 8;
        EventPush.pushCombat("self-protect: auto MLG water placed at "
                + waterPos.toShortString() + ", fall " + MathUtil.round2(player.fallDistance));
    }

    private static void recover(LocalPlayer player) {
        recoveryTicks++;
        if (recoveryTicks > 40) {
            EventPush.pushCombat("self-protect: auto MLG water recovery failed at "
                    + (waterPos == null ? "?" : waterPos.toShortString()));
            waterPos = null;
            placedThisFall = false;
            return;
        }
        int slot = Actions.findItemSlotAll(player, "minecraft:bucket");
        if (slot < 0) {
            EventPush.pushCombat("self-protect: auto MLG water recovery skipped, no empty bucket");
            waterPos = null;
            placedThisFall = false;
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        Actions.selectSlot(player, slot);
        Actions.faceBlock(player, waterPos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(waterPos), Direction.UP, waterPos, false);
        InteractionResult result = Minecraft.getInstance().gameMode.useItemOn(
                player, InteractionHand.MAIN_HAND, hit);
        if (!result.consumesAction()) {
            Minecraft.getInstance().gameMode.useItem(player, InteractionHand.MAIN_HAND);
        }
        player.swing(InteractionHand.MAIN_HAND);

        ItemStack held = player.getInventory().getItem(slot);
        if (held.is(Items.WATER_BUCKET)) {
            EventPush.pushCombat("self-protect: auto MLG water recovered at "
                    + waterPos.toShortString());
            waterPos = null;
            placedThisFall = false;
            recoveryTicks = 0;
            cooldown = 8;
        }
    }

    private static void reset(double y) {
        placedThisFall = false;
        airTicks = 0;
        fallStartY = y;
    }

    public static boolean hasWaterBucket(LocalPlayer player) {
        return player != null && Actions.findItemSlotAll(player, "minecraft:water_bucket") >= 0;
    }
}
