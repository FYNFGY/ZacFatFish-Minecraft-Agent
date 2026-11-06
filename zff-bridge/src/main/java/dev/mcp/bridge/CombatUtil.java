package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.TridentItem;

import java.util.List;

/** Shared helpers for the Ph8 close/far/escape combat jobs. */
public final class CombatUtil {
    private CombatUtil() {
    }

    public static Entity nearestEnemy(LocalPlayer player, double radius) {
        List<Entity> list = player.level().getEntities(player, player.getBoundingBox().inflate(radius),
                e -> e instanceof Enemy && e.isAlive() && e != player);
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : list) {
            boolean walkable = PathReachability.reachable(player, e, 15);
            // Flying blazes/dragons may hover above the platform with no ground path; accept a
            // visible flying target, but never a wall-blocked one.
            boolean flying = e instanceof net.minecraft.world.entity.monster.Blaze
                    || e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon;
            boolean visibleFlying = flying
                    && player.distanceTo(e) < (e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon ? 64 : 16)
                    && player.hasLineOfSight(e);
            if (!walkable && !visibleFlying) continue;
            double d = player.distanceToSqr(e);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    public static int countEnemies(LocalPlayer player, double radius) {
        return player.level().getEntities(player, player.getBoundingBox().inflate(radius),
                e -> e instanceof Enemy && e.isAlive()).size();
    }

    public static Entity findEntityById(LocalPlayer player, int id, double radius) {
        for (Entity e : player.level().getEntities(player, player.getBoundingBox().inflate(radius))) {
            if (e.getId() == id && e.isAlive()) return e;
        }
        return null;
    }

    public static int findItem(LocalPlayer player, String itemId, Class<?> type) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            if (itemId != null && !itemId.isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
                if (!id.equals(itemId)) continue;
            }
            if (type != null && !type.isInstance(s.getItem())) continue;
            return i;
        }
        return -1;
    }

    public static int findMelee(LocalPlayer player, String requested) {
        int requestedSlot = requested == null || requested.isEmpty() ? -1 : findItem(player, requested, null);
        if (requestedSlot >= 0) return requestedSlot;
        for (int i = 0; i < 36; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.isEmpty() && (s.getItem() instanceof SwordItem || s.getItem() instanceof AxeItem)) return i;
        }
        return -1;
    }

    public static int findRanged(LocalPlayer player, String requested) {
        int requestedSlot = requested == null || requested.isEmpty() ? -1 : findItem(player, requested, null);
        if (requestedSlot >= 0) return requestedSlot;
        for (int i = 0; i < 36; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.isEmpty() && (s.getItem() instanceof BowItem || s.getItem() instanceof CrossbowItem
                    || s.getItem() instanceof TridentItem)) return i;
        }
        return -1;
    }

    public static int arrows(LocalPlayer player) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals("arrow")) n += s.getCount();
        }
        return n;
    }

    public static boolean hasShield(LocalPlayer player) {
        return player.getOffhandItem().getItem() instanceof ShieldItem;
    }

    public static int remaining(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return -1;
        int max = stack.getMaxDamage();
        return max <= 0 ? Integer.MAX_VALUE : max - stack.getDamageValue();
    }

    public static boolean usableWeapon(ItemStack stack) {
        return stack != null && !stack.isEmpty() && remaining(stack) > 0;
    }

    public static void face(LocalPlayer player, Entity target) {
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        double dy = (target.getY() + target.getBbHeight() * 0.5) - player.getEyeY();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.setYHeadRot(yaw);
        player.setYBodyRot(yaw);
        // Server-side shield facing uses the server player's rotation, not the client-only setters.
        if (player.connection != null) {
            player.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                    yaw, pitch, player.onGround()));
        }
    }

    public static void moveToward(LocalPlayer player, Entity target, boolean sprint) {
        face(player, target);
        Minecraft mc = Minecraft.getInstance();
        mc.options.keyUp.setDown(true);
        mc.options.keySprint.setDown(sprint);
        // Jump both for a collision/step and for a target that is one or more blocks above.
        boolean needUp = target.getY() > player.getY() + 0.55;
        mc.options.keyJump.setDown(player.onGround() && (player.horizontalCollision || needUp));
    }

    public static void stopMove() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.keyUp.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    /** Drive the real right mouse button through the vanilla MouseHandler. */
    public static void mouseRight(boolean pressed) {
        try {
            Minecraft mc = Minecraft.getInstance();
            java.lang.reflect.Field f = net.minecraft.client.MouseHandler.class
                    .getDeclaredField("isRightPressed");
            f.setAccessible(true);
            f.setBoolean(mc.mouseHandler, pressed);
        } catch (Throwable t) {
            EventPush.pushCombat("mouseRight failed: " + t);
        }
    }

    /** Fully cancel any item use, including the server release packet and injected mouse state. */
    public static void releaseUse(LocalPlayer p) {
        if (p == null) return;
        Minecraft mc = Minecraft.getInstance();
        mouseRight(false);
        mc.options.keyUse.setDown(false);
        if (mc.gameMode != null) mc.gameMode.releaseUsingItem(p);
        p.stopUsingItem();
    }

    public static void attack(LocalPlayer player, Entity target) {
        Minecraft mc = Minecraft.getInstance();
        mc.gameMode.releaseUsingItem(player);
        player.stopUsingItem();
        mc.gameMode.attack(player, target);
        player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
    }

    public static void shieldUp(LocalPlayer player) {
        if (!hasShield(player)) return;
        // Use the real right-click input; a direct gameMode.useItem(OFF_HAND) can leave only a
        // client-side "using" flag and the server never enables shield blocking.
        mouseRight(true);
        Minecraft.getInstance().options.keyUse.setDown(true);
    }

    public static JsonObject armor(LocalPlayer player) {
        JsonArray a = new JsonArray();
        for (int i = 36; i <= 39; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            JsonObject o = new JsonObject();
            o.addProperty("slot", i);
            o.addProperty("item", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
            o.addProperty("damage", s.getDamageValue());
            o.addProperty("maxDamage", s.getMaxDamage());
            o.addProperty("remaining", remaining(s));
            a.add(o);
        }
        JsonObject o = new JsonObject();
        o.add("armor", a);
        return o;
    }

    public static JsonObject baseResult(String outcome, LocalPlayer player) {
        JsonObject o = new JsonObject();
        o.addProperty("outcome", outcome);
        o.addProperty("health", player == null ? -1 : MathUtil.round2(player.getHealth()));
        if (player != null) o.add("armor", armor(player).get("armor"));
        return o;
    }

    public static JsonObject bowProbe(LocalPlayer p) {
        JsonObject o = new JsonObject();
        if (p == null) return o;
        ItemStack main = p.getMainHandItem();
        ItemStack off = p.getOffhandItem();
        o.addProperty("main", main.isEmpty() ? "air" : BuiltInRegistries.ITEM.getKey(main.getItem()).toString());
        o.addProperty("off", off.isEmpty() ? "air" : BuiltInRegistries.ITEM.getKey(off.getItem()).toString());
        o.addProperty("arrows", arrows(p));
        o.addProperty("using", p.isUsingItem());
        o.addProperty("useRemaining", p.getUseItemRemainingTicks());
        o.addProperty("useItem", p.getUseItem().isEmpty() ? "air" : BuiltInRegistries.ITEM.getKey(p.getUseItem().getItem()).toString());
        o.addProperty("selected", p.getInventory().selected);
        o.addProperty("keyUse", Minecraft.getInstance().options.keyUse.isDown());
        o.addProperty("gameMode", Minecraft.getInstance().gameMode == null ? "null" : Minecraft.getInstance().gameMode.getClass().getSimpleName());
        try {
            ItemStack projectile = p.getProjectile(main);
            o.addProperty("projectile", projectile.isEmpty() ? "air" : BuiltInRegistries.ITEM.getKey(projectile.getItem()).toString());
            o.addProperty("projectileCount", projectile.getCount());
        } catch (Throwable t) {
            o.addProperty("projectileError", String.valueOf(t.getMessage()));
        }
        return o;
    }
}
