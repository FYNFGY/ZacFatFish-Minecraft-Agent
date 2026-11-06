package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * Ph6 ISSUE 修复用的工具（由 {@link Tools} 的 default 分支转进来，见 docs/ph6/ISSUE-TOOLS.md）。
 *
 * <p>本类只放“新工具”，不改动 {@link Tools} 里已有的 84 个工具。返回约定与 Tools 完全一致：
 * <ul>
 *   <li>同步工具走 {@link MainThread#submit} + {@link MainThread#await}，HTTP 工作线程等待；</li>
 *   <li>需要跨 tick 的工具用 {@link TickScheduler#poll}，绝不在客户端线程上阻塞；</li>
 *   <li>长任务返回 {@link Jobs.Job}（{@link BridgeRuntime#jobs}），与 walk/mine_block 一样。</li>
 * </ul>
 *
 * <p>{@link #call} 返回 null 表示“不认识这个工具”，让上层照常报 unknown tool。</p>
 */
public final class IssueTools {
    private IssueTools() {
    }

    public static Object call(String tool, JsonObject args, int requestedTimeout) {
        String name = tool == null ? "" : tool.trim().toLowerCase(Locale.ROOT);
        JsonObject a = args == null ? new JsonObject() : args;
        return switch (name) {
            // ISSUE-2：睡觉后起床。
            case "wake", "get_up", "stop_sleeping" -> wake(a, requestedTimeout);
            // ISSUE-3：完整“使用物品/进食”状态机（按住 32 tick 再释放）。
            case "eat", "consume" -> runUse(a, requestedTimeout, true);
            case "use_item" -> runUse(a, requestedTimeout, false);
            // ISSUE-14/15：读书、读告示牌、看物品完整 components。
            case "read_book" -> sync("read_book", requestedTimeout, 5, () -> readBook(a));
            case "read_sign" -> sync("read_sign", requestedTimeout, 3, () -> readSign(a));
            case "item_info" -> sync("item_info", requestedTimeout, 3, () -> itemInfo(a));
            // ISSUE-15：含 GUI 的截图（Tools 里的 screenshot 仍走旧路径；主代理可改传 args）。
            case "screenshot_gui", "screenshot_with_gui" ->
                    await(ScreenshotCapture.request(true), timeoutMs(requestedTimeout, 5));
            case "screenshot" -> await(ScreenshotCapture.request(a), timeoutMs(requestedTimeout, 5));
            // ISSUE-7：语义化移动（yaw 的“世界朝向”映射见 docs）。
            case "walk_dir" -> walkDir(a);
            // ISSUE-5：Tools 里的 path_open 仍走 4 参构造（默认不开箱）；这个别名把 openContainers
            // 透传给 PathOpenJob。主代理也可以改成在 Tools.pathOpen 里直接透传 args，二选一。
            case "path_open_ex", "path_open2" -> pathOpenEx(a);
            default -> null;
        };
    }

    // ------------------------------------------------------------------ 通用包装（与 Tools 同形）

    private static JsonObject sync(String tool, int requestedTimeout, int defaultSeconds,
                                   Callable<JsonObject> action) {
        return MainThread.await(MainThread.submit(tool, action), timeoutMs(requestedTimeout, defaultSeconds));
    }

    private static JsonObject await(CompletableFuture<JsonObject> future, long timeoutMs) {
        return MainThread.await(future, timeoutMs);
    }

    private static int timeoutSeconds(int requested, int def) {
        int v = requested > 0 ? requested : def;
        return MathUtil.clamp(v, 1, 600);
    }

    private static long timeoutMs(int requested, int def) {
        // 同步工具不能长期占住 HTTP worker；需要更久的操作请用 job。
        return Math.min(30, timeoutSeconds(requested, def)) * 1000L;
    }

    // ------------------------------------------------------------------ ISSUE-2：wake

    /**
     * 起床：客户端发 {@code ServerboundPlayerCommandPacket(STOP_SLEEPING)}，然后逐 tick 回读
     * {@code player.isSleeping()}（该值来自 LivingEntity 的同步 entity data，服务端清掉会同步回来）。
     */
    private static JsonObject wake(JsonObject args, int requestedTimeout) {
        CompletableFuture<JsonObject> started = MainThread.submit("wake", () -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null) {
                throw Errors.notLoaded("the player is not in a world");
            }
            JsonObject ctx = Json.ok();
            boolean sleeping = player.isSleeping();
            ctx.addProperty("sleepingBefore", sleeping);
            ctx.addProperty("screen", mc.screen == null ? "" : mc.screen.getClass().getName());
            player.getSleepingPos().ifPresent(pos ->
                    ctx.add("bedPos", Json.intArray(pos.getX(), pos.getY(), pos.getZ())));
            if (!sleeping) {
                ctx.addProperty("sleeping", false);
                ctx.addProperty("woke", false);
                ctx.addProperty("note", "the player is not sleeping");
                return ctx;
            }
            if (player.connection == null) {
                throw Errors.notLoaded("the client is not connected to a server");
            }
            player.connection.send(new ServerboundPlayerCommandPacket(
                    player, ServerboundPlayerCommandPacket.Action.STOP_SLEEPING));
            ctx.addProperty("packet", "STOP_SLEEPING");
            return ctx;
        });
        return MainThread.await(started.thenCompose(ctx -> {
            if (!ctx.get("sleepingBefore").getAsBoolean()) {
                return CompletableFuture.completedFuture(ctx);
            }
            return TickScheduler.poll(60, tick -> {
                Minecraft mc = Minecraft.getInstance();
                LocalPlayer player = mc.player;
                if (player == null) {
                    throw Errors.notLoaded("the player left the world");
                }
                if (!player.isSleeping()) {
                    ctx.addProperty("sleeping", false);
                    ctx.addProperty("woke", true);
                    ctx.addProperty("ticks", tick + 1);
                    // 床上的 InBedChatScreen 由客户端自己关；画面还挂着时顺手关掉，避免挡住后续 GUI 操作。
                    if (mc.screen != null
                            && mc.screen.getClass().getName().toLowerCase(Locale.ROOT).contains("inbed")) {
                        mc.setScreen(null);
                        ctx.addProperty("screenClosed", true);
                    }
                    return ctx;
                }
                if (tick >= 59) {
                    // 60 tick 内没醒：不抛 timeout，返回可诊断的结果（服端可能拒绝/插件接管）。
                    ctx.addProperty("sleeping", true);
                    ctx.addProperty("woke", false);
                    ctx.addProperty("ticks", 60);
                    ctx.addProperty("reason", "still sleeping after 60 ticks; the server may have refused STOP_SLEEPING");
                    return ctx;
                }
                return null;
            });
        }), timeoutMs(requestedTimeout, 10));
    }

    // ------------------------------------------------------------------ ISSUE-3：eat / use_item

    /**
     * 完整的“使用物品”状态机：选中 → {@code gameMode.useItem} → 逐 tick 保持 isUsingItem
     * → 用完或超时后 {@code releaseUsingItem()} → 回读 food/物品数量。</p>
     *
     * <p>{@code requireFood=true} 时对应 {@code eat}（只接受带 FOOD 组件的物品）；false 时对应
     * {@code use_item}（弓/药水/食物都行）。</p>
     */
    private static JsonObject runUse(JsonObject args, int requestedTimeout, boolean requireFood) {
        int maxTicks = MathUtil.clamp(Json.i(args, "maxTicks", 140), 20, 400);
        int slotArg = Json.i(args, "slot", -1);
        String itemArg = Json.str(args, "item", null);
        String handArg = Json.str(args, "hand", null);
        boolean offhand = "off".equalsIgnoreCase(handArg) || "offhand".equalsIgnoreCase(handArg);
        String tool = requireFood ? "eat" : "use_item";
        CompletableFuture<JsonObject> started = MainThread.submit(tool, () -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            ClientLevel level = mc.level;
            if (player == null || level == null) {
                throw Errors.notLoaded("the player is not in a world");
            }
            if (mc.gameMode == null) {
                throw Errors.notLoaded("the game mode is not ready");
            }
            JsonObject ctx = Json.ok();
            int hotbarSlot = player.getInventory().selected;
            int resolvedSlot = -1;
            if (!offhand) {
                int slot = slotArg;
                if (slot < 0 && itemArg != null && !itemArg.isBlank()) {
                    slot = Actions.findItemSlotAll(player, itemArg);
                    if (slot < 0) {
                        throw Errors.unsupported("no item matching '" + itemArg + "' in the inventory");
                    }
                }
                if (slot >= 0) {
                    if (slot > 8) {
                        // 主背包（9..35）里的物品：先换到当前快捷栏槽，再选中。
                        if (!InventoryActions.quickSwap(player, slot, hotbarSlot)) {
                            throw new Errors.BridgeException(Errors.INTERNAL,
                                    "could not move inventory slot " + slot + " into hotbar slot " + hotbarSlot);
                        }
                        ctx.addProperty("swappedFrom", slot);
                        ctx.addProperty("swappedInto", hotbarSlot);
                        slot = hotbarSlot;
                    }
                    if (slot > 8) {
                        throw new Errors.BridgeException(Errors.INTERNAL, "slot must be 0..35");
                    }
                    Actions.selectSlot(player, slot);
                    resolvedSlot = slot;
                }
            }
            InteractionHand hand = offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            ItemStack held = player.getItemInHand(hand);
            if (held.isEmpty()) {
                throw Errors.unsupported("the " + (offhand ? "off" : "main") + " hand is empty");
            }
            String itemId = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
            if (requireFood && !held.has(DataComponents.FOOD)) {
                throw Errors.unsupported("the selected item is not food: " + itemId);
            }
            ctx.addProperty("item", itemId);
            ctx.addProperty("hand", offhand ? "off" : "main");
            if (resolvedSlot >= 0) {
                ctx.addProperty("slot", resolvedSlot);
            }
            ctx.addProperty("countBefore", countItem(player, itemId));
            ctx.addProperty("foodBefore", player.getFoodData().getFoodLevel());
            ctx.addProperty("saturationBefore", MathUtil.round2(player.getFoodData().getSaturationLevel()));
            ctx.addProperty("healthBefore", MathUtil.round2(player.getHealth()));
            // 🔴 P7-11 根因（2026-11-02 现场取证）：只调 gameMode.useItem 会让客户端进入 using，但
            //    Minecraft 的 handleKeybinds 每 tick 检查“右键是否按住”，没按住就 stopUsingItem；
            //    于是 using 在 1 tick 后就变 false，食物一口都吃不到（using:true→false、ticks:1）。
            //    真正按住 keyUse 才能维持 using 到 32 tick 吃完；吃完/超时在下面统一松开。
            if (mc.screen != null) {
                mc.setScreen(null);
            }
            mc.options.keyUse.setDown(true);
            InteractionResult result = mc.gameMode.useItem(player, hand);
            player.swing(hand);
            ctx.addProperty("result", String.valueOf(result));
            boolean using = player.isUsingItem();
            ctx.addProperty("using", using);
            if (using) {
                int duration = player.getUseItem().getUseDuration(player);
                ctx.addProperty("useDuration", duration);
                ctx.addProperty("useItemRemaining", player.getUseItemRemainingTicks());
            } else {
                ctx.addProperty("note", "the item did not enter the using state (not food/consumable, or the server refused)");
            }
            return ctx;
        });
        CompletableFuture<JsonObject> chain = started.thenCompose(ctx -> {
            final String itemId = ctx.get("item").getAsString();
            final int countBefore = ctx.get("countBefore").getAsInt();
            final int foodBefore = ctx.get("foodBefore").getAsInt();
            final InteractionHand hand = "off".equals(ctx.get("hand").getAsString())
                    ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            // 🔴 P7-11 现场取证（2026-11-02）：客户端进入 using 后**下一个 tick 就被停掉**
            //    （`using:true` → tick1 `using:false`），所以只看 `isUsingItem()` 会在一 tick 后误判为
            //    "没吃到"。真实原因不是物品/服务器，而是客户端的右键状态在无焦点/自动化环境下保不住：
            //    Minecraft 每 tick 都会因为右键没按住而 stopUsingItem。这里做三件事：
            //    ① 每 tick 重新按住 keyUse；② 客户端若被停掉，就重新 startUsingItem（服务器端计时从
            //    第一次 UseItem 包开始，与服务端食物结算无关）；③ 用**食物/数量变化**作为唯一成功判据，
            //    并且不再因为"这一 tick 没在 using"就提前收尾。
            final boolean[] began = { ctx.get("using").getAsBoolean() };
            final int[] restarts = { 0 };
            return TickScheduler.poll(maxTicks, tick -> {
                Minecraft mc = Minecraft.getInstance();
                LocalPlayer player = mc.player;
                if (player == null) {
                    throw Errors.notLoaded("the player left the world");
                }
                // Keep the synthetic right-click held for the whole use; some environments clear it.
                mc.options.keyUse.setDown(true);
                boolean stillUsing = player.isUsingItem();
                if (stillUsing) {
                    began[0] = true;
                }
                int foodAfter = player.getFoodData().getFoodLevel();
                int countAfter = countItem(player, itemId);
                boolean consumed = foodAfter > foodBefore || countAfter < countBefore;
                if (!began[0] && !consumed && tick + 1 < maxTicks) {
                    return null;                      // 还没进入 using：再等一 tick
                }
                // Only finish when the world says it worked, the deadline expired, or the restart budget
                // is exhausted. A transient `using:false` is not a failure.
                if (!consumed && stillUsing && tick + 1 < maxTicks) {
                    return null;
                }
                if (!consumed && !stillUsing && tick < 80 && tick + 1 < maxTicks) {
                    try {
                        player.startUsingItem(hand);
                        restarts[0]++;
                    } catch (Throwable ignored) {
                        // If the client refuses to start again, wait for the server-side result instead.
                    }
                    return null;
                }
                int remaining = stillUsing ? player.getUseItemRemainingTicks() : 0;
                if (stillUsing) {
                    // 超时兜底：松开右键，别把玩家卡在“一直吃”状态。
                    player.releaseUsingItem();
                    player.stopUsingItem();
                }
                ctx.addProperty("using", player.isUsingItem());
                ctx.addProperty("ticks", tick + 1);
                ctx.addProperty("useItemRemaining", remaining);
                ctx.addProperty("foodAfter", foodAfter);
                ctx.addProperty("saturationAfter", MathUtil.round2(player.getFoodData().getSaturationLevel()));
                ctx.addProperty("healthAfter", MathUtil.round2(player.getHealth()));
                ctx.addProperty("countAfter", countAfter);
                ctx.addProperty("consumed", consumed);
                if (restarts[0] > 0) {
                    ctx.addProperty("restarts", restarts[0]);
                }
                if (!consumed) {
                    // 没真吃到/没用掉：不要报 SUCCESS（P1-4：`result:SUCCESS` + `consumed:false` 自相矛盾）
                    ctx.addProperty("result", "incomplete");
                    ctx.addProperty("note", "the use did not consume anything (food level and item count unchanged) - "
                            + "the item never entered the using state or the server refused it");
                } else if (remaining > 0) {
                    ctx.addProperty("result", "consumed_early");
                } else {
                    ctx.addProperty("result", "consumed");
                }
                if (stillUsing) {
                    ctx.addProperty("release", "forced after " + maxTicks + " ticks");
                }
                releaseUse();
                return ctx;
            });
        });
        chain.whenComplete((r, e) -> releaseUse());
        return MainThread.await(chain, timeoutMs(requestedTimeout, 20));
    }

    /** Release the synthetic right-click. Safe from any thread: it only writes the key state. */
    private static void releaseUse() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.options != null && mc.options.keyUse != null) {
                mc.options.keyUse.setDown(false);
            }
        } catch (Throwable ignored) {
            // never let cleanup break the tool result
        }
    }

    /** 统计 36 个背包槽里某个物品的总数量。 */
    private static int countItem(LocalPlayer player, String itemId) {
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            if (BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(itemId)) {
                total += s.getCount();
            }
        }
        return total;
    }

    // ------------------------------------------------------------------ ISSUE-14：read_book

    private static JsonObject readBook(JsonObject args) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            throw Errors.notLoaded("the player is not in a world");
        }
        ItemStack stack = resolveStack(player, args);
        if (stack.isEmpty()) {
            throw Errors.unsupported("the selected slot is empty");
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        JsonObject book = bookJson(stack);
        if (book == null) {
            throw Errors.unsupported("the item is not a written_book or writable_book: " + id);
        }
        JsonObject o = Json.ok();
        o.addProperty("item", id);
        for (String key : book.keySet()) {
            o.add(key, book.get(key));
        }
        return o;
    }

    /** 书页内容（written_book 的 title/author/pages，或 writable_book 的 pages）。 */
    private static JsonObject bookJson(ItemStack stack) {
        WrittenBookContent written = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (written != null) {
            JsonObject o = new JsonObject();
            o.addProperty("bookType", "written_book");
            o.addProperty("title", written.title().get(false));
            o.addProperty("author", written.author());
            o.addProperty("generation", written.generation());
            o.addProperty("resolved", written.resolved());
            JsonArray pages = new JsonArray();
            List<Component> rawPages = written.getPages(false);
            for (Component page : rawPages) {
                pages.add(page == null ? "" : page.getString());
            }
            o.add("pages", pages);
            o.addProperty("pageCount", pages.size());
            return o;
        }
        WritableBookContent writable = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
        if (writable != null) {
            JsonObject o = new JsonObject();
            o.addProperty("bookType", "writable_book");
            o.addProperty("title", "");
            o.addProperty("author", "");
            JsonArray pages = new JsonArray();
            List<String> rawPages = writable.getPages(false).toList();
            for (String page : rawPages) {
                pages.add(page == null ? "" : page);
            }
            o.add("pages", pages);
            o.addProperty("pageCount", pages.size());
            return o;
        }
        return null;
    }

    // ------------------------------------------------------------------ ISSUE-15：read_sign

    private static JsonObject readSign(JsonObject args) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            throw Errors.notLoaded("the player is not in a world");
        }
        BlockPos pos = blockPos(args);
        if (pos == null) {
            throw new Errors.BridgeException(Errors.INTERNAL, "x, y and z are required (or pos:[x,y,z])");
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof SignBlockEntity sign)) {
            String id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
            throw Errors.unsupported("no sign block entity at " + pos.toShortString() + " (block: " + id + ")");
        }
        JsonObject o = Json.ok();
        o.add("pos", Json.intArray(pos.getX(), pos.getY(), pos.getZ()));
        o.addProperty("block", BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString());
        o.addProperty("isWaxed", sign.isWaxed());
        o.add("front", signLines(sign.getFrontText()));
        o.add("back", signLines(sign.getBackText()));
        return o;
    }

    private static JsonArray signLines(SignText text) {
        JsonArray a = new JsonArray();
        if (text == null) {
            return a;
        }
        for (Component line : text.getMessages(false)) {
            a.add(line == null ? "" : line.getString());
        }
        return a;
    }

    // ------------------------------------------------------------------ ISSUE-15：item_info

    private static JsonObject itemInfo(JsonObject args) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            throw Errors.notLoaded("the player is not in a world");
        }
        int slotArg = Json.i(args, "slot", -1);
        ItemStack stack = resolveStack(player, args);
        JsonObject o = Json.ok();
        o.add("item", GameState.stack(stack));
        o.addProperty("empty", stack.isEmpty());
        if (slotArg >= 0 && slotArg < 36) {
            o.addProperty("slot", slotArg);
        }
        o.addProperty("hand", isOffhand(args) ? "off" : "main");
        if (stack.isEmpty()) {
            return o;
        }
        DynamicOps<JsonElement> ops = JsonOps.INSTANCE;
        ClientLevel level = mc.level;
        if (level != null) {
            try {
                ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
            } catch (Throwable ignored) {
                // 没有 registry 上下文也能编码大部分组件，失败时退回 String.valueOf。
            }
        }
        JsonObject components = new JsonObject();
        int count = 0;
        for (TypedDataComponent<?> component : stack.getComponents()) {
            ResourceLocation key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type());
            String name = key == null ? String.valueOf(component.type()) : key.toString();
            if (component.type() == DataComponents.WRITTEN_BOOK_CONTENT
                    || component.type() == DataComponents.WRITABLE_BOOK_CONTENT) {
                // 书页要展开成纯文本，不能只回 codec 的 JSON 结构。
                JsonObject book = bookJson(stack);
                if (book != null) {
                    components.add(name, book);
                    count++;
                    continue;
                }
            }
            JsonElement value = null;
            try {
                DataResult<JsonElement> encoded = component.encodeValue(ops);
                value = encoded.result().orElse(null);
            } catch (Throwable ignored) {
                // fall through
            }
            if (value == null || value.isJsonNull()) {
                value = new JsonPrimitive(String.valueOf(component.value()));
            }
            components.add(name, shorten(value, 1200));
            count++;
        }
        o.add("components", components);
        o.addProperty("componentCount", count);
        JsonObject book = bookJson(stack);
        if (book != null) {
            o.add("book", book);
        }
        return o;
    }

    private static JsonElement shorten(JsonElement value, int maxChars) {
        String json = Json.GSON.toJson(value);
        if (json.length() <= maxChars) {
            return value;
        }
        return new JsonPrimitive(json.substring(0, maxChars) + "...(" + json.length() + " chars)");
    }

    // ------------------------------------------------------------------ 工具函数

    private static boolean isOffhand(JsonObject args) {
        String hand = Json.str(args, "hand", null);
        return "off".equalsIgnoreCase(hand) || "offhand".equalsIgnoreCase(hand);
    }

    /** 解析 slot（0..35）/ hand（main/off）/ 默认主手。只读工具用，不交换背包。 */
    private static ItemStack resolveStack(LocalPlayer player, JsonObject args) {
        if (isOffhand(args)) {
            return player.getOffhandItem();
        }
        int slot = Json.i(args, "slot", -1);
        if (slot >= 0 && slot < 36) {
            return player.getInventory().getItem(slot);
        }
        return player.getMainHandItem();
    }

    private static BlockPos blockPos(JsonObject args) {
        JsonArray arr = Json.arr(args, "pos");
        if (arr != null && arr.size() >= 3) {
            return new BlockPos(arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt());
        }
        int x = Json.i(args, "x", Integer.MIN_VALUE);
        int y = Json.i(args, "y", Integer.MIN_VALUE);
        int z = Json.i(args, "z", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
            return null;
        }
        return new BlockPos(x, y, z);
    }

    // ------------------------------------------------------------------ ISSUE-5：path_open_ex

    /**
     * 与 {@code path_open} 相同的 Baritone 寻路，但把 {@code openContainers} 透传给
     * {@link PathOpenJob}（默认 false：只开*_door/fence_gate/trapdoor，不再顺手开箱子）。
     * Tools 里的 path_open 仍是 4 参构造，需要这个参数时用本别名。
     */
    private static Object pathOpenEx(JsonObject args) {
        int x = Json.i(args, "x", Integer.MIN_VALUE);
        int y = Json.i(args, "y", Integer.MIN_VALUE);
        int z = Json.i(args, "z", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
            throw new Errors.BridgeException(Errors.INTERNAL, "x, y and z are required");
        }
        if (!BaritoneControl.isLoaded()) {
            throw Errors.unsupported("Baritone (mod id baritoe) is not installed");
        }
        int range = MathUtil.clamp(Json.i(args, "range", 0), 0, 64);
        boolean openContainers = Json.b(args, "openContainers", false);
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 300), 1, 600);
        return BridgeRuntime.jobs.create("path_open",
                timeout, new PathOpenJob(x, y, z, range, openContainers));
    }

    // ------------------------------------------------------------------ ISSUE-7：walk_dir
    /**
     * 语义化移动。yaw 与 {@link WalkJob} 一致（世界朝向）：0→+Z(南)、90→-X(西)、180→-Z(北)、270→+X(东)。
     * 方向名映射到同一组 yaw，写完文档后调用方不必记数字。
     */
    private static Object walkDir(JsonObject args) {
        String direction = Json.str(args, "direction", null);
        float yaw = Float.NaN;
        if (direction != null && !direction.isBlank()) {
            yaw = switch (direction.trim().toLowerCase(Locale.ROOT)) {
                case "north", "n", "-z" -> 180.0f;
                case "south", "s", "+z" -> 0.0f;
                case "east", "e", "+x" -> 270.0f;
                case "west", "w", "-x" -> 90.0f;
                default -> throw new Errors.BridgeException(Errors.INTERNAL,
                        "direction must be north/south/east/west (or give yaw)");
            };
        }
        if (args.has("yaw")) {
            yaw = (float) Json.d(args, "yaw", yaw);
        }
        if (Float.isNaN(yaw)) {
            throw new Errors.BridgeException(Errors.INTERNAL, "direction or yaw is required");
        }
        int ticks = MathUtil.clamp(Json.i(args, "ticks", 10), 1, 400);
        boolean jump = Json.b(args, "jump", false);
        boolean sprint = Json.b(args, "sprint", false);
        boolean up = Json.b(args, "up", false);
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 60), 1, 600);
        return BridgeRuntime.jobs.create("walk_dir", timeout, new WalkJob(yaw, ticks, jump, sprint, up));
    }
}
