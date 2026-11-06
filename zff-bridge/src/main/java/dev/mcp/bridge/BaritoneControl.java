package dev.mcp.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Baritone is optional. The mod id is {@code baritoe} in Baritone 1.11.3's NeoForge metadata.
 * All access is reflective so the bridge never hard-depends on Baritone.
 */
public final class BaritoneControl {
    private static final Logger LOG = LoggerFactory.getLogger("mcpbridge/baritone");
    private static volatile boolean warned;

    private BaritoneControl() {
    }

    public static boolean isLoaded() {
        try {
            return ModList.get().isLoaded("baritoe");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Baritone's look behaviour is disabled in some modpacks (freeLook, camera mods).
     * Turning freeLook off and steering toward the next path node keeps the movement
     * keys aligned with the path even when another mod owns the camera.
     */
    public static void configureForPathing() {
        setMovementPolicy(PathPolicy.breakAllowed(), PathPolicy.placeAllowed());
    }

    /** Applies the break/place policy (P1-10) to Baritone. Safe when Baritone is absent. */
    public static void setMovementPolicy(boolean allowBreak, boolean allowPlace) {
        if (!isLoaded()) {
            return;
        }
        try {
            Object settings = invokeStatic("baritone.api.BaritoneAPI", "getSettings");
            setBoolean(settings, "freeLook", false);
            setBoolean(settings, "blockFreeLook", false);
            // Pathing must only walk: Baritone's own digging can stall (its aim fights the camera and
            // the destroy progress resets). Mining is done by our own aim-locked executors instead.
            setBoolean(settings, "allowBreak", allowBreak);
            setBoolean(settings, "allowPlace", allowPlace);
        } catch (Throwable t) {
            LOG.debug("could not configure Baritone look settings: {}", t.toString());
        }
    }

    private static void setBoolean(Object settings, String name, boolean value) {
        try {
            java.lang.reflect.Field field = settings.getClass().getField(name);
            Object setting = field.get(settings);
            java.lang.reflect.Field valueField = setting.getClass().getField("value");
            valueField.set(setting, value);
        } catch (Throwable ignored) {
        }
    }

    /** Rotate the client player toward the next path node (or the goal if no path yet). */
    public static void steer(int goalX, int goalY, int goalZ) {
        net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        double tx = goalX + 0.5;
        double ty = goalY + 0.5;
        double tz = goalZ + 0.5;
        try {
            Object baritone = primary();
            Object behavior = invoke(baritone, "baritone.api.IBaritone", "getPathingBehavior");
            Object pathOpt = invoke(behavior, "baritone.api.behavior.IPathingBehavior", "getPath");
            if (pathOpt instanceof Optional<?> o && o.isPresent()) {
                Object path = o.get();
                Object positionsObj = invoke(path, "baritone.api.pathing.calc.IPath", "positions");
                if (positionsObj instanceof java.util.List<?> positions && !positions.isEmpty()) {
                    int index = 0;
                    Object current = invoke(behavior, "baritone.api.behavior.IPathingBehavior", "getCurrent");
                    if (current != null) {
                        Object pos = invoke(current, "baritone.api.pathing.path.IPathExecutor", "getPosition");
                        if (pos instanceof Number n) {
                            index = n.intValue();
                        }
                    }
                    int next = Math.min(index + 1, positions.size() - 1);
                    if (positions.get(next) instanceof net.minecraft.core.Vec3i v) {
                        tx = v.getX() + 0.5;
                        ty = v.getY() + 0.5;
                        tz = v.getZ() + 0.5;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fall back to the goal.
        }
        double dx = tx - player.getX();
        double dy = ty - player.getEyeY();
        double dz = tz - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(0.001, horizontal)));
        // Smooth the turn instead of snapping every tick (snapping is what made the camera jitter).
        float currentYaw = player.getYRot();
        float deltaYaw = net.minecraft.util.Mth.wrapDegrees(targetYaw - currentYaw);
        float yaw = currentYaw + net.minecraft.util.Mth.clamp(deltaYaw * 0.35F, -9.0F, 9.0F);
        float deltaPitch = targetPitch - player.getXRot();
        float pitch = player.getXRot() + net.minecraft.util.Mth.clamp(deltaPitch * 0.3F, -6.0F, 6.0F);
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.setYHeadRot(yaw);
    }

    public static final class Status {
        public boolean pathing;
        public boolean active;
        public boolean hasPath;
        public Integer etaTicks;
        public Integer pathLength;
        public Integer pathIndex;
        public String error;
    }

    public static Status status() {
        Status s = new Status();
        if (!isLoaded()) {
            s.error = "baritone is not installed";
            return s;
        }
        try {
            Object baritone = primary();
            Object behavior = invoke(baritone, "baritone.api.IBaritone", "getPathingBehavior");
            s.pathing = (Boolean) invoke(behavior, "baritone.api.behavior.IPathingBehavior", "isPathing");
            Object goal = invoke(behavior, "baritone.api.behavior.IPathingBehavior", "getGoal");
            s.hasPath = goal != null;
            Object active = invoke(baritone, "baritone.api.IBaritone", "getCustomGoalProcess");
            s.active = (Boolean) invoke(active, "baritone.api.process.IBaritoneProcess", "isActive");
            Object eta = invoke(behavior, "baritone.api.behavior.IPathingBehavior", "estimatedTicksToGoal");
            if (eta instanceof Optional<?> o && o.isPresent() && o.get() instanceof Number n) {
                s.etaTicks = (int) Math.round(n.doubleValue());
            }
            Object pathOpt = invoke(behavior, "baritone.api.behavior.IPathingBehavior", "getPath");
            if (pathOpt instanceof Optional<?> o && o.isPresent()) {
                Object path = o.get();
                Object len = invoke(path, "baritone.api.pathing.calc.IPath", "length");
                if (len instanceof Number n) {
                    s.pathLength = n.intValue();
                }
            }
            Object current = invoke(behavior, "baritone.api.behavior.IPathingBehavior", "getCurrent");
            if (current != null) {
                Object idx = invoke(current, "baritone.api.pathing.path.IPathExecutor", "getPosition");
                if (idx instanceof Number n) {
                    s.pathIndex = n.intValue();
                }
            }
        } catch (Throwable t) {
            s.error = message(t);
            if (!warned) {
                warned = true;
                LOG.warn("Baritone reflection failed: {}", t.toString());
            }
        }
        return s;
    }

    /**
     * Starts a path. range == 0 means exact block; range > 0 means GoalNear.
     * Throws {@code unsupported} when Baritone is absent or its API changed.
     */
    public static void start(int x, int y, int z, int range) {
        if (!isLoaded()) {
            throw Errors.unsupported("Baritone (mod id baritoe) is not installed");
        }
        try {
            Object baritone = primary();
            Object process = invoke(baritone, "baritone.api.IBaritone", "getCustomGoalProcess");
            Object goal;
            if (range > 0) {
                Class<?> goalNear = Class.forName("baritone.api.pathing.goals.GoalNear");
                goal = goalNear.getConstructor(BlockPos.class, int.class)
                        .newInstance(new BlockPos(x, y, z), range);
            } else {
                Class<?> goalBlock = Class.forName("baritone.api.pathing.goals.GoalBlock");
                goal = goalBlock.getConstructor(int.class, int.class, int.class).newInstance(x, y, z);
            }
            Method set = Class.forName("baritone.api.process.ICustomGoalProcess")
                    .getMethod("setGoalAndPath", Class.forName("baritone.api.pathing.goals.Goal"));
            set.setAccessible(true);
            set.invoke(process, goal);
        } catch (Throwable t) {
            throw Errors.unsupported("could not start Baritone: " + message(t));
        }
    }

    public static void cancel() {
        if (!isLoaded()) {
            return;
        }
        try {
            Object baritone = primary();
            Object behavior = invoke(baritone, "baritone.api.IBaritone", "getPathingBehavior");
            invoke(behavior, "baritone.api.behavior.IPathingBehavior", "cancelEverything");
        } catch (Throwable t) {
            LOG.debug("Baritone cancel failed: {}", t.toString());
        }
    }

    public static boolean isActive() {
        try {
            Status s = status();
            return s.pathing || s.active;
        } catch (Throwable t) {
            return false;
        }
    }

    public static double distanceTo(int x, int y, int z) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            return Double.NaN;
        }
        double dx = p.getX() - (x + 0.5);
        double dy = p.getY() - y;
        double dz = p.getZ() - (z + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static JsonObject goalJson(int x, int y, int z, int range) {
        JsonObject g = new JsonObject();
        g.add("pos", Json.intArray(x, y, z));
        g.addProperty("range", range);
        return g;
    }

    private static Object primary() throws Exception {
        Object provider = invokeStatic("baritone.api.BaritoneAPI", "getProvider");
        return invoke(provider, "baritone.api.IBaritoneProvider", "getPrimaryBaritone");
    }

    private static Object invokeStatic(String iface, String method) throws Exception {
        Method m = Class.forName(iface).getMethod(method);
        m.setAccessible(true);
        return m.invoke(null);
    }

    private static Object invoke(Object target, String iface, String method, Object... args) throws Exception {
        Class<?> c = Class.forName(iface);
        Method selected = null;
        for (Method m : c.getMethods()) {
            if (!m.getName().equals(method)) {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            if (params.length != args.length) {
                continue;
            }
            boolean ok = true;
            for (int i = 0; i < params.length; i++) {
                if (args[i] == null) {
                    if (params[i].isPrimitive()) {
                        ok = false;
                        break;
                    }
                } else if (!params[i].isInstance(args[i]) && !boxed(params[i]).isInstance(args[i])) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                selected = m;
                break;
            }
        }
        if (selected == null) {
            throw new NoSuchMethodException(iface + "#" + method + "/" + args.length);
        }
        selected.setAccessible(true);
        return selected.invoke(target, args);
    }

    private static Class<?> boxed(Class<?> c) {
        if (c == boolean.class) return Boolean.class;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == double.class) return Double.class;
        if (c == float.class) return Float.class;
        if (c == byte.class) return Byte.class;
        if (c == short.class) return Short.class;
        if (c == char.class) return Character.class;
        return c;
    }

    private static String message(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        String m = c.getMessage();
        return m == null || m.isBlank() ? c.getClass().getSimpleName() : m;
    }
}
