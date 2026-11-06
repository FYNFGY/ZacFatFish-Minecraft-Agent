package dev.mcp.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;

import java.util.List;

/**
 * Ph8 M3: opt-in self protection. On damage from a non-player hostile it cancels other jobs and
 * starts a close-range kill against the damage source. Attack/escape jobs are never interrupted.
 */
public final class SelfProtect {
    private static volatile boolean enabled;
    private static volatile boolean shieldHeld;
    private static final long COOLDOWN_MS = 1200;
    private static volatile long lastTrigger;

    private SelfProtect() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static com.google.gson.JsonObject set(boolean on) {
        enabled = on;
        com.google.gson.JsonObject o = Json.ok();
        o.addProperty("enabled", on);
        return o;
    }

    /** Called every client tick: react to a nearby creeper before it explodes, not after. */
    public static void tick(LocalPlayer player) {
        if (!enabled || player == null || !player.isAlive()) return;
        // Falling has priority over creeper/shield logic.
        AutoMlg.tick(player);
        List<Entity> list = player.level().getEntities(player, player.getBoundingBox().inflate(8),
                e -> e instanceof Creeper && e.isAlive() && PathReachability.reachable(player, e, 15));
        if (list.isEmpty()) {
            if (shieldHeld) {
                shieldHeld = false;
                CombatUtil.releaseUse(player);
                EventPush.pushCombat("self-protect: creeper gone, shield lowered");
            }
            return;
        }
        Entity creeper = list.get(0);
        double best = player.distanceToSqr(creeper);
        for (Entity e : list) {
            double d = player.distanceToSqr(e);
            if (d < best) {
                best = d;
                creeper = e;
            }
        }
        if (CombatUtil.hasShield(player)) {
            boolean wasHeld = shieldHeld;
            CombatUtil.face(player, creeper);
            CombatUtil.shieldUp(player);
            shieldHeld = true;
            if (!wasHeld) {
                EventPush.pushCombat("self-protect: creeper nearby, shield raised");
            }
            return;
        }
        Jobs jobs = BridgeRuntime.jobs;
        if (jobs == null) return;
        for (Jobs.Job active : jobs.active()) {
            if (active.tool.equals("escape")) return;
        }
        jobs.cancelAll("creeper_no_shield");
        jobs.create("escape", 120, new EscapeJob(5));
        EventPush.pushCombat("self-protect: creeper nearby, no shield, fleeing");
    }

    /** Called from the client tick when health drops. */
    public static void onDamage(LocalPlayer player, Entity source) {
        if (!enabled || player == null || !player.isAlive() || !(source instanceof Enemy)) return;
        long now = System.currentTimeMillis();
        if (now - lastTrigger < COOLDOWN_MS) return;
        Jobs jobs = BridgeRuntime.jobs;
        if (jobs != null) {
            for (Jobs.Job active : jobs.active()) {
                String tool = active.tool;
                // Close-range attacks and escapes are never interrupted. A far/remote attack IS
                // interruptible by self-protection.
                if (tool.equals("kill_target_close") || tool.equals("escape")) {
                    return;
                }
            }
        }
        if (source instanceof Creeper || nearCreeper(player)) {
            if (CombatUtil.hasShield(player)) {
                lastTrigger = now;
                CombatUtil.shieldUp(player);
                EventPush.pushCombat("self-protect: creeper detected, shield raised");
                return;
            }
            if (jobs != null) {
                lastTrigger = now;
                jobs.cancelAll("self_protect");
                jobs.create("escape", 120, new EscapeJob(5));
                EventPush.pushCombat("self-protect: creeper detected, fleeing");
            }
            return;
        }
        if (jobs != null) {
            lastTrigger = now;
            jobs.cancelAll("self_protect");
            jobs.create("kill_target_close", 120, new KillTargetCloseJob("", 16.0, source.getId()));
            EventPush.pushCombat("self-protect: counterattacking " + source.getName().getString());
        }
    }

    private static boolean nearCreeper(LocalPlayer player) {
        List<Entity> list = player.level().getEntities(player, player.getBoundingBox().inflate(8),
                e -> e instanceof Creeper && e.isAlive() && PathReachability.reachable(player, e, 15));
        return !list.isEmpty();
    }
}
