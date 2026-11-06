package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Ph8 M2.3: immediate environment snapshot for CR.
 *
 * <p>Sounds are read from the vanilla subtitle overlay (the same data source as the bottom-right
 * subtitles), so the text/format matches what the player sees. Time is both game time and real local
 * time; weather is the vanilla level state; season is read reflectively from Serene Seasons when
 * present (no hard dependency).</p>
 */
public final class EnvInfo {
    private static final int MAX_SOUNDS = 8;
    private static final Deque<SoundLine> SOUNDS = new ArrayDeque<>();
    private static volatile boolean listenerReady;
    private static final SoundEventListener LISTENER = new SoundEventListener() {
        @Override
        public void onPlaySound(SoundInstance instance, WeighedSoundEvents events, float volume) {
            try {
                Component subtitle = events == null ? null : events.getSubtitle();
                if (subtitle == null) return;
                String text = subtitle.getString();
                if (text == null || text.isBlank()) return;
                String prefix = directionPrefix(instance);
                synchronized (SOUNDS) {
                    SOUNDS.addFirst(new SoundLine(prefix + text, System.currentTimeMillis()));
                    while (SOUNDS.size() > MAX_SOUNDS) SOUNDS.removeLast();
                }
            } catch (Throwable ignored) {
                // Sound listener must never break the sound engine.
            }
        }
    };

    private EnvInfo() {
    }

    /** Called from the client tick: register the listener once. */
    public static void tick() {
        if (listenerReady) return;
        try {
            SoundManager manager = Minecraft.getInstance().getSoundManager();
            if (manager == null) return;
            manager.addListener(LISTENER);
            listenerReady = true;
        } catch (Throwable ignored) {
            // retry next tick
        }
    }

    private record SoundLine(String text, long at) {
    }

    public static JsonObject snapshot() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            throw Errors.notLoaded("not in a world");
        }
        JsonObject out = new JsonObject();

        JsonArray sounds = new JsonArray();
        for (SoundLine line : soundLines()) {
            JsonObject s = new JsonObject();
            s.addProperty("text", line.text());
            s.addProperty("age", Math.max(0, System.currentTimeMillis() - line.at()));
            sounds.add(s);
        }
        out.add("sounds", sounds);

        JsonObject time = new JsonObject();
        long dayTime = level.getDayTime();
        JsonObject game = new JsonObject();
        game.addProperty("day", dayTime / 24_000L);
        game.addProperty("dayTime", dayTime % 24_000L);
        game.addProperty("phase", phase(dayTime % 24_000L));
        time.add("game", game);
        time.addProperty("real", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        out.add("time", time);

        JsonObject weather = new JsonObject();
        weather.addProperty("rain", level.isRaining());
        weather.addProperty("thunder", level.isThundering());
        out.add("weather", weather);

        JsonObject season = new JsonObject();
        try {
            season = season(level);
        } catch (Throwable t) {
            season.addProperty("available", false);
            season.addProperty("source", "sereneseasons");
            season.addProperty("error", String.valueOf(t.getMessage()));
        }
        out.add("season", season);
        out.addProperty("staleMs", 0);
        return out;
    }

    private static List<SoundLine> soundLines() {
        long now = System.currentTimeMillis();
        List<SoundLine> out = new ArrayList<>();
        synchronized (SOUNDS) {
            for (SoundLine line : SOUNDS) {
                if (now - line.at() <= 4000) out.add(line);
            }
        }
        return out;
    }

    /** Vanilla-style left/right marker; only for sounds with a clear horizontal direction. */
    private static String directionPrefix(SoundInstance instance) {
        try {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p == null) return "";
            double dx = instance.getX() - p.getX();
            double dz = instance.getZ() - p.getZ();
            if (Math.hypot(dx, dz) < 1.0) return "";
            double relative = Math.toDegrees(Math.atan2(-dx, dz)) - p.getYRot();
            while (relative > 180) relative -= 360;
            while (relative < -180) relative += 360;
            if (relative > 20) return "<";
            if (relative < -20) return ">";
            return "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static Object subtitleOverlay(Minecraft mc) throws Exception {
        Object gui = mc.gui;
        if (gui == null) return null;
        Field f = gui.getClass().getDeclaredField("subtitleOverlay");
        f.setAccessible(true);
        return f.get(gui);
    }

    private static List<?> subtitles(Object overlay) throws Exception {
        if (overlay == null) return null;
        Class<?> c = overlay.getClass();
        while (c != null) {
            for (Field f : c.getDeclaredFields()) {
                if (List.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object v = f.get(overlay);
                    if (v instanceof List<?> list) return list;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static JsonObject subtitle(Object sub) {
        if (sub == null) return null;
        try {
            Component text = null;
            String direction = null;
            int age = 0;
            Class<?> c = sub.getClass();
            while (c != null) {
                for (Field f : c.getDeclaredFields()) {
                    f.setAccessible(true);
                    Object v = f.get(sub);
                    if (v instanceof Component comp && text == null) {
                        text = comp;
                    } else if (v instanceof Enum<?> e && direction == null
                            && e.getClass().getSimpleName().toLowerCase().contains("direction")) {
                        direction = e.name();
                    } else if (v instanceof Integer i && f.getName().equalsIgnoreCase("age")) {
                        age = i;
                    }
                }
                c = c.getSuperclass();
            }
            if (text == null) return null;
            String dir = direction == null ? "" : direction;
            String prefix = dir.contains("LEFT") ? "<" : dir.contains("RIGHT") ? ">" : "";
            JsonObject o = new JsonObject();
            o.addProperty("text", prefix + text.getString());
            if (!dir.isEmpty()) o.addProperty("direction", dir);
            o.addProperty("age", age);
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    private static JsonObject season(ClientLevel level) throws Exception {
        JsonObject o = new JsonObject();
        Class<?> helper;
        try {
            helper = Class.forName("sereneseasons.api.season.SeasonHelper");
        } catch (ClassNotFoundException e) {
            o.addProperty("available", false);
            o.addProperty("source", "not_installed");
            return o;
        }
        Method getState = null;
        for (Method m : helper.getMethods()) {
            if (m.getName().equals("getSeasonState") && m.getParameterCount() == 1) {
                getState = m;
                break;
            }
        }
        if (getState == null) {
            o.addProperty("available", false);
            o.addProperty("source", "sereneseasons");
            o.addProperty("error", "getSeasonState not found");
            return o;
        }
        Object state = getState.invoke(null, level);
        if (state == null) {
            o.addProperty("available", false);
            o.addProperty("source", "sereneseasons");
            return o;
        }
        o.addProperty("available", true);
        o.addProperty("source", "sereneseasons");
        Object season = invoke(state, "getSeason");
        Object sub = invoke(state, "getSubSeason");
        Object day = invoke(state, "getDay");
        if (season != null) o.addProperty("season", String.valueOf(season));
        if (sub != null) o.addProperty("subSeason", String.valueOf(sub));
        if (day instanceof Number n) o.addProperty("day", n.intValue());
        return o;
    }

    private static Object invoke(Object target, String name) {
        try {
            Method m = target.getClass().getMethod(name);
            return m.invoke(target);
        } catch (Throwable t) {
            try {
                Method m = target.getClass().getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static String phase(long t) {
        if (t < 1000) return "sunrise";
        if (t < 6000) return "morning";
        if (t < 12000) return "noon";
        if (t < 13000) return "sunset";
        if (t < 18000) return "night";
        return "midnight";
    }
}
