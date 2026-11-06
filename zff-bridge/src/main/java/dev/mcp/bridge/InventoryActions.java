package dev.mcp.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/**
 * Inventory manipulation through the player's own inventory menu (server-synced). Lets the agent move
 * a tool or block from the main inventory into the hotbar, which the mining/placement actions need.
 */
public final class InventoryActions {
    private InventoryActions() {
    }

    /** Empty main-inventory + hotbar slots (0..35). 0 means the inventory is full. */
    public static int freeSlots(LocalPlayer player) {
        int free = 0;
        for (int i = 0; i < 36; i++) {
            if (player.getInventory().getItem(i).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    /** Blocks that are worth throwing away when the bag is full. */
    private static final java.util.Set<String> JUNK = java.util.Set.of(
            "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:gravel", "minecraft:andesite",
            "minecraft:diorite", "minecraft:granite", "minecraft:tuff", "minecraft:snowball",
            "minecraft:wheat_seeds", "minecraft:rooted_dirt", "minecraft:mud", "minecraft:clay_ball",
            "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:deepslate", "minecraft:stone");

    /**
     * Drops junk blocks until at least `target` free slots exist (or nothing junk is left).
     * Uses the menu THROW click (server-synced): mutating the stack directly desynchronises the client
     * inventory, which made slots look empty while the server still held the items.
     */
    public static JsonObject dropJunk(JsonObject args) {
        int target = MathUtil.clamp(Json.i(args, "target", 4), 1, 20);
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            throw Errors.notLoaded("the player is not in a world");
        }
        if (mc.gameMode == null) {
            throw Errors.notLoaded("the game mode is not ready");
        }
        int before = freeSlots(player);
        var dropped = new com.google.gson.JsonArray();
        // Inventory mutation is disabled: direct stack edits / drops desynced the client inventory
        // (slots looked empty while the server still held the items). Report only.
        if (true) {
            JsonObject r = Json.ok();
            r.addProperty("freeSlotsBefore", before);
            r.addProperty("freeSlotsAfter", freeSlots(player));
            r.add("dropped", dropped);
            r.addProperty("note", "inventory mutation disabled to keep client/server in sync");
            return r;
        }
        for (int i = 0; i < 36 && freeSlots(player) < target; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (!JUNK.contains(id)) {
                continue;
            }
            int count = stack.getCount();
            // LocalPlayer.drop sends the DROP_ITEM action packet: the server removes the stack, so
            // client and server stay in sync (directly shrinking the stack desyncs them).
            player.drop(stack.copy(), false);
            JsonObject o = new JsonObject();
            o.addProperty("item", id);
            o.addProperty("count", count);
            dropped.add(o);
        }
        JsonObject r = Json.ok();
        r.addProperty("freeSlotsBefore", before);
        r.addProperty("freeSlotsAfter", freeSlots(player));
        r.add("dropped", dropped);
        return r;
    }

    /**
     * 把主背包槽（9..35，也就是 inventoryMenu 的同号槽）与快捷栏槽（0..8）互换。
     * SWAP 点击是对合操作，再调一次即可换回。
     *
     * <p>ISSUE-4：{@code place_block} 找不到快捷栏物品时会自动调它，把背包里的方块换到当前槽。</p>
     *
     * @return true 表示点击已发给服务端（客户端会先做本地预测）
     */
    public static boolean quickSwap(LocalPlayer player, int fromSlot, int hotbarSlot) {
        if (player == null || hotbarSlot < 0 || hotbarSlot > 8) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null) {
            return false;
        }
        // 只有“玩家背包菜单”打开时槽位索引才和 inventoryMenu 对齐；开着箱子时点同号槽会点错。
        if (player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
            return false;
        }
        int size = player.inventoryMenu.slots.size();
        if (fromSlot < 0 || fromSlot >= size) {
            return false;
        }
        mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, fromSlot, hotbarSlot, ClickType.SWAP, player);
        return true;
    }

    /** Swap a main-inventory slot with a hotbar slot (0-8). Slots follow the inventory menu indices. */
    public static JsonObject swap(JsonObject args) {
        int from = Json.i(args, "fromSlot", Integer.MIN_VALUE);
        int hotbar = Json.i(args, "hotbarSlot", Integer.MIN_VALUE);
        if (from == Integer.MIN_VALUE || hotbar == Integer.MIN_VALUE) {
            throw new Errors.BridgeException(Errors.INTERNAL, "fromSlot and hotbarSlot are required");
        }
        if (hotbar < 0 || hotbar > 8) {
            throw new Errors.BridgeException(Errors.INTERNAL, "hotbarSlot must be 0..8");
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            throw Errors.notLoaded("the player is not in a world");
        }
        if (mc.gameMode == null) {
            throw Errors.notLoaded("the game mode is not ready");
        }
        int size = player.inventoryMenu.slots.size();
        if (from < 0 || from >= size) {
            throw new Errors.BridgeException(Errors.INTERNAL, "fromSlot is out of range (0.." + (size - 1) + ")");
        }
        ItemStack beforeFrom = player.inventoryMenu.getSlot(from).getItem().copy();
        int hotbarSlotIndex = 36 + hotbar;
        ItemStack beforeHotbar = player.inventoryMenu.getSlot(hotbarSlotIndex).getItem().copy();
        if (player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
            throw Errors.unsupported("a container screen is open; call container_close first");
        }
        if (!quickSwap(player, from, hotbar)) {
            throw new Errors.BridgeException(Errors.INTERNAL, "could not send the swap click");
        }
        JsonObject o = Json.ok();
        o.addProperty("fromSlot", from);
        o.addProperty("hotbarSlot", hotbar);
        o.add("beforeFrom", GameState.stack(beforeFrom));
        o.add("beforeHotbar", GameState.stack(beforeHotbar));
        o.add("afterHotbar", GameState.stack(player.getInventory().getItem(hotbar)));
        o.add("afterFrom", GameState.stack(player.inventoryMenu.getSlot(from).getItem()));
        return o;
    }

    /** Hotbar slot contents with item ids, for quick tool checks. */
    public static JsonObject hotbar() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            throw Errors.notLoaded("the player is not in a world");
        }
        var arr = new com.google.gson.JsonArray();
        for (int i = 0; i < 9; i++) {
            JsonObject o = new JsonObject();
            o.addProperty("slot", i);
            o.add("item", GameState.stack(player.getInventory().getItem(i)));
            arr.add(o);
        }
        JsonObject out = Json.ok();
        out.add("hotbar", arr);
        return out;
    }
}
