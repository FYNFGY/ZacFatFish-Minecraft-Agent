package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Player-equivalent block actions. Jobs are tick-driven; sync helpers are called on the client thread. */
public final class Actions {
    private Actions() {
    }

    public static Jobs.Job mineBlock(JsonObject args) {
        List<BlockPos> targets = targets(args, 64);
        if (targets.isEmpty()) {
            throw new Errors.BridgeException(Errors.INTERNAL, "no target positions");
        }
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 60), 1, 600);
        Minecraft mc = Minecraft.getInstance();
        Jobs.Job job = BridgeRuntime.jobs.create("mine_block", timeout, new MineExecutor(targets));
        return job;
    }

    public static Jobs.Job placeBlock(JsonObject args) {
        int x = Json.i(args, "x", Integer.MIN_VALUE);
        int y = Json.i(args, "y", Integer.MIN_VALUE);
        int z = Json.i(args, "z", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
            throw new Errors.BridgeException(Errors.INTERNAL, "x, y and z are required");
        }
        String face = Json.str(args, "face", null);
        String item = Json.str(args, "item", null);
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 60), 1, 600);
        return BridgeRuntime.jobs.create("place_block", timeout, new PlaceTask(new BlockPos(x, y, z), face, item));
    }

    public static Jobs.Job batch(JsonObject args) {
        JsonArray ops = Json.arr(args, "ops");
        if (ops == null || ops.isEmpty()) {
            throw new Errors.BridgeException(Errors.INTERNAL, "ops are required");
        }
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 300), 1, 600);
        return BridgeRuntime.jobs.create("batch", timeout, new BatchJob(ops));
    }

    /** Runs on the client thread. */
    public static JsonObject useBlock(JsonObject args) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            throw Errors.notLoaded("the player is not in a world");
        }
        int x = Json.i(args, "x", Integer.MIN_VALUE);
        int y = Json.i(args, "y", Integer.MIN_VALUE);
        int z = Json.i(args, "z", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
            throw new Errors.BridgeException(Errors.INTERNAL, "x, y and z are required");
        }
        BlockPos pos = new BlockPos(x, y, z);
        BlockState before = level.getBlockState(pos);
        if (before.isAir()) {
            throw Errors.unsupported("there is no block at " + pos.toShortString());
        }
        // ISSUE-11: 方块交互有三种语义——点方块（门/按钮/床）、把手上物品“放”到方块上
        // （营火/烘干架）、以及纯 useItem（水桶/食物）。默认 block 保留老行为。
        String mode = Json.str(args, "mode", "block").toLowerCase(Locale.ROOT);
        if (!mode.equals("block") && !mode.equals("item_on") && !mode.equals("use_item")) {
            throw new Errors.BridgeException(Errors.INTERNAL, "mode must be block, item_on or use_item");
        }
        // ISSUE-13: 下蹲交互（烘干架取货必须空手 + 蹲下右键）。
        boolean sneak = Json.b(args, "sneak", false);
        JsonObject beforeInfo = Perception.blockInfo(level, pos, false);
        Direction direction = parseDirection(Json.str(args, "face", null), player, pos);
        // Aim first: some items (buckets, flint and steel) do their own camera raycast.
        faceBlock(player, pos);
        // Ph8: Create's "physicalize" interaction requires an empty hand. Temporarily select an
        // empty hotbar slot, then restore the previous slot before returning.
        String hand = Json.str(args, "hand", "main").toLowerCase(Locale.ROOT);
        int emptySlot = -1;
        int previousSlot = player.getInventory().selected;
        if (hand.equals("empty")) {
            emptySlot = findEmptyHotbar(player);
            if (emptySlot < 0) throw new Errors.BridgeException(Errors.BLOCKED, "no empty hotbar slot for empty-hand interaction");
            Actions.selectSlot(player, emptySlot);
        }
        JsonObject heldBefore = GameState.stack(player.getMainHandItem());
        if (sneak) {
            startSneaking(player);
        }
        JsonArray attempts = new JsonArray();
        InteractionResult result;
        if (mode.equals("use_item")) {
            result = mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            attempts.add("use_item:" + result);
        } else {
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), direction, pos, false);
            result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            attempts.add("use_item_on(" + direction.getName() + "):" + result);
            // 营火 / 烘干架：把食物插上去走的是方块顶面的 useItemOn，命中点要更贴近顶面。
            boolean placeOnTop = isPlaceOnTopBlock(level, pos);
            if (!result.consumesAction() && placeOnTop) {
                BlockHitResult topHit = new BlockHitResult(
                        new Vec3(pos.getX() + 0.5, pos.getY() + 0.44, pos.getZ() + 0.5),
                        Direction.UP, pos, false);
                InteractionResult retry = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, topHit);
                attempts.add("use_item_on(UP):" + retry);
                if (retry.consumesAction()) {
                    result = retry;
                }
            }
            if (!result.consumesAction() && !mode.equals("item_on")) {
                // Vanilla falls back to "use item" when the block does not consume the interaction; this is
                // the path a water bucket takes (its logic lives in Item.use, not useOn).
                result = mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                attempts.add("use_item:" + result);
            }
        }
        player.swing(InteractionHand.MAIN_HAND);
        // 🔴 P1-3（2026-11-01 实测）：床有两半，点**脚半**常常只回 CONSUME 却不入睡
        //   （`sleeping=false`），点床头才成功。没入睡时自动换另一半再试一次。
        if (!mode.equals("use_item") && !mode.equals("item_on")
                && before.getBlock() instanceof net.minecraft.world.level.block.BedBlock
                && !player.isSleeping()) {
            Direction other = net.minecraft.world.level.block.BedBlock.getConnectedDirection(before);
            BlockPos otherPos = pos.relative(other);
            if (level.getBlockState(otherPos).getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
                faceBlock(player, otherPos);
                BlockHitResult bedHit = new BlockHitResult(Vec3.atCenterOf(otherPos), direction, otherPos, false);
                InteractionResult bedRetry = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, bedHit);
                attempts.add("use_item_on(bed_other=" + otherPos.toShortString() + "):" + bedRetry);
                player.swing(InteractionHand.MAIN_HAND);
            }
        }
        if (sneak) {
            scheduleStopSneaking(2);
        }
        JsonObject heldAfter = GameState.stack(player.getMainHandItem());
        JsonObject o = Json.ok();
        o.add("pos", Json.intArray(x, y, z));
        o.addProperty("mode", mode);
        o.addProperty("sneak", sneak);
        o.add("attempts", attempts);
        o.add("before", beforeInfo);
        o.addProperty("result", String.valueOf(result));
        o.add("after", Perception.blockInfo(level, pos, false));
        o.add("heldBefore", heldBefore);
        o.add("heldAfter", heldAfter);
        o.addProperty("itemConsumed",
                Math.max(0, Json.i(heldBefore, "count", 0) - Json.i(heldAfter, "count", 0)));
        if (emptySlot >= 0) {
            Actions.selectSlot(player, previousSlot);
            o.addProperty("hand", "empty");
            o.addProperty("emptySlot", emptySlot);
        }
        return o;
    }

    private static int findEmptyHotbar(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    /** 营火 / 烘干架这类“把物品放上去”的方块（ISSUE-11）。 */
    private static boolean isPlaceOnTopBlock(ClientLevel level, BlockPos pos) {
        String id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
        return id.endsWith("campfire") || id.contains("drying_rack") || id.endsWith("_rack");
    }

    /** Runs on the client thread; for trade it waits for the merchant menu to open. */
    public static CompletableFuture<JsonObject> entityInteract(JsonObject args) {
        String action = Json.str(args, "action", "use").toLowerCase(Locale.ROOT);
        // ISSUE-13: 部分实体交互（例如把物品挂到盔甲架/取货）要求蹲下。
        boolean sneak = Json.b(args, "sneak", false);
        CompletableFuture<JsonObject> interacted = MainThread.submit("entity_interact", () -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            ClientLevel level = mc.level;
            if (player == null || level == null) {
                throw Errors.notLoaded("the player is not in a world");
            }
            Entity entity = findEntity(level, player, args);
            if (entity == null) {
                throw Errors.unsupported("entity not found within 6 blocks");
            }
            int slot = Json.i(args, "itemSlot", -1);
            if (slot >= 0 && slot < 9) {
                selectSlot(player, slot);
            }
            JsonObject before = GameState.stack(player.getMainHandItem());
            if (sneak) {
                startSneaking(player);
            }
            InteractionResult result;
            if (action.equals("attack")) {
                mc.gameMode.attack(player, entity);
                player.swing(InteractionHand.MAIN_HAND);
                result = InteractionResult.SUCCESS;
            } else {
                result = mc.gameMode.interact(player, entity, InteractionHand.MAIN_HAND);
                player.swing(InteractionHand.MAIN_HAND);
            }
            if (sneak) {
                scheduleStopSneaking(2);
            }
            JsonObject o = Json.ok();
            o.addProperty("entityId", entity.getId());
            o.addProperty("uuid", entity.getUUID().toString());
            o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            o.addProperty("action", action);
            o.addProperty("sneak", sneak);
            o.addProperty("result", String.valueOf(result));
            o.add("itemBefore", before);
            o.addProperty("alive", entity.isAlive());
            if (entity instanceof LivingEntity living) {
                o.addProperty("health", living.getHealth());
            }
            return o;
        });
        return interacted.thenCompose(o -> {
            if (!action.equals("trade")) {
                return CompletableFuture.completedFuture(o);
            }
            return TickScheduler.poll(40, tick -> {
                LocalPlayer player = Minecraft.getInstance().player;
                if (player == null) {
                    throw Errors.notLoaded("the player left the world");
                }
                if (player.containerMenu instanceof MerchantMenu menu) {
                    o.addProperty("menuOpen", true);
                    o.addProperty("menuClass", menu.getClass().getName());
                    return o;
                }
                return null;
            }).exceptionally(error -> {
                o.addProperty("menuOpen", false);
                o.addProperty("warning", "the trade menu did not open within 40 ticks; check range");
                return o;
            });
        });
    }

    static Entity findEntity(ClientLevel level, LocalPlayer player, JsonObject args) {
        Integer id = Json.iOrNull(args, "entityId");
        String uuid = Json.str(args, "uuid", null);
        List<Entity> found = level.getEntities(player, player.getBoundingBox().inflate(6.0), e -> e != player);
        for (Entity e : found) {
            if (id != null && e.getId() == id) {
                return e;
            }
            if (uuid != null && e.getUUID().toString().equalsIgnoreCase(uuid)) {
                return e;
            }
        }
        return null;
    }

    /**
     * True when the player stands in (or their head touches) any fluid. Jobs that dig or build above
     * themselves must abort on this: a broken ceiling can release water and sweep the player away.
     */
    static boolean touchingWater(LocalPlayer player, net.minecraft.client.multiplayer.ClientLevel level) {
        if (player.isInWater() || player.isSwimming()) {
            return true;
        }
        BlockPos feet = player.blockPosition();
        if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty()) {
            return true;
        }
        BlockPos eye = BlockPos.containing(player.getX(), player.getEyeY(), player.getZ());
        return !level.getFluidState(eye).isEmpty();
    }

    static void selectSlot(LocalPlayer player, int slot) {
        if (slot < 0 || slot > 8) {
            return;
        }
        if (player.getInventory().selected != slot) {
            player.getInventory().selected = slot;
            if (player.connection != null) {
                player.connection.send(new ServerboundSetCarriedItemPacket(slot));
            }
        }
    }

    /** ISSUE-13: 进入下蹲（客户端姿势 + 发包，服务端才认“蹲下右键”）。 */
    static void startSneaking(LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        mc.options.keyShift.setDown(true);
        player.setShiftKeyDown(true);
        if (player.connection != null) {
            player.connection.send(new ServerboundPlayerCommandPacket(
                    player, ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
        }
    }

    /** ISSUE-13: 松开下蹲。 */
    static void stopSneaking(LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        mc.options.keyShift.setDown(false);
        player.setShiftKeyDown(false);
        if (player.connection != null) {
            player.connection.send(new ServerboundPlayerCommandPacket(
                    player, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
        }
    }

    /** 交互完成后隔几个 tick 再松开 shift，避免服务端在同一个 tick 里还没处理完。 */
    static void scheduleStopSneaking(int ticks) {
        TickScheduler.afterTicks(Math.max(1, ticks), () -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                stopSneaking(player);
            }
        });
    }

    /**
     * Aims the crosshair at the block centre. Vanilla resets the destroy progress when the hit result
     * changes, so mining without a stable aim shows "cracks but never breaks" (the view used to be
     * left wherever the last walk job pointed it).
     */
    static void faceBlock(LocalPlayer player, BlockPos pos) {
        double dx = pos.getX() + 0.5 - player.getX();
        double dy = pos.getY() + 0.5 - player.getEyeY();
        double dz = pos.getZ() + 0.5 - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.setYHeadRot(yaw);
        player.setYBodyRot(yaw);
        // Keep the crosshair hit result in sync too: the vanilla tick recomputes it from the camera, and
        // a stale hit result is what used to reset the destroy progress.
        Minecraft mc = Minecraft.getInstance();
        Direction face = Direction.getNearest((float) dx, (float) dy, (float) dz);
        mc.hitResult = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
    }

    static int findToolSlot(LocalPlayer player, BlockState state) {
        int best = -1;
        float bestSpeed = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            float speed = stack.getDestroySpeed(state);
            if (stack.isCorrectToolForDrops(state)) {
                speed += 1000;
            }
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = i;
            }
        }
        return best;
    }

    static int findItemSlot(LocalPlayer player, String itemId) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (matchesItemId(id, itemId)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * ISSUE-4: 快捷栏优先，找不到再搜主背包（9..35）。返回值是 inventory 索引：
     * 0..8 已在快捷栏，9..35 需要先交换到快捷栏才能放。
     */
    static int findItemSlotAll(LocalPlayer player, String itemId) {
        int hotbar = findItemSlot(player, itemId);
        // itemId 为空时保持旧语义：只返回快捷栏第一个非空格，不去背包里随便拿一个方块。
        if (hotbar >= 0 || itemId == null || itemId.isBlank()) {
            return hotbar;
        }
        for (int i = 9; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (matchesItemId(id, itemId)) {
                return i;
            }
        }
        return -1;
    }

    /** 完整 id 优先；也允许只写路径（"bread" 匹配 "minecraft:bread"）。 */
    private static boolean matchesItemId(String id, String itemId) {
        return itemId == null || itemId.isBlank() || id.equals(itemId) || id.endsWith(":" + itemId);
    }

    static Direction parseDirection(String face, LocalPlayer player, BlockPos pos) {
        if (face != null && !face.isBlank()) {
            try {
                return Direction.valueOf(face.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }
        Vec3 eye = player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(pos);
        return Direction.getNearest(center.x - eye.x, center.y - eye.y, center.z - eye.z);
    }

    static List<BlockPos> targets(JsonObject args, int max) {
        List<BlockPos> out = new ArrayList<>();
        JsonArray positions = Json.arr(args, "positions");
        if (positions != null) {
            for (int i = 0; i < Math.min(max, positions.size()); i++) {
                var e = positions.get(i);
                if (e.isJsonArray() && e.getAsJsonArray().size() >= 3) {
                    JsonArray a = e.getAsJsonArray();
                    out.add(new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()));
                }
            }
        }
        if (out.isEmpty()) {
            int x = Json.i(args, "x", Integer.MIN_VALUE);
            int y = Json.i(args, "y", Integer.MIN_VALUE);
            int z = Json.i(args, "z", Integer.MIN_VALUE);
            if (x != Integer.MIN_VALUE && y != Integer.MIN_VALUE && z != Integer.MIN_VALUE) {
                out.add(new BlockPos(x, y, z));
            }
        }
        return out;
    }

    private static final class MineTask implements Jobs.TickTask {
        private final List<BlockPos> targets;
        private int index;
        private int mined;
        private boolean started;
        private Direction face = Direction.UP;
        private int targetSlot = -1;

        private MineTask(List<BlockPos> targets) {
            this.targets = targets;
        }

        @Override
        public void start(Jobs.Job job) {
            job.metric("total", targets.size());
            job.metric("blocksMined", 0);
            job.metric("target", new JsonArray());
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
            if (index >= targets.size()) {
                JsonObject r = new JsonObject();
                r.addProperty("blocksMined", mined);
                r.addProperty("total", targets.size());
                job.succeed(r);
                return;
            }
            BlockPos pos = targets.get(index);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                mined++;
                index++;
                started = false;
                update(job, pos);
                return;
            }
            if (state.getDestroySpeed(level, pos) < 0) {
                job.fail(Errors.UNSUPPORTED, "block is unbreakable: " + pos.toShortString());
                return;
            }
            if (targetSlot < 0) {
                targetSlot = findToolSlot(player, state);
                if (targetSlot >= 0) {
                    selectSlot(player, targetSlot);
                }
            }
            if (!started) {
                face = parseDirection(null, player, pos);
                boolean ok = mc.gameMode.startDestroyBlock(pos, face);
                started = true;
                if (!ok) {
                    job.fail(Errors.UNSUPPORTED, "could not start breaking " + pos.toShortString());
                }
                return;
            }
            mc.gameMode.continueDestroyBlock(pos, face);
            if (level.getBlockState(pos).isAir()) {
                mined++;
                index++;
                started = false;
                targetSlot = -1;
                update(job, pos);
            } else {
                job.metric("target", posArray(pos));
            }
        }

        private void update(Jobs.Job job, BlockPos pos) {
            job.metric("blocksMined", mined);
            job.metric("total", targets.size());
            job.metric("lastBlock", posArray(pos));
            int pct = targets.isEmpty() ? 100 : (int) Math.round(100.0 * mined / targets.size());
            job.progress(pct);
            if (index >= targets.size()) {
                job.metric("blocksMined", mined);
            }
        }

        private JsonArray posArray(BlockPos pos) {
            return Json.intArray(pos.getX(), pos.getY(), pos.getZ());
        }

        @Override
        public void cancel(Jobs.Job job) {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc.gameMode != null) {
                    mc.gameMode.stopDestroyBlock();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class PlaceTask implements Jobs.TickTask {
        private final BlockPos target;
        private final String faceName;
        private final String itemId;
        private int ticks;
        private boolean sent;
        private BlockPos clicked;
        private Direction direction;
        // ISSUE-4: 从主背包临时换进快捷栏的槽位（-1 表示没换）。
        private int autoSwapFrom = -1;
        private int autoSwapHotbar = -1;

        private PlaceTask(BlockPos target, String faceName, String itemId) {
            this.target = target;
            this.faceName = faceName;
            this.itemId = itemId;
        }

        @Override
        public void start(Jobs.Job job) {
            job.metric("total", 1);
            job.metric("blocksPlaced", 0);
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
            BlockState targetState = level.getBlockState(target);
            if (!sent) {
                if (!targetState.isAir() && !targetState.canBeReplaced()) {
                    job.fail(Errors.UNSUPPORTED, "target position is already occupied by "
                            + BuiltInRegistries.BLOCK.getKey(targetState.getBlock()));
                    return;
                }
                if (!(targetState.isAir() || targetState.canBeReplaced())) {
                    job.fail(Errors.UNSUPPORTED, "target is not replaceable");
                    return;
                }
                Direction d;
                if (faceName != null && !faceName.isBlank()) {
                    try {
                        d = Direction.valueOf(faceName.trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        job.fail(Errors.INTERNAL, "invalid face: " + faceName);
                        return;
                    }
                } else {
                    d = null;
                    for (Direction candidate : Direction.values()) {
                        BlockPos neighbor = target.relative(candidate);
                        BlockState ns = level.getBlockState(neighbor);
                        if (!ns.isAir() && ns.getCollisionShape(level, neighbor).isEmpty() == false) {
                            d = candidate;
                            break;
                        }
                    }
                    if (d == null) {
                        job.fail(Errors.UNSUPPORTED, "no solid neighbor to place against");
                        return;
                    }
                }
                BlockPos support;
                Direction hitFace;
                if (faceName != null && !faceName.isBlank()) {
                    // The caller gives the face that will be clicked (the side of the support block).
                    hitFace = d;
                    support = target.relative(d.getOpposite());
                } else {
                    // d is the direction from the target to a solid neighbour: click that neighbour's
                    // side that faces the target.
                    support = target.relative(d);
                    hitFace = d.getOpposite();
                }
                BlockState supportState = level.getBlockState(support);
                if (supportState.isAir() || supportState.getCollisionShape(level, support).isEmpty()) {
                    job.fail(Errors.UNSUPPORTED, "clicked support block is not solid: " + support.toShortString());
                    return;
                }
                // ISSUE-4: 物品不在快捷栏时，自动从主背包换到当前快捷栏槽，放置结束后再换回去。
                // 以前这里只找 0..8，物品在背包 35 格时直接报 "no matching block item in the hotbar"。
                int slot = findItemSlotAll(player, itemId);
                if (slot < 0) {
                    job.fail(Errors.UNSUPPORTED, "no matching block item in the hotbar or inventory");
                    return;
                }
                if (slot >= 9) {
                    int hotbar = player.getInventory().selected;
                    if (!InventoryActions.quickSwap(player, slot, hotbar)) {
                        job.fail(Errors.INTERNAL,
                                "could not move inventory slot " + slot + " into hotbar slot " + hotbar);
                        return;
                    }
                    autoSwapFrom = slot;
                    autoSwapHotbar = hotbar;
                    slot = hotbar;
                    job.metric("autoSwap", true);
                }
                selectSlot(player, slot);
                clicked = support;
                direction = hitFace;
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support), hitFace, support, false);
                mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                player.swing(InteractionHand.MAIN_HAND);
                sent = true;
                ticks = 0;
                return;
            }
            ticks++;
            if (ticks < 3) {
                return;
            }
            BlockState after = level.getBlockState(target);
            boolean placed = !after.isAir() && !after.canBeReplaced();
            job.metric("blocksPlaced", placed ? 1 : 0);
            job.progress(placed ? 100 : 50);
            int swappedFrom = autoSwapFrom;
            int swappedHotbar = autoSwapHotbar;
            boolean restored = restoreHotbar(job);
            if (placed) {
                JsonObject r = new JsonObject();
                r.add("pos", Json.intArray(target.getX(), target.getY(), target.getZ()));
                r.addProperty("block", BuiltInRegistries.BLOCK.getKey(after.getBlock()).toString());
                r.add("clicked", Json.intArray(clicked.getX(), clicked.getY(), clicked.getZ()));
                r.addProperty("face", direction.getName());
                r.addProperty("item", itemId == null ? "minecraft:air" : itemId);
                r.addProperty("autoSwap", swappedFrom >= 0 || swappedHotbar >= 0);
                if (swappedFrom >= 0) {
                    r.addProperty("swappedFrom", swappedFrom);
                }
                if (swappedHotbar >= 0) {
                    r.addProperty("hotbarSlot", swappedHotbar);
                }
                r.addProperty("restored", restored);
                job.succeed(r);
            } else {
                job.fail(Errors.INTERNAL, "placement was not confirmed by the client after 3 ticks"
                        + (swappedHotbar >= 0 ? " (hotbar item restored: " + restored + ")" : ""));
            }
        }

        /**
         * 把临时换进快捷栏的物品换回主背包原槽。menu 的 SWAP 点击是对合操作，再换一次就还原；
         * 返回 true 表示做过还原（且点击已发出）。
         */
        private boolean restoreHotbar(Jobs.Job job) {
            if (autoSwapFrom < 0 || autoSwapHotbar < 0) {
                return false;
            }
            LocalPlayer player = Minecraft.getInstance().player;
            boolean restored = player != null && InventoryActions.quickSwap(player, autoSwapFrom, autoSwapHotbar);
            autoSwapFrom = -1;
            autoSwapHotbar = -1;
            job.metric("restored", restored);
            return restored;
        }
    }
}
