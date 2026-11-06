package dev.mcp.bridge;

import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

/** Small math helpers. */
public final class MathUtil {
    private MathUtil() {
    }

    public static double distance(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static double distanceXZ(double x1, double z1, double x2, double z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static int randomBetween(int min, int max) {
        if (max <= min) {
            return min;
        }
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    public static Vec3 center(int x, int y, int z) {
        return new Vec3(x + 0.5, y + 0.5, z + 0.5);
    }

    public static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
