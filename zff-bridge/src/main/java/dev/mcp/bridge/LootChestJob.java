package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Opens a container, quick-moves every stack out of the first 27 slots and closes it again in one job,
 * without the per-slot RPC round trips the generic container_move path needs.
 */
public final class LootChestJob implements Jobs.TickTask {
    private final BlockPos pos;
    private int phase;
    private int ticks;
    private int taken;
    private int guard;
    private int containerRows;
    private final JsonArray items = new JsonArray();

    public LootChestJob(BlockPos pos) {
        this.pos = pos;
    }

    @Override
    public void start(Jobs.Job job) {
        job.metric("total", 27);
        job.metric("taken", 0);
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            throw Errors.notLoaded("the player is not in a world");
        }
        double distance = Math.sqrt(player.distanceToSqr(Vec3.atCenterOf(pos)));
        if (distance > 5.0) {
            throw Errors.unsupported("the container is " + MathUtil.round2(distance)
                    + " blocks away; walk within reach first");
        }
        InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        if (result == InteractionResult.FAIL) {
            throw Errors.unsupported("the block did not accept a use action");
        }
        phase = 1;
    }

    @Override
    public void tick(Jobs.Job job) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            job.fail(Errors.DISCONNECTED, "the player left the world");
            return;
        }
        switch (phase) {
            case 1 -> {
                AbstractContainerMenu menu = player.containerMenu;
                if (menu != null && menu != player.inventoryMenu) {
                    containerRows = Math.min(27, menu.slots.size());
                    phase = 2;
                    ticks = 0;
                } else if (++ticks > 60) {
                    job.fail(Errors.TIMEOUT, "no container menu opened");
                }
            }
            case 2 -> {
                AbstractContainerMenu menu = player.containerMenu;
                if (menu == null || menu == player.inventoryMenu) {
                    phase = 3;
                    ticks = 0;
                    return;
                }
                int slot = -1;
                for (int i = 0; i < Math.min(27, menu.slots.size()); i++) {
                    if (!menu.getSlot(i).getItem().isEmpty()) {
                        slot = i;
                        break;
                    }
                }
                if (slot < 0) {
                    phase = 3;
                    ticks = 0;
                    return;
                }
                if (guard++ > 60) {
                    phase = 3;
                    ticks = 0;
                    return;
                }
                ItemStack stack = menu.getSlot(slot).getItem().copy();
                mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.QUICK_MOVE, player);
                taken++;
                JsonObject item = new JsonObject();
                item.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                item.addProperty("count", stack.getCount());
                items.add(item);
                job.metric("taken", taken);
                job.progress(Math.min(90, taken * 10));
            }
            case 3 -> {
                if (++ticks >= 5) {
                    player.closeContainer();
                    phase = 4;
                    ticks = 0;
                }
            }
            case 4 -> {
                if (++ticks >= 2) {
                    JsonObject result = new JsonObject();
                    result.addProperty("taken", taken);
                    result.add("items", items);
                    job.succeed(result);
                }
            }
            default -> {
            }
        }
    }
}
