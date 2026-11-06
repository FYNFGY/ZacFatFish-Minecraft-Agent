package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Round-3 utility actions for the world tests: throwing the eye of ender and sampling its flight,
 * placing a boat and sailing it to a target, then dismounting so the boat can be recovered.
 */
public final class ItemActions {
    private ItemActions() {
    }

    /** Runs on the client thread: right-click with the held item (e.g. throw an eye of ender). */
    public static JsonObject throwItem(JsonObject args) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            throw Errors.notLoaded("the player is not in a world");
        }
        String item = Json.str(args, "item", null);
        int slot = Json.i(args, "slot", -1);
        if (slot < 0 && item != null) {
            slot = Actions.findItemSlot(player, item);
        }
        if (slot >= 0) {
            Actions.selectSlot(player, slot);
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            throw Errors.unsupported("the main hand is empty");
        }
        String heldId = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        // ISSUE-3: 进食/拉弓需要“按住右键”跨 tick，原来只调一次 useItem 等于按下就松手。
        int holdTicks = MathUtil.clamp(Json.i(args, "holdTicks", 0), 0, 200);
        int countBefore = held.getCount();
        if (holdTicks > 0) {
            // Bow/trident need the actual use key held; calling useItem once lets the client cancel
            // the charge on the next tick, which is why no arrow was ever released.
            Minecraft.getInstance().options.keyUse.setDown(true);
        }
        InteractionResult result = mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        if (holdTicks > 0 && !player.isUsingItem()) {
            player.startUsingItem(InteractionHand.MAIN_HAND);
        }
        player.swing(InteractionHand.MAIN_HAND);
        boolean using = player.isUsingItem();
        JsonObject o = Json.ok();
        o.addProperty("item", heldId);
        o.addProperty("result", String.valueOf(result));
        o.addProperty("holdTicks", holdTicks);
        o.addProperty("usingItem", using);
        o.addProperty("countBefore", countBefore);
        o.addProperty("countAfter", player.getMainHandItem().getCount());
        if (holdTicks > 0 && using) {
            // 这个工具是同步调用（客户端线程），不能在这里等 32 tick；先按住，把“松开”排到 N tick 后。
            // 需要“看到吃完/拉满”的调用方用 eat / use_item（它们逐 tick 等待）。
            TickScheduler.afterTicks(holdTicks, () -> {
                LocalPlayer current = Minecraft.getInstance().player;
                if (current != null && current.isUsingItem()) {
                    Minecraft.getInstance().options.keyUse.setDown(false);
                    Minecraft.getInstance().gameMode.releaseUsingItem(current);
                    current.stopUsingItem();
                } else {
                    Minecraft.getInstance().options.keyUse.setDown(false);
                }
            });
            o.addProperty("holdState", "scheduled");
            o.addProperty("releaseAfterTicks", holdTicks);
            o.addProperty("note", "release is scheduled on the client tick; use eat/use_item to wait for completion");
        } else if (holdTicks > 0) {
            o.addProperty("holdState", "not_using");
            o.addProperty("note", "the item did not enter the using state, nothing to hold");
        } else {
            o.addProperty("holdState", "instant");
        }
        // The projectile entity is spawned client-side in the same tick; find it right away.
        for (Entity e : level.getEntities(player, player.getBoundingBox().inflate(4.0), candidate -> candidate != player)) {
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            if (type.equals("minecraft:eye_of_ender") || type.equals("minecraft:eye_of_ender_signal")) {
                o.addProperty("entityId", e.getId());
                o.addProperty("type", type);
                o.addProperty("x", MathUtil.round2(e.getX()));
                o.addProperty("y", MathUtil.round2(e.getY()));
                o.addProperty("z", MathUtil.round2(e.getZ()));
                return o;
            }
        }
        o.addProperty("entityId", -1);
        o.addProperty("projectileNote",
                "no eye/projectile entity found in the same tick; use track_entity on a nearby entity");
        return o;
    }

    /** Job: sample an entity's position every tick and report its direction. */
    public static Jobs.Job trackEntity(JsonObject args) {
        int entityId = Json.i(args, "entityId", -1);
        int ticks = MathUtil.clamp(Json.i(args, "ticks", 20), 2, 200);
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 30), 1, 600);
        return BridgeRuntime.jobs.create("track_entity", timeout, new TrackTask(entityId, ticks));
    }

    /** Job: place a boat on the water at (x,y,z) - the block must be water. */
    public static JsonObject placeBoat(JsonObject args) {
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
        int slot = Actions.findItemSlot(player, Json.str(args, "item", "minecraft:oak_boat"));
        if (slot < 0) {
            slot = -1;
            for (int i = 0; i < 9; i++) {
                ItemStack s = player.getInventory().getItem(i);
                if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith("_boat")) {
                    slot = i;
                    break;
                }
            }
        }
        if (slot < 0) {
            throw Errors.unsupported("no boat item in the hotbar");
        }
        Actions.selectSlot(player, slot);
        BlockPos pos = new BlockPos(x, y, z);
        // Boats are placed with the *item use* path, not useItemOn: water has no collision shape, so
        // vanilla raycasts (including fluids) from the eyes and calls BoatItem.use(). Clicking the
        // water block with useItemOn just returns PASS and nothing appears.
        Vec3 center = Vec3.atCenterOf(pos).add(0.0, 0.2, 0.0);
        double dx = center.x - player.getX();
        double dy = center.y - player.getEyeY();
        double dz = center.z - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(0.001, horizontal)));
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(pitch);
        // getViewVector(1.0) interpolates between the previous and current rotation; without this the
        // ray is still aimed at the old direction for one tick and BoatItem.use() misses the water.
        player.yRotO = yaw;
        player.xRotO = pitch;
        sendRotation(player);
        InteractionResult result = mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        // Some aims clip the shoreline or the block under the feet: retry a little steeper/shallower.
        for (int attempt = 0; attempt < 4 && !result.consumesAction(); attempt++) {
            float p2 = pitch + (attempt % 2 == 0 ? 6.0f : -6.0f) * (attempt + 1);
            p2 = (float) MathUtil.clamp((double) p2, -89.0, 89.0);
            player.setXRot(p2);
            player.xRotO = p2;
            sendRotation(player);
            result = mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        }
        player.swing(InteractionHand.MAIN_HAND);
        JsonObject o = Json.ok();
        o.addProperty("result", String.valueOf(result));
        o.addProperty("pick", hitInfo(player.pick(5.0, 1.0F, true), level));
        o.addProperty("aim", MathUtil.round2(pitch));
        for (Entity e : level.getEntities(player, player.getBoundingBox().inflate(6.0), candidate -> candidate != player)) {
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            if (isBoatType(type)) {
                o.addProperty("entityId", e.getId());
                o.addProperty("type", type);
                o.addProperty("x", MathUtil.round2(e.getX()));
                o.addProperty("y", MathUtil.round2(e.getY()));
                o.addProperty("z", MathUtil.round2(e.getZ()));
                return o;
            }
        }
        o.addProperty("entityId", -1);
        o.addProperty("note", "no boat entity appeared; check that the target block is water");
        return o;
    }

    /** Boat entity types differ per pack: vanilla uses <wood>_boat, this pack uses minecraft:boat. */
    private static boolean isBoatType(String type) {
        return type.equals("minecraft:boat") || type.endsWith("_boat") || type.endsWith("_raft")
                || type.contains("boat");
    }

    /** Sends the current look direction to the server; the server raycasts for itself on item use. */
    private static void sendRotation(LocalPlayer player) {
        if (player.connection == null) {
            return;
        }
        player.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                player.getYRot(), player.getXRot(), player.onGround()));
    }

    /** Human readable description of a raycast result, used by place_boat diagnostics. */
    private static String hitInfo(HitResult hit, ClientLevel level) {
        if (hit == null) {
            return "null";
        }
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult block) {
            BlockPos p = block.getBlockPos();
            return "BLOCK " + p.toShortString() + " "
                    + BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock())
                    + " water=" + level.isWaterAt(p) + " face=" + block.getDirection();
        }
        return String.valueOf(hit.getType());
    }

    /** Job: mount the nearest boat, sail to (x,z), stop next to the target and dismount. */
    public static Jobs.Job boatSail(JsonObject args) {
        double x = Json.d(args, "x", Double.NaN);
        double z = Json.d(args, "z", Double.NaN);
        double range = Json.d(args, "range", 2.0);
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 240), 1, 600);
        return BridgeRuntime.jobs.create("boat_sail", timeout, new SailTask(x, z, range));
    }

    private static final class TrackTask implements Jobs.TickTask {
        private final int entityId;
        private final int maxTicks;
        private final List<double[]> samples = new ArrayList<>();
        private int ticks;
        private double startX;
        private double startZ;
        private double startY;

        private TrackTask(int entityId, int maxTicks) {
            this.entityId = entityId;
            this.maxTicks = maxTicks;
        }

        @Override
        public void start(Jobs.Job job) {
            job.metric("entityId", entityId);
            job.metric("samples", 0);
        }

        @Override
        public void tick(Jobs.Job job) {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            LocalPlayer player = mc.player;
            if (level == null || player == null) {
                job.fail(Errors.DISCONNECTED, "player left the world");
                return;
            }
            Entity target = level.getEntity(entityId);
            if (target == null) {
                job.fail(Errors.ENTITY_NOT_FOUND, "entity " + entityId + " is gone (it may have shattered)");
                return;
            }
            if (ticks == 0) {
                startX = target.getX();
                startY = target.getY();
                startZ = target.getZ();
            }
            ticks++;
            samples.add(new double[]{target.getX() - startX, target.getY() - startY, target.getZ() - startZ});
            job.metric("samples", samples.size());
            if (ticks >= maxTicks) {
                finish(job);
            }
        }

        private void finish(Jobs.Job job) {
            double dx = 0;
            double dz = 0;
            double dy = 0;
            int n = Math.min(samples.size(), 10);
            if (n > 0) {
                double[] last = samples.get(n - 1);
                dx = last[0];
                dy = last[1];
                dz = last[2];
            }
            JsonObject r = Json.ok();
            r.addProperty("entityId", entityId);
            r.addProperty("samples", samples.size());
            r.addProperty("dx", MathUtil.round2(dx));
            r.addProperty("dy", MathUtil.round2(dy));
            r.addProperty("dz", MathUtil.round2(dz));
            double horizontal = Math.hypot(dx, dz);
            r.addProperty("horizontal", MathUtil.round2(horizontal));
            if (horizontal > 0.1) {
                r.addProperty("yaw", MathUtil.round2((float) Math.toDegrees(Math.atan2(-dx, dz))));
            }
            r.addProperty("vertical", dy > 0.15 ? "up" : dy < -0.15 ? "down" : "flat");
            JsonArray arr = new JsonArray();
            for (double[] s : samples) {
                arr.add(Json.doubleArray(s[0], s[1], s[2]));
            }
            r.add("path", arr);
            job.succeed(r);
        }
    }

    private static final class SailTask implements Jobs.TickTask {
        private final double targetX;
        private final double targetZ;
        private final double range;
        private Entity boat;
        private int state;
        private int ticks;
        private int stateTicks;

        private SailTask(double targetX, double targetZ, double range) {
            this.targetX = targetX;
            this.targetZ = targetZ;
            this.range = range;
        }

        @Override
        public void start(Jobs.Job job) {
            job.metric("state", "mount");
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
            ticks++;
            if (ticks > 6000) {
                job.fail(Errors.TIMEOUT, "boat sail did not finish in 6000 ticks");
                return;
            }
            switch (state) {
                case 0 -> mount(job, mc, player, level);
                case 1 -> sail(job, mc, player);
                case 2 -> dismount(job, mc, player);
                default -> job.fail(Errors.INTERNAL, "bad state");
            }
        }

        private void mount(Jobs.Job job, Minecraft mc, LocalPlayer player, ClientLevel level) {
            if (player.getVehicle() != null) {
                boat = player.getVehicle();
                state = 1;
                return;
            }
            if (boat == null) {
                for (Entity e : level.getEntities(player, player.getBoundingBox().inflate(6.0), candidate -> candidate != player)) {
                    String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
                    if (isBoatType(type)) {
                        boat = e;
                        break;
                    }
                }
                if (boat == null) {
                    job.fail(Errors.ENTITY_NOT_FOUND, "no boat within 6 blocks; place one first");
                    return;
                }
            }
            mc.gameMode.interact(player, boat, InteractionHand.MAIN_HAND);
            player.swing(InteractionHand.MAIN_HAND);
        }

        private void sail(Jobs.Job job, Minecraft mc, LocalPlayer player) {
            if (player.getVehicle() == null) {
                job.fail(Errors.NO_PROGRESS, "the player is no longer in the boat");
                return;
            }
            double dx = targetX - player.getX();
            double dz = targetZ - player.getZ();
            if (Math.hypot(dx, dz) <= range) {
                mc.options.keyUp.setDown(false);
                state = 2;
                stateTicks = 0;
                return;
            }
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            mc.options.keyUp.setDown(true);
            player.setYRot(yaw);
            player.setYHeadRot(yaw);
            job.metric("distance", Math.round(Math.hypot(dx, dz)));
            job.metric("state", "sail");
        }

        private void dismount(Jobs.Job job, Minecraft mc, LocalPlayer player) {
            mc.options.keyUp.setDown(false);
            stateTicks++;
            mc.options.keyShift.setDown(true);
            if (player.getVehicle() == null || stateTicks > 40) {
                mc.options.keyShift.setDown(false);
                JsonObject r = Json.ok();
                r.addProperty("dismounted", player.getVehicle() == null);
                r.addProperty("x", MathUtil.round2(player.getX()));
                r.addProperty("y", MathUtil.round2(player.getY()));
                r.addProperty("z", MathUtil.round2(player.getZ()));
                job.succeed(r);
            }
        }
    }
}

