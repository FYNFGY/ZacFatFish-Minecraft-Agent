package dev.mcp.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Method;
import java.util.Collection;

/**
 * Ph8 M2.2 Create specialisation: speed controllers expose a ScrollValueBehaviour instead of a normal
 * container screen. This adapter reads/writes that behaviour directly, so CR does not need to open a
 * canvas GUI or use OCR.
 */
public final class CreateAdapter {
    private CreateAdapter() {
    }

    public static JsonObject scroll(BlockPos pos, int requested) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) throw Errors.notLoaded("not in a world");
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) throw Errors.internal("no block entity at " + pos.toShortString());
        try {
            Method all = be.getClass().getMethod("getAllBehaviours");
            Object value = all.invoke(be);
            if (value instanceof Collection<?> behaviours) {
                for (Object b : behaviours) {
                    if (b == null) continue;
                    String name = b.getClass().getName();
                    if (!name.endsWith("ScrollValueBehaviour")) continue;
                    Method set = b.getClass().getMethod("setValue", int.class);
                    Method get = b.getClass().getMethod("getValue");
                    int before = (int) get.invoke(b);
                    int target = requested;
                    Object between = b.getClass().getMethod("between", int.class, int.class);
                    // no direct min/max accessor; clamp through the behaviour's own setter callback.
                    set.invoke(b, target);
                    int after = (int) get.invoke(b);
                    JsonObject o = Json.ok();
                    o.addProperty("before", before);
                    o.addProperty("after", after);
                    o.addProperty("value", target);
                    o.addProperty("pos", pos.toShortString());
                    o.addProperty("behaviour", name);
                    return o;
                }
            }
        } catch (Throwable t) {
            throw new Errors.BridgeException(Errors.INTERNAL, "Create scroll adapter failed: " + t.getMessage());
        }
        throw new Errors.BridgeException(Errors.UNSUPPORTED, "no ScrollValueBehaviour at " + pos.toShortString());
    }

    public static JsonObject probe(BlockPos pos) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) throw Errors.notLoaded("not in a world");
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) throw Errors.internal("no block entity at " + pos.toShortString());
        JsonObject o = Json.ok();
        o.addProperty("blockEntity", be.getClass().getName());
        try {
            Method all = be.getClass().getMethod("getAllBehaviours");
            Object value = all.invoke(be);
            if (value instanceof Collection<?> behaviours) {
                for (Object b : behaviours) {
                    if (b == null) continue;
                    String name = b.getClass().getName();
                    if (!name.endsWith("ScrollValueBehaviour")) continue;
                    Object v = b.getClass().getMethod("getValue").invoke(b);
                    o.addProperty("behaviour", name);
                    o.addProperty("value", v instanceof Number n ? n.intValue() : -1);
                    return o;
                }
            }
        } catch (Throwable t) {
            o.addProperty("error", String.valueOf(t.getMessage()));
        }
        return o;
    }
}
