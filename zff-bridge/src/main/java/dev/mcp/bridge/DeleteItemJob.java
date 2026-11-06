package dev.mcp.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Server-synced item deletion (Ph4 P1-6).
 *
 * <p>The old client-side stack mutation produced fake empty slots. This job never edits an
 * {@link ItemStack}: it moves the target stack to a hotbar slot with a menu SWAP click, selects it,
 * sends the vanilla DROP_ALL_ITEMS action ({@code LocalPlayer.drop(true)}) and waits until the
 * server syncs the slot back as empty. Optionally it then runs a few blocks away so the dropped
 * entities are not picked up again.</p>
 *
 * <p>This is option 1 of ISSUES P1-6 ("server-synced THROW click"): the items stay on the ground,
 * which is exactly what "delete junk" means in survival. There is no local inventory mutation.</p>
 */
public final class DeleteItemJob implements Jobs.TickTask {
    private static final int DROP_WAIT_TICKS = 40;
    private static final int RUN_TICKS = 70;

    private final String itemId;
    private final int requestedSlot;
    private final int runDistance;

    private int state; // 0 find, 1 swap, 2 verify swap, 3 drop, 4 verify drop, 5 run, 6 done
    private int slot = -1;
    private int hotbar = -1;
    private int count;
    private int waitTicks;
    private int runTicks;
    private int runSign = 1;
    private double runX;
    private double runZ;
    private double runStartX;
    private double runStartZ;
    private boolean movedSinceRun;
    private int freeBefore;
    private String droppedId;
    private String error;

    public DeleteItemJob(String itemId, int requestedSlot, int runDistance) {
        this.itemId = itemId == null || itemId.isBlank() ? null : itemId;
        this.requestedSlot = requestedSlot;
        this.runDistance = MathUtil.clamp(runDistance, 0, 12);
    }

    @Override
    public void start(Jobs.Job job) {
        job.metric("item", itemId == null ? "any" : itemId);
        job.metric("state", "find");
    }

    private int menuSlot(int invSlot) {
        return invSlot < 9 ? 36 + invSlot : invSlot;
    }

    private static String idOf(ItemStack stack) {
        return stack.isEmpty() ? "minecraft:air"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private int findSlot(LocalPlayer player) {
        if (requestedSlot >= 0 && requestedSlot < 36) {
            return requestedSlot;
        }
        int best = -1;
        int bestCount = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = idOf(stack);
            if (itemId != null && !id.equals(itemId) && !id.endsWith(":" + itemId)) {
                continue;
            }
            if (stack.getCount() > bestCount) {
                bestCount = stack.getCount();
                best = i;
            }
        }
        return best;
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
        switch (state) {
            case 0 -> {
                slot = findSlot(player);
                freeBefore = InventoryActions.freeSlots(player);
                if (slot < 0) {
                    finish(job, player, null);
                    return;
                }
                count = player.getInventory().getItem(slot).getCount();
                droppedId = idOf(player.getInventory().getItem(slot));
                // Prefer an empty hotbar slot, otherwise reuse slot 8.
                hotbar = 8;
                for (int h = 8; h >= 0; h--) {
                    if (player.getInventory().getItem(h).isEmpty()) {
                        hotbar = h;
                        break;
                    }
                }
                if (slot == hotbar) {
                    Actions.selectSlot(player, hotbar);
                    state = 3;
                    return;
                }
                mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId,
                        menuSlot(slot), hotbar, ClickType.SWAP, player);
                state = 1;
                waitTicks = 0;
            }
            case 1 -> {
                waitTicks++;
                if (waitTicks < 5) {
                    return;
                }
                String held = idOf(player.getInventory().getItem(hotbar));
                if (!held.equals(droppedId)) {
                    job.fail(Errors.SERVER_REJECTED, "the SWAP click did not move " + droppedId
                            + " into hotbar slot " + hotbar + " (slot holds " + held + ")");
                    return;
                }
                Actions.selectSlot(player, hotbar);
                state = 3;
            }
            case 3 -> {
                Actions.selectSlot(player, hotbar);
                player.drop(true);
                job.metric("state", "verify");
                waitTicks = 0;
                state = 4;
            }
            case 4 -> {
                waitTicks++;
                if (player.getInventory().getItem(hotbar).isEmpty()) {
                    job.metric("state", "run");
                    if (runDistance <= 0) {
                        finish(job, player, droppedId);
                        return;
                    }
                    startRun(player, 1);
                    state = 5;
                    return;
                }
                if (waitTicks > DROP_WAIT_TICKS) {
                    job.fail(Errors.SERVER_REJECTED, "the drop was not confirmed by the server after "
                            + DROP_WAIT_TICKS + " ticks (" + idOf(player.getInventory().getItem(hotbar))
                            + " is still in slot " + hotbar + ")");
                }
            }
            case 5 -> {
                if (runTicks <= 0) {
                    if (!player.getInventory().getItem(hotbar).isEmpty()) {
                        // The drop itself was accepted, but the items were picked up again before the
                        // player got far enough away: that is not a deletion, report it as such.
                        job.metric("state", "repicked");
                        job.fail(Errors.REPICKED, "the dropped " + droppedId
                                + " was picked up again during the run-away; move to open space and retry");
                        return;
                    }
                    finish(job, player, droppedId);
                    return;
                }
                double dx = runX - player.getX();
                double dz = runZ - player.getZ();
                double dist = Math.hypot(dx, dz);
                if (dist < 1.0) {
                    finish(job, player, droppedId);
                    return;
                }
                // If the run direction is blocked, turn around after 15 ticks instead of standing
                // next to the dropped stack until it is picked up again.
                if (runTicks < RUN_TICKS - 15 && !movedSinceRun) {
                    startRun(player, -runSign);
                    job.metric("turnedAround", true);
                }
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                player.setYRot(yaw);
                player.setYHeadRot(yaw);
                if (player.onGround() && player.horizontalCollision) {
                    player.jumpFromGround();
                }
                player.setDeltaMovement(dx / dist * 0.26, player.getDeltaMovement().y, dz / dist * 0.26);
                player.hurtMarked = true;
                if (Math.hypot(player.getX() - runStartX, player.getZ() - runStartZ) > 1.0) {
                    movedSinceRun = true;
                }
                runTicks--;
            }
            default -> job.fail(Errors.INTERNAL, "bad state " + state);
        }
    }

    private void startRun(LocalPlayer player, int sign) {
        runSign = sign;
        double yawRad = Math.toRadians(player.getYRot());
        runX = player.getX() + sign * (-Math.sin(yawRad)) * runDistance;
        runZ = player.getZ() + sign * Math.cos(yawRad) * runDistance;
        runStartX = player.getX();
        runStartZ = player.getZ();
        movedSinceRun = false;
        runTicks = RUN_TICKS;
        state = 5;
    }

    private void finish(Jobs.Job job, LocalPlayer player, String dropped) {
        JsonObject r = new JsonObject();
        r.addProperty("deleted", dropped != null);
        r.addProperty("item", dropped == null ? itemId : dropped);
        r.addProperty("count", dropped == null ? 0 : count);
        r.addProperty("sourceSlot", slot);
        r.addProperty("hotbarSlot", hotbar);
        r.addProperty("freeSlotsBefore", freeBefore);
        r.addProperty("freeSlotsAfter", InventoryActions.freeSlots(player));
        r.addProperty("verifiedEmpty", dropped != null && (hotbar < 0
                || player.getInventory().getItem(hotbar).isEmpty()));
        r.addProperty("note", "server-synced DROP_ALL_ITEMS; nothing was edited client-side");
        if (dropped == null) {
            r.addProperty("reason", itemId == null ? "inventory is empty" : "item not found: " + itemId);
        }
        job.succeed(r);
    }
}
