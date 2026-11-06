package dev.mcp.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * Dumps junk items properly: move to a spot, drop the stack through the server (DROP packet), then
 * immediately run several blocks away so the dropped items are not sucked back in. The client and the
 * server stay in sync because nothing is edited locally.
 */
public final class DumpJob implements Jobs.TickTask {
    private static final java.util.Set<String> JUNK = java.util.Set.of(
            "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:gravel", "minecraft:andesite",
            "minecraft:diorite", "minecraft:granite", "minecraft:tuff", "minecraft:snowball",
            "minecraft:wheat_seeds", "minecraft:rooted_dirt", "minecraft:mud", "minecraft:clay_ball",
            "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:deepslate",
            "minecraft:stone", "minecraft:calcite", "minecraft:dripstone_block");

    private final boolean all;
    private final int targetFree;
    private final int runDistance;
    private final java.util.List<Integer> candidates = new java.util.ArrayList<>();
    private boolean collected;
    private int dumped;
    private int attempts;
    private int runTicks;
    private double runX;
    private double runZ;
    private int waitTicks;
    private int jumpCooldown;

    public DumpJob(int targetFree, int runDistance) {
        this(targetFree, runDistance, false);
    }

    public DumpJob(int targetFree, int runDistance, boolean all) {
        this.all = all;
        this.targetFree = MathUtil.clamp(targetFree, 1, 20);
        this.runDistance = MathUtil.clamp(runDistance, 3, 12);
    }

    @Override
    public void start(Jobs.Job job) {
        job.metric("targetFree", targetFree);
        job.metric("dumped", 0);
    }

    @Override
    public void tick(Jobs.Job job) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            job.fail(Errors.DISCONNECTED, "player left the world");
            return;
        }
        if (!collected) {
            collected = true;
            for (int i = 0; i < 36; i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if ((all && !id.contains("ender_eye")) || JUNK.contains(id)) {
                    candidates.add(i);
                }
            }
            job.metric("candidates", candidates.size());
            if (candidates.isEmpty()) {
                finish(job, player);
                return;
            }
        }
        if (InventoryActions.freeSlots(player) >= targetFree || candidates.isEmpty()) {
            finish(job, player);
            return;
        }
        if (runTicks > 0) {
            // run away from the drop so the items are not sucked back in
            double dx = runX - player.getX();
            double dz = runZ - player.getZ();
            double dist = Math.hypot(dx, dz);
            if (dist < 1.2) {
                runTicks = 0;
                waitTicks = 4;
                return;
            }
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            player.setYRot(yaw);
            player.setYHeadRot(yaw);
            if (jumpCooldown > 0) {
                jumpCooldown--;
            }
            if (player.onGround() && player.horizontalCollision && jumpCooldown == 0) {
                player.jumpFromGround();
                jumpCooldown = 6;
            }
            double speed = 0.28;
            player.setDeltaMovement(dx / dist * speed, player.getDeltaMovement().y, dz / dist * speed);
            player.hurtMarked = true;
            runTicks--;
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        if (attempts++ > 40) {
            finish(job, player);
            return;
        }
        // take the next junk stack, move it to a hotbar slot, select it and drop the whole stack
        int index = candidates.remove(0);
        ItemStack stack = player.getInventory().getItem(index);
        if (stack.isEmpty()) {
            return;
        }
        int hotbar = 8;
        int menuSlot = index < 9 ? 36 + index : index;
        // an empty hotbar slot is preferred; otherwise reuse the last one
        for (int h = 8; h >= 0; h--) {
            if (player.getInventory().getItem(h).isEmpty()) {
                hotbar = h;
                break;
            }
        }
        mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, menuSlot, hotbar,
                net.minecraft.world.inventory.ClickType.SWAP, player);
        Actions.selectSlot(player, hotbar);
        ItemStack held = player.getInventory().getItem(hotbar);
        if (!held.isEmpty()) {
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
            // LocalPlayer.drop(true) sends DROP_ALL_ITEMS: the server removes the whole stack and
            // the client slot is emptied by the inventory sync.
            player.drop(true);
            dumped++;
            job.metric("dumped", dumped);
            job.metric("lastDropped", id);
        }
        double yawRad = Math.toRadians(player.getYRot());
        runX = player.getX() - Math.sin(yawRad) * runDistance;
        runZ = player.getZ() + Math.cos(yawRad) * runDistance;
        runTicks = 80;
    }

    private void finish(Jobs.Job job, LocalPlayer player) {
        JsonObject r = new JsonObject();
        r.addProperty("dumped", dumped);
        r.addProperty("freeSlots", InventoryActions.freeSlots(player));
        r.addProperty("targetFree", targetFree);
        job.succeed(r);
    }
}
