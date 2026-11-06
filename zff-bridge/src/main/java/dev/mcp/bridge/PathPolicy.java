package dev.mcp.bridge;

import com.google.gson.JsonObject;

/**
 * Pathing policy toggles (Ph4 P1-10): whether pathing jobs may break blocks / place blocks.
 *
 * <p>The default is conservative (no breaking, no placing) because silently modifying the world
 * while walking is exactly what produced the Ph3 ghost blocks and the destroyed plants. Dig jobs
 * are unaffected - they always break the blocks they are asked to break.</p>
 */
public final class PathPolicy {
    private static volatile boolean allowBreak;
    private static volatile boolean allowPlace;

    private PathPolicy() {
    }

    public static boolean breakAllowed() {
        return allowBreak;
    }

    public static boolean placeAllowed() {
        return allowPlace;
    }

    public static void set(boolean breakBlocks, boolean placeBlocks) {
        allowBreak = breakBlocks;
        allowPlace = placeBlocks;
        BaritoneControl.setMovementPolicy(allowBreak, allowPlace);
    }

    public static JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("allowBreak", allowBreak);
        o.addProperty("allowPlace", allowPlace);
        return o;
    }
}
