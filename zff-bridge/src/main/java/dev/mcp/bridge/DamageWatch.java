package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Watches the local player's health and records every drop together with a best-effort cause
 * (damage source from the packet when available, then fall/fire/drown/starve/attacker heuristics).
 */
public final class DamageWatch {
    private static final int MAX = 40;
    private static final Deque<JsonObject> EVENTS = new ArrayDeque<>();
    private static float lastTotal = -1;
    private static float fallPeak;
    private static long lastEventAt;

    private DamageWatch() {
    }

    public static synchronized void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) {
            lastTotal = -1;
            fallPeak = 0;
            return;
        }
        if (!p.onGround() && p.fallDistance > fallPeak) {
            fallPeak = (float) p.fallDistance;
        }
        float total = p.getHealth() + p.getAbsorptionAmount();
        if (lastTotal < 0) {
            lastTotal = total;
            if (p.onGround()) {
                fallPeak = 0;
            }
            return;
        }
        if (total < lastTotal - 0.01F) {
            JsonObject event = build(p, lastTotal, total);
            EVENTS.addLast(event);
            while (EVENTS.size() > MAX) {
                EVENTS.removeFirst();
            }
            lastEventAt = System.currentTimeMillis();
            EventJournal.push("damage", event);
        }
        lastTotal = total;
        if (p.onGround()) {
            fallPeak = 0;
        }
    }

    public static synchronized JsonArray recent(int limit) {
        int n = MathUtil.clamp(limit, 1, MAX);
        JsonArray out = new JsonArray();
        int skip = Math.max(0, EVENTS.size() - n);
        int i = 0;
        for (JsonObject event : EVENTS) {
            if (i++ >= skip) {
                out.add(event.deepCopy());
            }
        }
        return out;
    }

    public static synchronized JsonObject lastEvent() {
        return EVENTS.peekLast();
    }

    public static synchronized long lastEventAt() {
        return lastEventAt;
    }

    private static JsonObject build(LocalPlayer p, float before, float after) {
        JsonObject e = new JsonObject();
        e.addProperty("at", System.currentTimeMillis());
        e.addProperty("amount", MathUtil.round2(before - after));
        e.addProperty("healthBefore", MathUtil.round2(before));
        e.addProperty("healthAfter", MathUtil.round2(after));
        e.addProperty("cause", cause(p));
        e.addProperty("fallDistance", MathUtil.round2(Math.max(fallPeak, p.fallDistance)));
        e.addProperty("onGround", p.onGround());
        e.addProperty("onFire", p.isOnFire());
        e.addProperty("food", p.getFoodData().getFoodLevel());
        e.addProperty("air", p.getAirSupply());
        e.addProperty("x", MathUtil.round2(p.getX()));
        e.addProperty("y", MathUtil.round2(p.getY()));
        e.addProperty("z", MathUtil.round2(p.getZ()));
        LivingEntity attacker = p.getLastHurtByMob();
        if (attacker != null && p.tickCount - p.getLastHurtByMobTimestamp() < 100) {
            e.addProperty("attacker", type(attacker));
            e.addProperty("attackerDistance", MathUtil.round2(attacker.distanceTo(p)));
        }
        JsonArray effects = new JsonArray();
        for (MobEffectInstance effect : p.getActiveEffects()) {
            effects.add(BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()) + ":" + effect.getAmplifier());
        }
        e.add("effects", effects);
        return e;
    }

    private static String cause(LocalPlayer p) {
        DamageSource source = p.getLastDamageSource();
        if (source != null) {
            String id = source.getMsgId();
            Entity attacker = source.getEntity();
            if (attacker != null) {
                return id + ":" + type(attacker);
            }
            if (source.getDirectEntity() != null) {
                return id + ":" + type(source.getDirectEntity());
            }
            return id;
        }
        LivingEntity attacker = p.getLastHurtByMob();
        if (attacker != null && p.tickCount - p.getLastHurtByMobTimestamp() < 60) {
            return "mob:" + type(attacker);
        }
        if (fallPeak > 1.5) {
            return "fall";
        }
        if (p.isOnFire()) {
            return "fire";
        }
        if (p.getAirSupply() < 200) {
            return "drown";
        }
        if (p.getFoodData().getFoodLevel() <= 0) {
            return "starve";
        }
        return "unknown";
    }

    private static String type(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }
}
