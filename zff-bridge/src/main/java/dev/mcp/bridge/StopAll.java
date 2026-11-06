package dev.mcp.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/**
 * Round-3 "global stop": cancels every job, stops Baritone and the scheduler, releases the current
 * mining target and closes transient screens. Runs on the client thread and takes effect within one
 * tick, so a chat "stop" can be honoured before the agent's next action.
 */
public final class StopAll {
    private StopAll() {
    }

    public static JsonObject run(String reason) {
        long t0 = System.nanoTime();
        String why = (reason == null || reason.isBlank()) ? "user_stop" : reason;
        JsonObject out = Json.ok();
        out.addProperty("reason", why);

        int jobsCancelled = 0;
        Jobs manager = BridgeRuntime.jobs;
        if (manager != null) {
            jobsCancelled = manager.cancelAll(why);
        }
        out.addProperty("jobsCancelled", jobsCancelled);

        boolean baritone = false;
        try {
            BaritoneControl.cancel();
            baritone = true;
        } catch (Throwable ignored) {
        }
        out.addProperty("baritoneStopped", baritone);

        int tasksDropped = TickScheduler.clear();
        out.addProperty("tasksDropped", tasksDropped);

        Minecraft mc = Minecraft.getInstance();
        boolean screenClosed = false;
        try {
            if (mc.gameMode != null) {
                mc.gameMode.stopDestroyBlock();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (mc.player != null) {
                // Tell the server the container/screen is closed as well.
                mc.player.closeContainer();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (mc.screen != null) {
                mc.setScreen(null);
                screenClosed = true;
            }
        } catch (Throwable ignored) {
        }
        out.addProperty("screenClosed", screenClosed);

        long ms = (System.nanoTime() - t0) / 1_000_000L;
        out.addProperty("ms", ms);

        JsonObject event = new JsonObject();
        event.addProperty("reason", why);
        event.addProperty("jobsCancelled", jobsCancelled);
        EventJournal.push("stop", event);
        return out;
    }
}
