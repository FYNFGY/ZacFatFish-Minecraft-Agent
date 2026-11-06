package dev.mcp.bridge;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * ZFF bridge configuration (Ph5). Reads {@code config/zff_bridge.config} (simple key=value), creating
 * it with defaults on first run. The watchdog timings live here because the requirement says the
 * watchdog must be provided by the mod, not by the Node side.
 */
public final class ZffConfig {
    /** 3 s, as required by docs/architechture.md ("看门狗一律设置为 3s"). */
    public static int watchdogMs = 3000;
    public static double towerDeltaY = 1.5;
    public static double walkDeltaXZ = 1.0;
    public static int reportIntervalMs = 500;
    /** CR (chatter) is disabled until release; the Java side only exposes the flag. */
    public static boolean crEnabled = false;
    /**
     * Ph5 T3: when true the mod owns the watchdog (Java tick) and the Node sensors must not run their
     * own copy, otherwise every stall would be reported twice.
     */
    public static boolean watchdogInMod = true;
    /** Identifier of the AI that writes memory/todo through this client. */
    public static String actorId = "ai-1";

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("zff_bridge.config");
    }

    private ZffConfig() {
    }

    public static synchronized void load() {
        Path path = file();
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, defaults(), StandardCharsets.UTF_8);
            }
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                switch (key) {
                    case "watchdogMs" -> watchdogMs = clampInt(value, 3000, 200, 60000);
                    case "towerDeltaY" -> towerDeltaY = clampDouble(value, 1.5, 0.05, 4.0);
                    case "walkDeltaXZ" -> walkDeltaXZ = clampDouble(value, 1.0, 0.1, 8.0);
                    case "reportIntervalMs" -> reportIntervalMs = clampInt(value, 500, 100, 10000);
                    case "crEnabled" -> crEnabled = Boolean.parseBoolean(value);
                    case "watchdogInMod" -> watchdogInMod = Boolean.parseBoolean(value);
                    case "actorId" -> actorId = value.isBlank() ? "ai-1" : value;
                    default -> { /* unknown key: ignore, the file is user editable */ }
                }
            }
            System.out.println("[zff] config loaded: watchdogMs=" + watchdogMs + " towerDeltaY=" + towerDeltaY
                    + " walkDeltaXZ=" + walkDeltaXZ + " crEnabled=" + crEnabled
                    + " watchdogInMod=" + watchdogInMod + " actorId=" + actorId);
        } catch (IOException | RuntimeException e) {
            System.out.println("[zff] config load failed (" + e.getMessage() + "), using defaults");
        }
    }

    public static synchronized void saveDefaults() {
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, defaults(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[zff] could not write " + file() + ": " + e.getMessage());
        }
    }

    private static String defaults() {
        return "# ZFF bridge config (Ph5)\n"
                + "# watchdog: no movement for this long => stuck event (requirement: 3 s)\n"
                + "watchdogMs=3000\n"
                + "# towering: |dy| below this counts as not moving\n"
                + "towerDeltaY=1.5\n"
                + "# walking/pathing: |dxz| below this counts as not moving\n"
                + "walkDeltaXZ=1.0\n"
                + "# how often the watchdog samples\n"
                + "reportIntervalMs=500\n"
                + "# chatter (CR) is disabled during development\n"
                + "crEnabled=false\n"
                + "# the mod owns the watchdog (Java tick); the Node sensors must not report a second copy\n"
                + "watchdogInMod=true\n"
                + "# identity written into the server-side memory/todo store\n"
                + "actorId=ai-1\n";
    }

    private static int clampInt(String v, int def, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(v)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double clampDouble(String v, double def, double min, double max) {
        try {
            return Math.max(min, Math.min(max, Double.parseDouble(v)));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
