package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Entity-based actions that must NOT use mine_block: boats, minecarts, item frames, paintings and
 * armour stands are entities. break_entity attacks until the entity is removed and reports the drops;
 * collect_drops walks over nearby item entities until they are picked up.
 */
public final class EntityActions {
    private EntityActions() {
    }

    public static Jobs.Job breakEntity(JsonObject args) {
        Integer id = Json.iOrNull(args, "entityId");
        String uuid = Json.str(args, "uuid", null);
        String type = Json.str(args, "type", null);
        double radius = Json.d(args, "radius", 6.0);
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 60), 1, 600);
        return BridgeRuntime.jobs.create("break_entity", timeout, new BreakTask(id, uuid, type, radius));
    }

    public static Jobs.Job collectDrops(JsonObject args) {
        double radius = Json.d(args, "radius", 8.0);
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 60), 1, 600);
        return BridgeRuntime.jobs.create("collect_drops", timeout, new CollectTask(radius));
    }

    private static Entity find(ClientLevel level, LocalPlayer player, Integer id, String uuid, String type, double radius) {
        AABB box = player.getBoundingBox().inflate(Math.max(1.0, radius));
        for (Entity e : level.getEntities(player, box, candidate -> candidate != player)) {
            if (id != null && e.getId() == id) {
                return e;
            }
            if (uuid != null && e.getUUID().toString().equalsIgnoreCase(uuid)) {
                return e;
            }
            if (id == null && uuid == null && type != null
                    && BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals(type)) {
                return e;
            }
        }
        return null;
    }

    private static final class BreakTask implements Jobs.TickTask {
        private final Integer id;
        private final String uuid;
        private final String type;
        private final double radius;
        private Entity target;
        private int hits;
        private int cooldown;
        private int ticks;
        private boolean broke;
        private String entityType;

        private BreakTask(Integer id, String uuid, String type, double radius) {
            this.id = id;
            this.uuid = uuid;
            this.type = type;
            this.radius = radius;
        }

        @Override
        public void start(Jobs.Job job) {
            job.metric("hits", 0);
            job.metric("total", 1);
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
            if (target == null) {
                target = find(level, player, id, uuid, type, radius);
                if (target == null) {
                    job.fail(Errors.ENTITY_NOT_FOUND, "no matching entity within " + radius + " blocks");
                    return;
                }
                entityType = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
                job.metric("entityType", entityType);
                job.metric("entityId", target.getId());
            }
            if (target.isRemoved() || !target.isAlive()) {
                finish(job, level, player);
                return;
            }
            ticks++;
            if (ticks > 900) {
                job.fail(Errors.TIMEOUT, "entity did not break within 900 ticks");
                return;
            }
            if (cooldown > 0) {
                cooldown--;
                return;
            }
            mc.gameMode.attack(player, target);
            player.swing(InteractionHand.MAIN_HAND);
            hits++;
            cooldown = 10;
            job.metric("hits", hits);
            if (target.isRemoved() || !target.isAlive()) {
                broke = true;
                finish(job, level, player);
            }
        }

        private void finish(Jobs.Job job, ClientLevel level, LocalPlayer player) {
            JsonArray drops = new JsonArray();
            AABB box = player.getBoundingBox().inflate(4.0);
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box)) {
                JsonObject o = new JsonObject();
                o.addProperty("item", BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString());
                o.addProperty("count", item.getItem().getCount());
                o.add("pos", Json.intArray(item.blockPosition().getX(), item.blockPosition().getY(), item.blockPosition().getZ()));
                drops.add(o);
            }
            JsonObject r = new JsonObject();
            r.addProperty("entityType", entityType == null ? "?" : entityType);
            r.addProperty("hits", hits);
            r.addProperty("removed", true);
            r.add("drops", drops);
            job.succeed(r);
        }
    }

    private static final class CollectTask implements Jobs.TickTask {
        private final double radius;
        private int ticks;
        private int collected;
        private int initial;
        private int stuckTicks;
        private double lastDist = Double.MAX_VALUE;

        private CollectTask(double radius) {
            this.radius = radius;
        }

        @Override
        public void start(Jobs.Job job) {
            job.metric("collected", 0);
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
            if (ticks > 900) {
                job.fail(Errors.TIMEOUT, "drops were not collected within 900 ticks");
                return;
            }
            AABB box = player.getBoundingBox().inflate(Math.max(1.0, radius));
            List<ItemEntity> items = new ArrayList<>(level.getEntitiesOfClass(ItemEntity.class, box));
            if (initial == 0 && ticks == 1) {
                initial = items.size();
                job.metric("initial", initial);
            }
            if (items.isEmpty()) {
                // Everything in range is gone: that IS the pickup. Without this the last tick that saw
                // the item kept metrics.collected at its previous value (the result said 1, the metric 0).
                collected = Math.max(collected, initial);
                job.metric("collected", collected);
                succeed(job, "done");
                return;
            }
            ItemEntity nearest = items.get(0);
            double best = Double.MAX_VALUE;
            for (ItemEntity item : items) {
                double d = item.distanceToSqr(player);
                if (d < best) {
                    best = d;
                    nearest = item;
                }
            }
            double distance = Math.sqrt(best);
            if (distance < lastDist - 0.05) {
                stuckTicks = 0;
            } else {
                stuckTicks++;
            }
            lastDist = distance;
            // Give up quickly when the items are unreachable (behind a wall): the old 80-tick window
            // plus a jump every tick looked like the player was "jumping in place" for seconds.
            if (stuckTicks > 35) {
                succeed(job, "stuck");
                return;
            }
            Vec3 delta = nearest.position().subtract(player.position());
            double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
            if (horizontal > 0.05) {
                double speed = 0.22;
                player.setDeltaMovement(delta.x / horizontal * speed, player.getDeltaMovement().y, delta.z / horizontal * speed);
                // Throttle jumps: at most once every 10 ticks, and only while actually making progress.
                if (player.horizontalCollision && player.onGround() && stuckTicks % 10 == 0) {
                    player.jumpFromGround();
                }
            } else if (player.onGround() && Math.abs(delta.y) < 1.6 && stuckTicks % 10 == 0) {
                // The item is straight above the player: hop to reach it.
                player.jumpFromGround();
            }
            collected = Math.max(0, initial - items.size());
            job.metric("collected", collected);
        }

        private void succeed(Jobs.Job job, String reason) {
            JsonObject r = new JsonObject();
            job.metric("collected", collected);
            r.addProperty("collected", collected);
            r.addProperty("ticks", ticks);
            r.addProperty("reason", reason);
            job.succeed(r);
        }
    }

    static String typeOf(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().toLowerCase(Locale.ROOT);
    }
}
