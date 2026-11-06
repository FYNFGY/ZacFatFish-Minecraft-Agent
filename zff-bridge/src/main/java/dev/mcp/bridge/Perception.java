package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Runtime block/entity/collision queries. Every method runs on the client thread. */
public final class Perception {
    private Perception() {
    }

    public static JsonObject scanArea(JsonObject args) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            throw Errors.notLoaded("the player is not in a world");
        }
        int radius = MathUtil.clamp(Json.i(args, "radius", 8), 1, 32);
        int maxBlocks = MathUtil.clamp(Json.i(args, "maxBlocks", 1200), 1, 6000);
        boolean includeAir = Json.b(args, "includeAir", false);
        boolean includeProperties = Json.b(args, "includeProperties", false);
        boolean includeCollision = Json.b(args, "includeCollision", false);
        boolean includeEntities = Json.b(args, "includeEntities", true);
        String shape = Json.str(args, "shape", "sphere").toLowerCase(Locale.ROOT);
        String filter = Json.str(args, "filter", null);
        int[] center = center(args, player);
        BlockPos centerPos = new BlockPos(center[0], center[1], center[2]);
        int minY = Math.max(level.getMinBuildHeight(), centerPos.getY() - radius);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, centerPos.getY() + radius);

        JsonArray blocks = new JsonArray();
        Map<String, Integer> counts = new LinkedHashMap<>();
        int matched = 0;
        int unloaded = 0;
        boolean truncated = false;
        int r2 = radius * radius;

        for (int y = minY; y <= maxY; y++) {
            for (int z = centerPos.getZ() - radius; z <= centerPos.getZ() + radius; z++) {
                for (int x = centerPos.getX() - radius; x <= centerPos.getX() + radius; x++) {
                    int dx = x - centerPos.getX();
                    int dy = y - centerPos.getY();
                    int dz = z - centerPos.getZ();
                    if (shape.equals("sphere") && dx * dx + dy * dy + dz * dz > r2) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.hasChunkAt(pos)) {
                        unloaded++;
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (!includeAir && state.isAir()) {
                        continue;
                    }
                    String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    if (!matches(state, id, filter)) {
                        continue;
                    }
                    matched++;
                    counts.merge(id, 1, Integer::sum);
                    if (blocks.size() >= maxBlocks) {
                        truncated = true;
                        continue;
                    }
                    JsonArray entry = new JsonArray();
                    entry.add(x);
                    entry.add(y);
                    entry.add(z);
                    entry.add(id);
                    if (includeProperties) {
                        entry.add(properties(state));
                    }
                    if (includeCollision) {
                        entry.add(collision(level, pos, state));
                    }
                    blocks.add(entry);
                }
            }
        }

        JsonObject out = Json.ok();
        out.addProperty("coordMode", "block");
        out.add("center", Json.intArray(centerPos.getX(), centerPos.getY(), centerPos.getZ()));
        out.addProperty("radius", radius);
        out.addProperty("shape", shape);
        out.addProperty("blocksFormat", includeProperties || includeCollision
                ? "[x,y,z,id,properties?,collision?]" : "[x,y,z,id]");
        out.add("blocks", blocks);
        out.add("blockCounts", counts(counts, 40));
        out.addProperty("matchedBlocks", matched);
        out.addProperty("returnedBlocks", blocks.size());
        out.addProperty("unloadedPositions", unloaded);
        out.addProperty("truncated", truncated);

        if (includeEntities) {
            JsonArray entities = new JsonArray();
            AABB area = new AABB(
                    centerPos.getX() - radius, centerPos.getY() - radius, centerPos.getZ() - radius,
                    centerPos.getX() + radius + 1, centerPos.getY() + radius + 1, centerPos.getZ() + radius + 1);
            List<Entity> found = level.getEntities(player, area, e -> e != player);
            found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(player)));
            int maxEntities = 100;
            for (Entity e : found) {
                if (entities.size() >= maxEntities) {
                    out.addProperty("entitiesTruncated", true);
                    break;
                }
                entities.add(entity(e, player));
            }
            out.add("entities", entities);
            out.addProperty("returnedEntities", entities.size());
        }
        return cap(out);
    }

    public static JsonObject blockQuery(JsonObject args) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            throw Errors.notLoaded("the player is not in a world");
        }
        List<int[]> positions = positions(args, mc.player, 64);
        JsonArray out = new JsonArray();
        for (int[] p : positions) {
            out.add(blockInfo(level, new BlockPos(p[0], p[1], p[2]), true));
        }
        JsonObject o = Json.ok();
        o.addProperty("coordMode", "block");
        o.add("blocks", out);
        return cap(o);
    }

    public static JsonObject entityQuery(JsonObject args) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            throw Errors.notLoaded("the player is not in a world");
        }
        // ISSUE-12: 大半径一次性 getEntities 会“静默返回空”（区块未加载 / 查询过慢），调用方无法区分
        // “真的没有”和“没查到”。半径 > 48 时改为 32 格同心环分块扫描、按 entityId 去重合并，
        // 并把 scanMode / rings / candidates 一起回传，绝不静默给空数组。
        int requested = MathUtil.clamp(Json.i(args, "radius", 16), 1, 128);
        int radius = requested;
        int limit = MathUtil.clamp(Json.i(args, "limit", 64), 1, 200);
        String typeFilter = Json.str(args, "type", null);
        String nameFilter = Json.str(args, "name", null);
        double cx = player.getX();
        double cy = player.getY();
        double cz = player.getZ();
        java.util.LinkedHashMap<Integer, Entity> foundById = new java.util.LinkedHashMap<>();
        int scanBoxes = 0;
        int rings = 0;
        int scanErrors = 0;
        boolean ringMode = radius > 48;
        if (!ringMode) {
            AABB area = player.getBoundingBox().inflate(radius);
            scanBoxes++;
            for (Entity e : level.getEntities(player, area, candidate -> candidate != player)) {
                foundById.putIfAbsent(e.getId(), e);
            }
        } else {
            int step = 32;
            for (int outer = step; ; outer += step) {
                double r = Math.min(radius, outer);
                AABB area = new AABB(cx - r, cy - r, cz - r, cx + r, cy + r, cz + r);
                scanBoxes++;
                rings++;
                try {
                    for (Entity e : level.getEntities(player, area, candidate -> candidate != player)) {
                        double dx = e.getX() - cx;
                        double dy = e.getY() - cy;
                        double dz = e.getZ() - cz;
                        if (dx * dx + dy * dy + dz * dz <= (double) radius * radius) {
                            foundById.putIfAbsent(e.getId(), e);
                        }
                    }
                } catch (Throwable ignored) {
                    // 单个环失败不影响其它环；scanErrors 会告诉调用方结果可能不完整。
                    scanErrors++;
                }
                if (r >= radius) {
                    break;
                }
            }
        }
        List<Entity> found = new ArrayList<>(foundById.values());
        found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(player)));
        JsonArray out = new JsonArray();
        for (Entity e : found) {
            if (out.size() >= limit) {
                break;
            }
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            if (typeFilter != null && !typeFilter.isBlank() && !type.toLowerCase(Locale.ROOT).contains(typeFilter.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (nameFilter != null && !nameFilter.isBlank()) {
                String name = e.getName().getString();
                if (!name.toLowerCase(Locale.ROOT).contains(nameFilter.toLowerCase(Locale.ROOT))) {
                    continue;
                }
            }
            out.add(entity(e, player));
        }
        JsonObject o = Json.ok();
        o.addProperty("radius", radius);
        o.addProperty("requestedRadius", requested);
        o.addProperty("scanMode", ringMode ? "rings" : "single");
        o.addProperty("rings", rings);
        o.addProperty("scanBoxes", scanBoxes);
        o.addProperty("candidates", found.size());
        o.add("entities", out);
        o.addProperty("returned", out.size());
        o.addProperty("truncated", found.size() > out.size());
        if (scanErrors > 0) {
            o.addProperty("scanErrors", scanErrors);
        }
        if (out.isEmpty()) {
            // 明确说明“可能只是没加载”，而不是让调用方误以为世界上没有实体。
            o.addProperty("note", "no matching entities within " + radius
                    + " blocks; distant chunks may be unloaded (scanMode=" + (ringMode ? "rings" : "single") + ")");
        }
        return cap(o);
    }

    public static JsonObject collisionQuery(JsonObject args) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            throw Errors.notLoaded("the player is not in a world");
        }
        List<int[]> positions = positions(args, mc.player, 64);
        JsonArray out = new JsonArray();
        for (int[] p : positions) {
            BlockPos pos = new BlockPos(p[0], p[1], p[2]);
            BlockState state = level.getBlockState(pos);
            JsonObject o = new JsonObject();
            o.add("pos", Json.intArray(p[0], p[1], p[2]));
            o.addProperty("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            o.add("collision", collision(level, pos, state));
            o.add("interaction", shape(state.getInteractionShape(level, pos)));
            out.add(o);
        }
        JsonObject o = Json.ok();
        o.add("positions", out);
        return cap(o);
    }

    private static JsonArray collision(ClientLevel level, BlockPos pos, BlockState state) {
        return shape(state.getCollisionShape(level, pos));
    }

    private static JsonArray shape(VoxelShape shape) {
        JsonArray a = new JsonArray();
        if (shape == null || shape.isEmpty()) {
            return a;
        }
        List<AABB> boxes = shape.toAabbs();
        for (int i = 0; i < Math.min(16, boxes.size()); i++) {
            AABB b = boxes.get(i);
            JsonArray box = new JsonArray();
            box.add(Json.round(b.minX, 3));
            box.add(Json.round(b.minY, 3));
            box.add(Json.round(b.minZ, 3));
            box.add(Json.round(b.maxX, 3));
            box.add(Json.round(b.maxY, 3));
            box.add(Json.round(b.maxZ, 3));
            a.add(box);
        }
        return a;
    }

    public static JsonObject blockInfo(ClientLevel level, BlockPos pos, boolean includeShapes) {
        BlockState state = level.getBlockState(pos);
        JsonObject o = new JsonObject();
        o.add("pos", Json.intArray(pos.getX(), pos.getY(), pos.getZ()));
        o.addProperty("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        o.addProperty("name", state.getBlock().getName().getString());
        o.addProperty("air", state.isAir());
        o.addProperty("replaceable", state.canBeReplaced());
        o.addProperty("hardness", state.getDestroySpeed(level, pos));
        o.addProperty("requiresTool", state.requiresCorrectToolForDrops());
        o.addProperty("loaded", level.hasChunkAt(pos));
        // Round-3: lighting and fluid details (spawn safety + water/lava planning).
        o.addProperty("blockLight", level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos));
        o.addProperty("skyLight", level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos));
        net.minecraft.world.level.material.FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            o.addProperty("fluid", BuiltInRegistries.FLUID.getKey(fluidState.getType()).toString());
            o.addProperty("fluidSource", fluidState.isSource());
            o.addProperty("fluidAmount", fluidState.getAmount());
            o.addProperty("fluidFlowing", !fluidState.isSource());
        }
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)) {
            o.addProperty("waterlogged", state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED));
        }
        JsonObject props = properties(state);
        if (!props.isEmpty()) {
            o.add("properties", props);
        }
        if (includeShapes) {
            o.add("collision", collision(level, pos, state));
            o.add("interaction", shape(state.getInteractionShape(level, pos)));
        }
        return o;
    }

    private static JsonObject properties(BlockState state) {
        JsonObject props = new JsonObject();
        try {
            for (Property<?> property : state.getProperties()) {
                if (props.size() >= 24) {
                    break;
                }
                Object value = state.getValue(property);
                props.addProperty(property.getName(), String.valueOf(value));
            }
        } catch (Throwable ignored) {
        }
        return props;
    }

    private static JsonObject entity(Entity e, LocalPlayer player) {
        JsonObject o = new JsonObject();
        o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
        o.addProperty("id", e.getId());
        o.addProperty("uuid", e.getUUID().toString());
        o.addProperty("name", e.getName().getString());
        o.addProperty("x", Json.round(e.getX(), 2));
        o.addProperty("y", Json.round(e.getY(), 2));
        o.addProperty("z", Json.round(e.getZ(), 2));
        BlockPos bp = e.blockPosition();
        o.addProperty("blockX", bp.getX());
        o.addProperty("blockY", bp.getY());
        o.addProperty("blockZ", bp.getZ());
        o.addProperty("distance", Json.round(Math.sqrt(e.distanceToSqr(player)), 2));
        o.addProperty("alive", e.isAlive());
        AABB b = e.getBoundingBox();
        o.add("bbox", Json.doubleArray(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ));
        if (e instanceof LivingEntity living) {
            o.addProperty("health", Json.round(living.getHealth(), 2));
            o.addProperty("maxHealth", Json.round(living.getMaxHealth(), 2));
        }
        if (e instanceof ItemEntity item) {
            o.add("item", GameState.stack(item.getItem()));
        }
        if (e instanceof Player p) {
            o.addProperty("player", true);
            o.addProperty("gameMode", p.isCreative() ? "creative" : p.isSpectator() ? "spectator" : "survival");
        }
        return o;
    }

    private static boolean matches(BlockState state, String id, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        for (String raw : filter.split("[,;\\s]+")) {
            String f = raw.trim().toLowerCase(Locale.ROOT);
            if (f.isEmpty()) {
                continue;
            }
            if (f.startsWith("#")) {
                try {
                    ResourceLocation rl = ResourceLocation.tryParse(f.substring(1));
                    if (rl != null && state.is(TagKey.create(Registries.BLOCK, rl))) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
                continue;
            }
            if (id.toLowerCase(Locale.ROOT).contains(f)) {
                return true;
            }
            try {
                if (state.getBlock().getName().getString().toLowerCase(Locale.ROOT).contains(f)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static JsonArray counts(Map<String, Integer> counts, int limit) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        JsonArray a = new JsonArray();
        for (int i = 0; i < Math.min(limit, list.size()); i++) {
            JsonObject o = new JsonObject();
            o.addProperty("id", list.get(i).getKey());
            o.addProperty("count", list.get(i).getValue());
            a.add(o);
        }
        return a;
    }

    private static int[] center(JsonObject args, LocalPlayer player) {
        JsonArray c = Json.arr(args, "center");
        if (c != null && c.size() >= 3) {
            return new int[]{c.get(0).getAsInt(), c.get(1).getAsInt(), c.get(2).getAsInt()};
        }
        Integer x = Json.iOrNull(args, "x");
        Integer y = Json.iOrNull(args, "y");
        Integer z = Json.iOrNull(args, "z");
        if (x != null && y != null && z != null) {
            return new int[]{x, y, z};
        }
        BlockPos bp = player.blockPosition();
        return new int[]{bp.getX(), bp.getY(), bp.getZ()};
    }

    private static List<int[]> positions(JsonObject args, LocalPlayer player, int limit) {
        List<int[]> out = new ArrayList<>();
        JsonArray list = Json.arr(args, "positions");
        if (list != null) {
            for (int i = 0; i < Math.min(limit, list.size()); i++) {
                var e = list.get(i);
                if (e.isJsonArray() && e.getAsJsonArray().size() >= 3) {
                    JsonArray a = e.getAsJsonArray();
                    out.add(new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        Integer x = Json.iOrNull(args, "x");
        Integer y = Json.iOrNull(args, "y");
        Integer z = Json.iOrNull(args, "z");
        if (x != null && y != null && z != null) {
            out.add(new int[]{x, y, z});
        } else if (player != null) {
            BlockPos bp = player.blockPosition();
            out.add(new int[]{bp.getX(), bp.getY(), bp.getZ()});
        }
        return out;
    }

    /** Enforces a hard UTF-8 cap by dropping trailing blocks/entities. */
    private static JsonObject cap(JsonObject out) {
        String s = Json.GSON.toJson(out);
        int bytes = Json.utf8Bytes(s);
        if (bytes <= BridgeConfig.MAX_RESPONSE_BYTES - 1000) {
            out.addProperty("truncated", Json.b(out, "truncated", false));
            out.addProperty("bytes", bytes);
            return out;
        }
        JsonArray blocks = Json.arr(out, "blocks");
        JsonArray entities = Json.arr(out, "entities");
        boolean dropped = false;
        while (bytes > BridgeConfig.MAX_RESPONSE_BYTES - 1000 && blocks != null && blocks.size() > 0) {
            blocks.remove(blocks.size() - 1);
            dropped = true;
            s = Json.GSON.toJson(out);
            bytes = Json.utf8Bytes(s);
        }
        while (bytes > BridgeConfig.MAX_RESPONSE_BYTES - 1000 && entities != null && entities.size() > 0) {
            entities.remove(entities.size() - 1);
            dropped = true;
            s = Json.GSON.toJson(out);
            bytes = Json.utf8Bytes(s);
        }
        out.addProperty("truncated", true);
        if (dropped) {
            out.addProperty("truncatedByByteCap", true);
        }
        if (blocks != null) {
            out.addProperty("returnedBlocks", blocks.size());
        }
        if (entities != null) {
            out.addProperty("returnedEntities", entities.size());
        }
        out.addProperty("bytes", Json.utf8Bytes(Json.GSON.toJson(out)));
        return out;
    }
}
