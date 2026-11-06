package dev.mcp.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;

/**
 * When the death screen appears, wait a few ticks and respawn automatically, recording the death and
 * its last damage cause. This keeps unattended survival runs from getting stuck dead.
 */
public final class AutoRespawn {
    private static boolean enabled = true;
    private static int deaths;
    private static JsonObject lastDeath;
    private static int screenTicks;

    private AutoRespawn() {
    }

    public static synchronized void tick() {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (mc.screen instanceof DeathScreen) {
            screenTicks++;
            if (screenTicks == 1) {
                JsonObject death = new JsonObject();
                death.addProperty("at", System.currentTimeMillis());
                if (player != null) {
                    death.addProperty("x", MathUtil.round2(player.getX()));
                    death.addProperty("y", MathUtil.round2(player.getY()));
                    death.addProperty("z", MathUtil.round2(player.getZ()));
                    if (mc.level != null) {
                        death.addProperty("dimension", mc.level.dimension().location().toString());
                    }
                }
                JsonObject damage = DamageWatch.lastEvent();
                if (damage != null) {
                    death.add("lastDamage", damage.deepCopy());
                }
                lastDeath = death;
                EventJournal.push("death", death);
            }
            // The death screen can appear one tick before DamageWatch sees the fatal health drop;
            // attach the event as soon as it exists so lastDeath always explains the death.
            if (lastDeath != null && !lastDeath.has("lastDamage")) {
                JsonObject damage = DamageWatch.lastEvent();
                if (damage != null) {
                    lastDeath.add("lastDamage", damage.deepCopy());
                }
            }
            if (screenTicks >= 10) {
                try {
                    if (player != null) {
                        player.respawn();
                    }
                } catch (Throwable ignored) {
                    // fall through to closing the screen
                }
                mc.setScreen(null);
                deaths++;
                screenTicks = 0;
                JsonObject respawned = new JsonObject();
                respawned.addProperty("deaths", deaths);
                EventJournal.push("respawn", respawned);
            }
        } else {
            screenTicks = 0;
        }
    }

    public static synchronized JsonObject status() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("deaths", deaths);
        if (lastDeath != null) {
            o.add("lastDeath", lastDeath.deepCopy());
        }
        return o;
    }

    public static synchronized JsonObject set(boolean value) {
        enabled = value;
        return status();
    }
}
