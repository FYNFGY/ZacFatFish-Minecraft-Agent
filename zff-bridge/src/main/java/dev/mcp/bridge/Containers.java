package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Container UI operations. Reads are pure; mutations use ClickSlot and always read back. */
public final class Containers {
    private Containers() {
    }

    public static CompletableFuture<JsonObject> open(JsonObject args) {
        int x = Json.i(args, "x", Integer.MIN_VALUE);
        int y = Json.i(args, "y", Integer.MIN_VALUE);
        int z = Json.i(args, "z", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
            throw new Errors.BridgeException(Errors.INTERNAL, "x, y and z are required");
        }
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        CompletableFuture<Void> send = MainThread.submit("container_open", () -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null || mc.level == null) {
                throw Errors.notLoaded("the player is not in a world");
            }
            BlockPos pos = new BlockPos(x, y, z);
            if (mc.level.getBlockState(pos).isAir()) {
                throw Errors.unsupported("no block at " + pos.toShortString());
            }
            Direction face = Actions.parseDirection(Json.str(args, "face", null), player, pos);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
            InteractionResult r = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            if (r == InteractionResult.FAIL) {
                throw Errors.unsupported("the block did not accept a use action");
            }
            return null;
        });
        send.whenComplete((ignored, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            CompletableFuture<JsonObject> wait = TickScheduler.poll(50, tick -> {
                LocalPlayer player = Minecraft.getInstance().player;
                if (player == null) {
                    throw Errors.notLoaded("the player left the world");
                }
                if (player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
                    return snapshot(player);
                }
                return null;
            });
            wait.whenComplete((snap, e) -> {
                if (e != null) {
                    result.completeExceptionally(e);
                } else {
                    result.complete(snap);
                }
            });
        });
        return result;
    }

    public static CompletableFuture<JsonObject> read(boolean full) {
        return MainThread.submit("container_read", () -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) {
                throw Errors.notLoaded("the player is not in a world");
            }
            if (player.containerMenu == null || player.containerMenu == player.inventoryMenu) {
                throw Errors.unsupported("no container menu is open; call container_open first");
            }
            JsonObject snap = snapshot(player);
            return full ? snap : brief(snap);
        });
    }

    public static CompletableFuture<JsonObject> move(JsonObject args) {
        int from = Json.i(args, "fromSlot", Integer.MIN_VALUE);
        if (from == Integer.MIN_VALUE) {
            throw new Errors.BridgeException(Errors.INTERNAL, "fromSlot is required");
        }
        int to = Json.i(args, "toSlot", -1);
        String mode = Json.str(args, "mode", "quick").toLowerCase(Locale.ROOT);
        int count = MathUtil.clamp(Json.i(args, "count", 1), 1, 64);
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        CompletableFuture<JsonObject> started = MainThread.submit("container_move", () -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null) {
                throw Errors.notLoaded("the player is not in a world");
            }
            AbstractContainerMenu menu = player.containerMenu;
            if (menu == null || menu == player.inventoryMenu) {
                throw Errors.unsupported("no container menu is open");
            }
            if (from < 0 || from >= menu.slots.size()) {
                throw new Errors.BridgeException(Errors.INTERNAL, "fromSlot is out of range");
            }
            if (to >= menu.slots.size()) {
                throw new Errors.BridgeException(Errors.INTERNAL, "toSlot is out of range");
            }
            JsonObject ctx = new JsonObject();
            ctx.addProperty("mode", mode);
            ctx.addProperty("from", from);
            ctx.addProperty("to", to);
            ctx.addProperty("fromBefore", stackCount(menu.getSlot(from)));
            if (to >= 0) {
                ctx.addProperty("toBefore", stackCount(menu.getSlot(to)));
            }
            JsonObject before = new JsonObject();
            before.add("from", slotView(menu.getSlot(from), player));
            if (to >= 0) {
                before.add("to", slotView(menu.getSlot(to), player));
            }
            ctx.add("before", before);
            ctx.add("beforeFull", snapshot(player));
            if (!menu.getCarried().isEmpty()) {
                ctx.add("carriedBefore", GameState.stack(menu.getCarried()));
            }
            if (mode.equals("take_one")) {
                ItemStack resultStack = menu.getSlot(from).getItem();
                if (resultStack.isEmpty()) {
                    throw Errors.unsupported("the result slot is empty");
                }
                ctx.addProperty("takeItem", BuiltInRegistries.ITEM.getKey(resultStack.getItem()).toString());
                ctx.addProperty("takeBefore", countInventoryItem(menu, player, resultStack.getItem()));
            }
            Deck deck = new Deck(menu, player, from, to, mode, count);
            deck.apply();
            ctx.add("immediate", snapshot(player));
            return ctx;
        });
        started.whenComplete((ctx, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            CompletableFuture<JsonObject> after = TickScheduler.poll(8, tick -> tick >= 4 ? snapshot(playerOrThrow()) : null);
            after.whenComplete((afterSnap, e) -> {
                JsonObject out = Json.ok();
                out.addProperty("mode", mode);
                out.add("before", ctx.getAsJsonObject("before"));
                if (ctx.has("immediate")) {
                    out.add("immediate", ctx.getAsJsonObject("immediate"));
                }
                if (e != null) {
                    out.addProperty("verified", false);
                    out.addProperty("warning", "read-back failed: " + errorMessage(e));
                    result.complete(out);
                    return;
                }
                out.add("after", brief(afterSnap));
                out.add("changed", diff(ctx.getAsJsonObject("beforeFull"), afterSnap));
                if (ctx.has("carriedBefore")) {
                    out.add("carriedBefore", ctx.get("carriedBefore"));
                }
                int fromBefore = ctx.get("fromBefore").getAsInt();
                int fromAfter = countAt(afterSnap, from);
                int moved = fromBefore - fromAfter;
                boolean verified = moved > 0;
                if (to >= 0) {
                    int toBefore = ctx.has("toBefore") ? ctx.get("toBefore").getAsInt() : 0;
                    int toAfter = countAt(afterSnap, to);
                    verified = moved > 0 && (toAfter >= toBefore || toAfter > 0);
                }
                out.addProperty("verified", verified);
                out.addProperty("moved", moved);
                if (mode.equals("take_one") && ctx.has("takeItem")) {
                    int takeAfter = countSnapshotItem(afterSnap, ctx.get("takeItem").getAsString());
                    int takeBefore = ctx.get("takeBefore").getAsInt();
                    out.addProperty("verified", takeAfter > takeBefore);
                    out.addProperty("moved", takeAfter - takeBefore);
                    if (takeAfter <= takeBefore) {
                        out.addProperty("warning", "the crafted item did not reach the inventory");
                    }
                }
                if (!verified) {
                    out.addProperty("warning", "the client did not observe the expected slot change; the server may have rejected the click");
                }
                result.complete(out);
            });
        });
        return result;
    }

    public static CompletableFuture<JsonObject> close() {
        return MainThread.submit("container_close", () -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) {
                throw Errors.notLoaded("the player is not in a world");
            }
            boolean wasOpen = player.containerMenu != null && player.containerMenu != player.inventoryMenu;
            player.closeContainer();
            JsonObject o = Json.ok();
            o.addProperty("wasOpen", wasOpen);
            return o;
        });
    }

    /**
     * Forces the client back to a clean state: closes any screen, cancels running jobs, stops Baritone
     * and returns a fresh inventory snapshot. A true client/server resync still needs a relog, which
     * the watchdog performs when the human reports a mismatch.
     */
    public static CompletableFuture<JsonObject> resync() {
        return MainThread.submit("resync", () -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null) {
                throw Errors.notLoaded("the player is not in a world");
            }
            player.closeContainer();
            if (mc.screen != null) {
                mc.setScreen(null);
            }
            int cancelled = 0;
            Jobs manager = BridgeRuntime.jobs;
            if (manager != null) {
                cancelled = manager.cancelAll("resync");
            }
            BaritoneControl.cancel();
            JsonArray items = new JsonArray();
            Inventory inv = player.getInventory();
            for (int i = 0; i < inv.items.size(); i++) {
                ItemStack s = inv.getItem(i);
                if (s.isEmpty()) {
                    continue;
                }
                JsonObject o = new JsonObject();
                o.addProperty("slot", i);
                // Inventory index -> InventoryMenu slot (what inventory_swap expects).
                o.addProperty("menuSlot", i <= 8 ? i + 36 : (i <= 35 ? i : (i <= 39 ? 5 + (i - 36) : 45)));
                o.addProperty("id", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
                o.addProperty("count", s.getCount());
                if (s.isDamageableItem()) {
                    o.addProperty("damage", s.getDamageValue());
                    o.addProperty("maxDamage", s.getMaxDamage());
                }
                items.add(o);
            }
            JsonObject o = Json.ok();
            o.addProperty("cancelledJobs", cancelled);
            o.addProperty("menuOpen", player.containerMenu != null && player.containerMenu != player.inventoryMenu);
            o.addProperty("items", items.size());
            o.add("inventory", items);
            o.addProperty("note", "client snapshot only; restart the client for a true server resync");
            return o;
        });
    }

    public static CompletableFuture<JsonObject> trades() {
        return MainThread.submit("villager_trades", () -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) {
                throw Errors.notLoaded("the player is not in a world");
            }
            if (!(player.containerMenu instanceof MerchantMenu menu)) {
                throw Errors.unsupported("no villager merchant menu is open; use entity_interact action=trade first");
            }
            MerchantOffers offers = menu.getOffers();
            JsonArray out = new JsonArray();
            for (int i = 0; i < Math.min(40, offers.size()); i++) {
                MerchantOffer offer = offers.get(i);
                JsonObject o = new JsonObject();
                o.addProperty("index", i);
                o.add("costA", GameState.stack(offer.getCostA()));
                o.add("costB", GameState.stack(offer.getCostB()));
                o.add("result", GameState.stack(offer.getResult()));
                o.addProperty("uses", offer.getUses());
                o.addProperty("maxUses", offer.getMaxUses());
                o.addProperty("xp", offer.getXp());
                o.addProperty("outOfStock", offer.isOutOfStock());
                out.add(o);
            }
            JsonObject o = Json.ok();
            o.add("offers", out);
            o.addProperty("count", out.size());
            return o;
        });
    }

    public static CompletableFuture<JsonObject> tradeSelect(JsonObject args) {
        int index = Json.i(args, "index", -1);
        int times = MathUtil.clamp(Json.i(args, "times", 1), 1, 16);
        if (index < 0) {
            throw new Errors.BridgeException(Errors.INTERNAL, "index is required");
        }
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        CompletableFuture<JsonObject> selected = MainThread.submit("trade_select", () -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null) {
                throw Errors.notLoaded("the player is not in a world");
            }
            if (!(player.containerMenu instanceof MerchantMenu menu)) {
                throw Errors.unsupported("no villager merchant menu is open");
            }
            if (index >= menu.getOffers().size()) {
                throw new Errors.BridgeException(Errors.INTERNAL, "trade index out of range");
            }
            if (player.connection == null) {
                throw Errors.notLoaded("not connected to a world");
            }
            JsonObject ctx = new JsonObject();
            ctx.addProperty("index", index);
            ctx.addProperty("times", times);
            ctx.addProperty("beforeUses", menu.getOffers().get(index).getUses());
            // The server only moves the payment into the trade slots when it receives this packet.
            // Moving items in the client menu (tryMoveItems) desyncs and never executes a trade.
            player.connection.send(new ServerboundSelectTradePacket(index));
            return ctx;
        });
        selected.whenComplete((ctx, error) -> {
            if (error != null) {
                result.completeExceptionally(unwrap(error));
                return;
            }
            TickScheduler.poll(60, tick -> {
                LocalPlayer player = playerOrThrow();
                if (!(player.containerMenu instanceof MerchantMenu menu)) {
                    throw Errors.unsupported("the merchant menu closed before the trade could start");
                }
                boolean paid = !menu.getSlot(0).getItem().isEmpty() || !menu.getSlot(1).getItem().isEmpty();
                boolean ready = !menu.getSlot(2).getItem().isEmpty();
                return paid && ready;
            }).whenComplete((ready, waitError) -> {
                if (waitError != null) {
                    finishTrade(ctx, 0, result);
                } else {
                    clickTradeResult(ctx, 0, result);
                }
            });
        });
        return result;
    }

    private static void clickTradeResult(JsonObject ctx, int done, CompletableFuture<JsonObject> result) {
        int index = ctx.get("index").getAsInt();
        int times = ctx.get("times").getAsInt();
        if (done >= times) {
            finishTrade(ctx, done, result);
            return;
        }
        // The result slot refills after each trade on the next server tick; wait for it.
        TickScheduler.poll(10, tick -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) {
                throw Errors.notLoaded("the player left the world");
            }
            if (!(player.containerMenu instanceof MerchantMenu menu)) {
                return null;
            }
            return menu.getSlot(2).getItem().isEmpty() ? null : Boolean.TRUE;
        }).whenComplete((ready, readyError) -> {
            if (readyError != null) {
                finishTrade(ctx, done, result);
                return;
            }
            sendTradePickup(ctx, done, index, result);
        });
    }

    private static void sendTradePickup(JsonObject ctx, int done, int index, CompletableFuture<JsonObject> result) {
        CompletableFuture<JsonObject> picked = MainThread.submit("trade_select", () -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null) {
                throw Errors.notLoaded("the player is not in a world");
            }
            if (!(player.containerMenu instanceof MerchantMenu menu)) {
                throw Errors.unsupported("no villager merchant menu is open");
            }
            if (index >= menu.getOffers().size()) {
                throw new Errors.BridgeException(Errors.INTERNAL, "trade index out of range");
            }
            MerchantOffer offer = menu.getOffers().get(index);
            JsonObject info = new JsonObject();
            info.addProperty("resultCount", offer.getResult().getCount());
            info.addProperty("costCount", offer.getCostA().getCount());
            info.addProperty("resultBefore", countItem(menu, player, offer.getResult()));
            info.addProperty("costBefore", countItem(menu, player, offer.getCostA()));
            // PICKUP on the result slot performs exactly one trade and puts the result on the cursor;
            // QUICK_MOVE would trade until the payment stack is exhausted.
            mc.gameMode.handleInventoryMouseClick(menu.containerId, 2, 0, ClickType.PICKUP, player);
            return info;
        });
        picked.whenComplete((info, error) -> {
            if (error != null) {
                result.completeExceptionally(unwrap(error));
                return;
            }
            // The server confirms the click and syncs the cursor a couple of ticks later.
            TickScheduler.poll(10, tick -> tick >= 5 ? Boolean.TRUE : null).whenComplete((waited, waitError) -> {
                CompletableFuture<JsonObject> stored = MainThread.submit("trade_select", () -> {
                    LocalPlayer player = Minecraft.getInstance().player;
                    if (player == null) {
                        throw Errors.notLoaded("the player left the world");
                    }
                    if (!(player.containerMenu instanceof MerchantMenu menu)) {
                        throw Errors.unsupported("the trade menu closed");
                    }
                    storeCursor(menu, player);
                    return info;
                });
                stored.whenComplete((ignored, storeError) -> {
                    if (storeError != null) {
                        result.completeExceptionally(unwrap(storeError));
                        return;
                    }
                    TickScheduler.poll(10, tick -> tick >= 5 ? Boolean.TRUE : null).whenComplete((waited2, waitError2) -> {
                        CompletableFuture<JsonObject> measured = MainThread.submit("trade_select", () -> {
                            LocalPlayer player = Minecraft.getInstance().player;
                            if (player == null) {
                                throw Errors.notLoaded("the player left the world");
                            }
                            if (!(player.containerMenu instanceof MerchantMenu menu)) {
                                throw Errors.unsupported("the trade menu closed");
                            }
                            MerchantOffer offer = menu.getOffers().get(index);
                            int resultAfter = countItem(menu, player, offer.getResult());
                            int costAfter = countItem(menu, player, offer.getCostA());
                            info.addProperty("resultAfter", resultAfter);
                            info.addProperty("costAfter", costAfter);
                            boolean confirmed = resultAfter >= info.get("resultBefore").getAsInt() + info.get("resultCount").getAsInt()
                                    || costAfter <= info.get("costBefore").getAsInt() - info.get("costCount").getAsInt();
                            info.addProperty("confirmed", confirmed);
                            return info;
                        });
                        measured.whenComplete((measuredInfo, measureError) -> {
                            if (measureError != null) {
                                result.completeExceptionally(unwrap(measureError));
                                return;
                            }
                            if (measuredInfo.get("confirmed").getAsBoolean()) {
                                clickTradeResult(ctx, done + 1, result);
                            } else {
                                finishTrade(ctx, done, result);
                            }
                        });
                    });
                });
            });
        });
    }

    /** Puts the cursor stack into the first matching or empty player slot of the merchant menu. */
    private static void storeCursor(MerchantMenu menu, LocalPlayer player) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            return;
        }
        int target = -1;
        for (int i = 3; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (slot.container != player.getInventory()) {
                continue;
            }
            ItemStack current = slot.getItem();
            if (current.isEmpty()) {
                target = i;
                break;
            }
            if (ItemStack.isSameItemSameComponents(current, carried) && current.getCount() < slot.getMaxStackSize()) {
                target = i;
                break;
            }
        }
        if (target >= 0) {
            Minecraft.getInstance().gameMode.handleInventoryMouseClick(menu.containerId, target, 0, ClickType.PICKUP, player);
        }
    }

    /** Counts an item in the player inventory rows of a menu, including the cursor stack. */
    private static int countItem(AbstractContainerMenu menu, LocalPlayer player, ItemStack like) {
        if (like == null || like.isEmpty()) {
            return 0;
        }
        int total = 0;
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty() && ItemStack.isSameItemSameComponents(carried, like)) {
            total += carried.getCount();
        }
        for (int i = 3; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (slot.container != player.getInventory()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, like)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Counts an item in the player inventory rows of any menu. */
    private static int countInventoryItem(AbstractContainerMenu menu, LocalPlayer player, Item item) {
        int total = 0;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (slot.container != player.getInventory()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int countSnapshotItem(JsonObject snapshot, String itemId) {
        int total = 0;
        JsonArray slots = snapshot.getAsJsonArray("slots");
        if (slots == null) {
            return 0;
        }
        for (JsonElement e : slots) {
            JsonObject slot = e.getAsJsonObject();
            JsonObject item = slot.getAsJsonObject("item");
            if (item != null && itemId.equals(item.get("id").getAsString())) {
                total += item.get("count").getAsInt();
            }
        }
        return total;
    }

    /** Stores the cursor stack into any matching or empty player slot of the open menu. */
    private static void storeCursorGeneric(AbstractContainerMenu menu, LocalPlayer player) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            return;
        }
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (slot.container != player.getInventory()) {
                continue;
            }
            ItemStack current = slot.getItem();
            if (current.isEmpty() || (ItemStack.isSameItemSameComponents(current, carried) && current.getCount() < slot.getMaxStackSize())) {
                Minecraft.getInstance().gameMode.handleInventoryMouseClick(menu.containerId, i, 0, ClickType.PICKUP, player);
                return;
            }
        }
    }

    private static void finishTrade(JsonObject ctx, int trades, CompletableFuture<JsonObject> result) {
        int index = ctx.get("index").getAsInt();
        TickScheduler.poll(10, tick -> tick >= 5 ? Boolean.TRUE : null).whenComplete((ignored, error) -> {
            JsonObject out = Json.ok();
            out.addProperty("index", index);
            out.addProperty("times", ctx.get("times").getAsInt());
            out.addProperty("tradesExecuted", trades);
            out.addProperty("beforeUses", ctx.get("beforeUses").getAsInt());
            int afterUses = -1;
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null && player.containerMenu instanceof MerchantMenu menu && index < menu.getOffers().size()) {
                afterUses = menu.getOffers().get(index).getUses();
            }
            out.addProperty("afterUses", afterUses);
            out.addProperty("verified", trades > 0);
            out.addProperty("verifiedBy", "item-count");
            if (player != null) {
                out.add("after", snapshot(player));
            }
            if (trades <= 0) {
                out.addProperty("warning", "no trade was confirmed by item movement; check the payment items and the villager");
            }
            result.complete(out);
        });
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static LocalPlayer playerOrThrow() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            throw Errors.notLoaded("the player left the world");
        }
        return player;
    }

    private static int countAt(JsonObject snapshot, int slot) {
        JsonArray slots = snapshot.getAsJsonArray("slots");
        if (slots == null || slot < 0 || slot >= slots.size()) {
            return 0;
        }
        JsonObject s = slots.get(slot).getAsJsonObject();
        JsonObject item = s.getAsJsonObject("item");
        return item == null ? 0 : item.get("count").getAsInt();
    }

    private static int stackCount(Slot slot) {
        return slot.getItem() == null ? 0 : slot.getItem().getCount();
    }

    private static JsonObject snapshot(LocalPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        JsonObject o = new JsonObject();
        o.addProperty("menuClass", menu.getClass().getName());
        o.addProperty("containerId", menu.containerId);
        o.addProperty("slotCount", menu.slots.size());
        o.add("carried", GameState.stack(menu.getCarried()));
        if (Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> screen) {
            o.addProperty("title", screen.getTitle().getString());
        }
        JsonArray slots = new JsonArray();
        for (int i = 0; i < Math.min(100, menu.slots.size()); i++) {
            slots.add(slotView(menu.getSlot(i), player));
        }
        o.add("slots", slots);
        o.addProperty("truncated", menu.slots.size() > 100);
        return o;
    }

    private static JsonObject slotView(Slot slot, LocalPlayer player) {
        JsonObject o = new JsonObject();
        o.addProperty("index", slot.index);
        o.addProperty("containerSlot", slot.getContainerSlot());
        o.addProperty("playerInventory", slot.container == player.getInventory());
        o.addProperty("active", slot.isActive());
        o.addProperty("maxStack", slot.getMaxStackSize());
        o.add("item", GameState.stack(slot.getItem()));
        return o;
    }

    /**
     * P7-17(b): the old response always carried ~90 slots (container + 36 inventory), which is a huge
     * token cost for a one-item move. The brief view keeps only non-empty slots + cursor; `full:true`
     * still returns everything.
     */
    private static JsonObject brief(JsonObject snap) {
        JsonObject o = new JsonObject();
        for (String key : new String[]{"menuClass", "containerId", "slotCount", "carried", "title", "truncated"}) {
            if (snap.has(key)) {
                o.add(key, snap.get(key));
            }
        }
        JsonArray slots = new JsonArray();
        JsonArray all = snap.getAsJsonArray("slots");
        int nonEmpty = 0;
        if (all != null) {
            for (JsonElement e : all) {
                JsonObject slot = e.getAsJsonObject();
                JsonObject item = slot.getAsJsonObject("item");
                if (item != null && item.has("count") && item.get("count").getAsInt() > 0) {
                    slots.add(slot);
                    nonEmpty++;
                }
            }
        }
        o.add("slots", slots);
        o.addProperty("nonEmpty", nonEmpty);
        o.addProperty("full", false);
        o.addProperty("note", "empty slots omitted; pass full:true for every slot");
        return o;
    }

    /** Slot-by-slot diff for `container_move`: only what actually changed. */
    private static JsonArray diff(JsonObject before, JsonObject after) {
        JsonArray changed = new JsonArray();
        JsonArray a = before.getAsJsonArray("slots");
        JsonArray b = after.getAsJsonArray("slots");
        if (a == null || b == null) {
            return changed;
        }
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            JsonObject sa = a.get(i).getAsJsonObject();
            JsonObject sb = b.get(i).getAsJsonObject();
            JsonObject ia = sa.getAsJsonObject("item");
            JsonObject ib = sb.getAsJsonObject("item");
            int ca = ia == null ? 0 : ia.get("count").getAsInt();
            int cb = ib == null ? 0 : ib.get("count").getAsInt();
            String ida = ia == null ? "" : ia.get("id").getAsString();
            String idb = ib == null ? "" : ib.get("id").getAsString();
            if (ca != cb || !ida.equals(idb)) {
                JsonObject c = new JsonObject();
                c.addProperty("slot", i);
                c.addProperty("from", ida.isEmpty() ? "empty" : ida + " x" + ca);
                c.addProperty("to", idb.isEmpty() ? "empty" : idb + " x" + cb);
                changed.add(c);
            }
        }
        return changed;
    }

    private static String errorMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }

    /** Performs the click sequence for one move request, on the client thread. */
    private static final class Deck {
        private final Minecraft mc = Minecraft.getInstance();
        private final AbstractContainerMenu menu;
        private final LocalPlayer player;
        private final int from;
        private final int to;
        private final String mode;
        private final int count;

        private Deck(AbstractContainerMenu menu, LocalPlayer player, int from, int to, String mode, int count) {
            this.menu = menu;
            this.player = player;
            this.from = from;
            this.to = to;
            this.mode = mode;
            this.count = count;
        }

        private void apply() {
            // 2026-11-02: take = container -> player, put = player -> container. The old code treated
            // both as an undirected shift-click, so a furnace "put" could move the wrong direction.
            if (mode.equals("take")) {
                if (menu.getSlot(from).container == player.getInventory()) {
                    throw Errors.unsupported("take needs a container source slot; use put for player->container");
                }
                if (!menu.getCarried().isEmpty()) storeCursorGeneric(menu, player);
                mc.gameMode.handleInventoryMouseClick(menu.containerId, from, 0, ClickType.QUICK_MOVE, player);
                return;
            }
            if (mode.equals("put")) {
                if (menu.getSlot(from).container != player.getInventory()) {
                    throw Errors.unsupported("put needs a player-inventory source slot; use take for container->player");
                }
                if (!menu.getCarried().isEmpty()) storeCursorGeneric(menu, player);
                mc.gameMode.handleInventoryMouseClick(menu.containerId, from, 0, ClickType.QUICK_MOVE, player);
                return;
            }
            if (mode.equals("move")) {
                if (!menu.getCarried().isEmpty()) storeCursorGeneric(menu, player);
                ItemStack src = menu.getSlot(from).getItem();
                if (src.isEmpty()) throw Errors.unsupported("the source slot is empty");
                int n = count <= 1 ? src.getCount() : Math.min(count, src.getCount());
                mc.gameMode.handleInventoryMouseClick(menu.containerId, from, 0, ClickType.PICKUP, player);
                if (n >= src.getCount()) {
                    mc.gameMode.handleInventoryMouseClick(menu.containerId, to, 0, ClickType.PICKUP, player);
                } else {
                    for (int i = 0; i < n; i++) {
                        mc.gameMode.handleInventoryMouseClick(menu.containerId, to, 1, ClickType.PICKUP, player);
                    }
                    // Do not leave the remainder stuck on the cursor.
                    storeCursorGeneric(menu, player);
                }
                return;
            }
            boolean quick = mode.equals("quick") || to < 0;
            if (quick) {
                // P7-17(a): shift-click while the cursor holds a stack is interpreted as "drop the
                // cursor here", so the move silently does nothing. Park the cursor in the player
                // inventory first (same packet order, server sees an empty cursor).
                if (!menu.getCarried().isEmpty()) {
                    storeCursorGeneric(menu, player);
                }
                mc.gameMode.handleInventoryMouseClick(menu.containerId, from, 0, ClickType.QUICK_MOVE, player);
                return;
            }
            if (mode.equals("take_one")) {
                // Exactly one craft: pick the result up, then store the cursor in the inventory.
                mc.gameMode.handleInventoryMouseClick(menu.containerId, from, 0, ClickType.PICKUP, player);
                storeCursorGeneric(menu, player);
                return;
            }
            if (mode.equals("split")) {
                mc.gameMode.handleInventoryMouseClick(menu.containerId, from, 1, ClickType.PICKUP, player);
                mc.gameMode.handleInventoryMouseClick(menu.containerId, to, 0, ClickType.PICKUP, player);
                return;
            }
            mc.gameMode.handleInventoryMouseClick(menu.containerId, from, 0, ClickType.PICKUP, player);
            for (int i = 0; i < count; i++) {
                mc.gameMode.handleInventoryMouseClick(menu.containerId, to, 1, ClickType.PICKUP, player);
            }
        }
    }
}
