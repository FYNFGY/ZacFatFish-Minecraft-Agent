package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Surface-only perception: flood fill the open space reachable from the player's eye and return only
 * the blocks that touch that space. Optional los/fov modes filter the surface by line of sight or by
 * the current camera view cone. See docs/Ph2/scan_surface.md for the contract.
 */
public final class ScanSurface {
    private static final int UP = 1;
    private static final int DOWN = 2;
    private static final int NORTH = 4;
    private static final int SOUTH = 8;
    private static final int WEST = 16;
    private static final int EAST = 32;
    private static final Direction[] DIRS = {Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
    private static final int[] BITS = {UP, DOWN, NORTH, SOUTH, WEST, EAST};

    private ScanSurface() {
    }

    public static JsonObject scan(JsonObject args) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            throw Errors.notLoaded("the player is not in a world");
        }
        int cx;
        int cy;
        int cz;
        JsonArray center = Json.arr(args, "center");
        if (center != null && center.size() >= 3) {
            cx = center.get(0).getAsInt();
            cy = center.get(1).getAsInt();
            cz = center.get(2).getAsInt();
        } else {
            BlockPos base = player.blockPosition();
            cx = base.getX();
            cy = base.getY();
            cz = base.getZ();
        }
        int radius = MathUtil.clamp(Json.i(args, "radius", 16), 1, 64);
        String mode = Json.str(args, "mode", "connected").toLowerCase(Locale.ROOT);
        if (!mode.equals("connected") && !mode.equals("los") && !mode.equals("fov")) {
            throw new Errors.BridgeException(Errors.INTERNAL, "mode must be connected, los or fov");
        }
        int heightRadius = MathUtil.clamp(Json.i(args, "heightRadius", Math.min(16, radius)), 1, Math.min(32, radius));
        int maxSurface = MathUtil.clamp(Json.i(args, "maxSurface", 4000), 32, 8000);
        int maxCells = MathUtil.clamp(Json.i(args, "maxVisitedCells", 250_000), 1000, 2_000_000);
        int budgetMs = MathUtil.clamp(Json.i(args, "timeBudgetMs", 1500), 50, 5000);
        int maxRaySteps = MathUtil.clamp(Json.i(args, "maxRaySteps", 64), 8, 256);
        float fovDegrees = (float) MathUtil.clamp(Json.i(args, "fovDegrees", 70), 10, 180);
        boolean includeEntities = Json.b(args, "includeEntities", true);
        // ISSUE-8: 门/活板门/栅栏门/床/告示牌不是整格方块，过去既不进 surface 也不进 doors。
        boolean includeNonFull = Json.b(args, "includeNonFull", true);

        long deadline = System.nanoTime() + budgetMs * 1_000_000L;
        Vec3 eye = player.getEyePosition();
        BlockPos eyePos = BlockPos.containing(eye);

        HashSet<Long> visited = new HashSet<>();
        HashSet<Long> reachedColumns = new HashSet<>();
        HashMap<Long, Integer> surface = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        int openCells = 0;
        int unloaded = 0;
        boolean truncated = false;
        String truncatedReason = null;
        long radiusSq = (long) (radius + 1) * (radius + 1);

        if (level.hasChunkAt(eyePos) && isOpen(level, eyePos)) {
            visited.add(eyePos.asLong());
            queue.add(eyePos);
        } else {
            // The eye may be inside a block (rare); start from the block above.
            BlockPos start = eyePos.above();
            if (level.hasChunkAt(start) && isOpen(level, start)) {
                visited.add(start.asLong());
                queue.add(start);
            }
        }

        while (!queue.isEmpty()) {
            if ((openCells & 127) == 0 && System.nanoTime() > deadline) {
                truncated = true;
                truncatedReason = "timeBudget";
                break;
            }
            BlockPos pos = queue.poll();
            if (!level.hasChunkAt(pos)) {
                unloaded++;
                continue;
            }
            if (!isOpen(level, pos)) {
                continue;
            }
            openCells++;
            reachedColumns.add(columnKey(pos.getX(), pos.getZ()));
            for (int i = 0; i < DIRS.length; i++) {
                Direction dir = DIRS[i];
                BlockPos next = pos.relative(dir);
                long key = next.asLong();
                if (!inRadius(next, cx, cy, cz, radius, radiusSq)) {
                    continue;
                }
                if (visited.contains(key)) {
                    continue;
                }
                visited.add(key);
                if (visited.size() > maxCells) {
                    truncated = true;
                    truncatedReason = "maxVisitedCells";
                    queue.clear();
                    break;
                }
                if (!level.hasChunkAt(next)) {
                    unloaded++;
                    continue;
                }
                if (isOpen(level, next)) {
                    queue.add(next);
                } else {
                    int face = BITS[opposite(i)];
                    surface.merge(key, face, (a, b) -> a | b);
                }
            }
            if (truncated && queue.isEmpty()) {
                break;
            }
        }

        List<SurfaceEntry> entries = new ArrayList<>(surface.size());
        for (Map.Entry<Long, Integer> e : surface.entrySet()) {
            BlockPos pos = BlockPos.of(e.getKey());
            entries.add(new SurfaceEntry(pos, e.getValue(), blockId(level, pos), distance(eye, pos)));
        }
        entries.sort(Comparator.comparingDouble(s -> s.distance));

        boolean strict = !mode.equals("connected");
        List<SurfaceEntry> visible = new ArrayList<>();
        int occluded = 0;
        if (strict) {
            int tested = Math.min(entries.size(), maxSurface);
            Vec3 view = player.getViewVector(1.0F).normalize();
            double fovCos = Math.cos(Math.toRadians(fovDegrees) / 2.0);
            for (int i = 0; i < tested; i++) {
                SurfaceEntry entry = entries.get(i);
                if (isVisible(level, eye, entry, maxRaySteps, mode, view, fovCos)) {
                    visible.add(entry);
                } else {
                    occluded++;
                }
            }
            if (tested < entries.size()) {
                truncated = true;
                truncatedReason = truncatedReason == null ? "maxSurface" : truncatedReason;
            }
        }

        List<SurfaceEntry> emitted = strict ? visible : entries;
        if (emitted.size() > maxSurface) {
            emitted = new ArrayList<>(emitted.subList(0, maxSurface));
            truncated = true;
            truncatedReason = truncatedReason == null ? "maxSurface" : truncatedReason;
        }

        // ISSUE-8: 非整格可交互方块（door/trapdoor/fence_gate/bed/sign）。
        // 它们被 isOpen() 当成“可通行”，所以不会出现在 surface；这里从 flood fill 访问过的格子
        // 及其 6 个方向补扫，放进 surface 时追加 shape="nonfull" 标记，并汇总到 doors 字段。
        List<SurfaceEntry> nonFullEntries = new ArrayList<>();
        boolean nonFullTruncated = false;
        if (includeNonFull) {
            LinkedHashMap<Long, SurfaceEntry> nonFull = new LinkedHashMap<>();
            int scanned = 0;
            for (long key : visited) {
                BlockPos pos = BlockPos.of(key);
                addNonFull(level, pos, nonFull, eye);
                for (Direction dir : DIRS) {
                    if (++scanned > 250_000) {
                        nonFullTruncated = true;
                        break;
                    }
                    BlockPos next = pos.relative(dir);
                    if (!inRadius(next, cx, cy, cz, radius, radiusSq)) {
                        continue;
                    }
                    addNonFull(level, next, nonFull, eye);
                }
                if (nonFullTruncated) {
                    break;
                }
            }
            nonFullEntries.addAll(nonFull.values());
            nonFullEntries.sort(Comparator.comparingDouble(s -> s.distance));
        }

        JsonArray surfaceJson = new JsonArray();
        for (SurfaceEntry s : emitted) {
            JsonArray a = new JsonArray();
            a.add(s.pos.getX());
            a.add(s.pos.getY());
            a.add(s.pos.getZ());
            a.add(s.id);
            a.add(s.faces);
            surfaceJson.add(a);
        }
        // 非整格方块追加在 surface 末尾；第 6 个元素是 shape 标记（老调用方读前 5 个不受影响）。
        for (SurfaceEntry s : nonFullEntries) {
            JsonArray a = new JsonArray();
            a.add(s.pos.getX());
            a.add(s.pos.getY());
            a.add(s.pos.getZ());
            a.add(s.id);
            a.add(s.faces);
            a.add("nonfull");
            surfaceJson.add(a);
        }

        JsonArray heightMap = new JsonArray();
        JsonArray pits = new JsonArray();
        HashMap<Long, Integer> topByColumn = new HashMap<>();
        HashMap<Long, String> idByColumn = new HashMap<>();
        int eyeY = (int) Math.floor(eye.y);
        for (SurfaceEntry s : entries) {
            if ((s.faces & UP) == 0 || s.pos.getY() > eyeY) {
                continue;
            }
            long col = columnKey(s.pos.getX(), s.pos.getZ());
            Integer prev = topByColumn.get(col);
            if (prev == null || s.pos.getY() > prev) {
                topByColumn.put(col, s.pos.getY());
                idByColumn.put(col, s.id);
            }
        }
        for (int dx = -heightRadius; dx <= heightRadius; dx++) {
            for (int dz = -heightRadius; dz <= heightRadius; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                long col = columnKey(x, z);
                if (!reachedColumns.contains(col)) {
                    continue;
                }
                Integer y = topByColumn.get(col);
                if (y == null) {
                    JsonArray p = new JsonArray();
                    p.add(x);
                    p.add(z);
                    p.add((String) null);
                    p.add(Math.max(1, eyeY - cy));
                    p.add("minecraft:air");
                    pits.add(p);
                    continue;
                }
                JsonArray h = new JsonArray();
                h.add(x);
                h.add(z);
                h.add(y);
                h.add(idByColumn.get(col));
                heightMap.add(h);
                int neighbourMin = Integer.MAX_VALUE;
                int neighbourCount = 0;
                for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
                    Integer ny = topByColumn.get(columnKey(x + d.getStepX(), z + d.getStepZ()));
                    if (ny != null) {
                        neighbourMin = Math.min(neighbourMin, ny);
                        neighbourCount++;
                    }
                }
                if (neighbourCount > 0 && y <= neighbourMin - 2) {
                    JsonArray p = new JsonArray();
                    p.add(x);
                    p.add(z);
                    p.add(y);
                    p.add(neighbourMin - y);
                    p.add(idByColumn.get(col));
                    pits.add(p);
                }
            }
        }

        JsonArray doors = new JsonArray();
        JsonArray containers = new JsonArray();
        java.util.LinkedHashSet<Long> doorSeen = new java.util.LinkedHashSet<>();
        for (SurfaceEntry s : entries) {
            BlockState state = level.getBlockState(s.pos);
            addDoorJson(level, s, state, doors, doorSeen);
            if (isContainer(level, s.pos, state)) {
                JsonArray c = new JsonArray();
                c.add(s.pos.getX());
                c.add(s.pos.getY());
                c.add(s.pos.getZ());
                c.add(s.id);
                containers.add(c);
            }
        }
        for (SurfaceEntry s : nonFullEntries) {
            addDoorJson(level, s, level.getBlockState(s.pos), doors, doorSeen);
        }

        JsonObject o = Json.ok();
        o.addProperty("mode", mode);
        o.add("center", Json.intArray(cx, cy, cz));
        o.add("eye", Json.doubleArray(eye.x, eye.y, eye.z));
        o.addProperty("radius", radius);
        o.add("surface", surfaceJson);
        o.addProperty("surfaceFormat", includeNonFull
                ? "[x,y,z,id,faces,shape?] (shape=\"nonfull\" for door/trapdoor/fence_gate/bed/sign)"
                : "[x,y,z,id,faces]");
        o.add("heightMap", heightMap);
        o.add("pits", pits);
        o.add("doors", doors);
        o.addProperty("doorsFormat", "{pos,id,type,open?,facing?,part?,occupied?}");
        o.add("containers", containers);
        o.addProperty("includeNonFull", includeNonFull);
        o.addProperty("nonFullCount", nonFullEntries.size());
        o.addProperty("nonFullTruncated", nonFullTruncated);
        o.addProperty("openCells", openCells);
        o.addProperty("surfaceCount", entries.size() + nonFullEntries.size());
        o.addProperty("regularSurfaceCount", entries.size());
        o.addProperty("visibleSurfaceCount", strict ? visible.size() : entries.size());
        o.addProperty("occludedSurfaceCount", occluded);
        o.addProperty("unloaded", unloaded);
        o.addProperty("truncated", truncated);
        if (truncatedReason == null) {
            o.add("truncatedReason", null);
        } else {
            o.addProperty("truncatedReason", truncatedReason);
        }
        if (includeEntities) {
            JsonArray entities = new JsonArray();
            List<net.minecraft.world.entity.Entity> found = level.getEntities(player,
                    player.getBoundingBox().inflate(Math.min(32, radius)), e -> e != player);
            found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(player)));
            for (int i = 0; i < Math.min(20, found.size()); i++) {
                net.minecraft.world.entity.Entity e = found.get(i);
                JsonArray a = new JsonArray();
                a.add(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
                a.add(Math.round(e.getX() * 100.0) / 100.0);
                a.add(Math.round(e.getY() * 100.0) / 100.0);
                a.add(Math.round(e.getZ() * 100.0) / 100.0);
                a.add(Math.round(e.distanceTo(player) * 100.0) / 100.0);
                entities.add(a);
            }
            o.add("entities", entities);
        }
        String json = Json.GSON.toJson(o);
        int guard = 0;
        // Keep a useful minimum of every array: trimming the surface to zero (the old behaviour)
        // made scans useless even though the response was small enough.
        while (json.length() > 24000 && guard++ < 60) {
            boolean changed = false;
            if (surfaceJson.size() > 200) {
                trimTo(surfaceJson, Math.max(200, surfaceJson.size() * 4 / 5));
                changed = true;
            } else if (heightMap.size() > 100) {
                trimTo(heightMap, Math.max(100, heightMap.size() * 4 / 5));
                changed = true;
            } else if (pits.size() > 40) {
                trimTo(pits, Math.max(40, pits.size() * 4 / 5));
                changed = true;
            }
            if (!changed) {
                break;
            }
            o.addProperty("truncated", true);
            o.addProperty("truncatedReason", "byteCap");
            json = Json.GSON.toJson(o);
        }
        o.addProperty("bytes", json.length());
        // 2026-11-02: ore discovery piggybacks on the existing scan_surface call.
        // The return shape is unchanged; the mod pushes a separate `ore` event and
        // the MCP EmergencyState renders it in infoTxt.
        reportOres(level, player);
        return o;
    }

    /** Side-channel: all ores in the 16x16x16 cube centred on the player (only when y<=40). */
    private static void reportOres(ClientLevel level, LocalPlayer player) {
        try {
            BlockPos base = player.blockPosition();
            if (base.getY() > 40) return;
            JsonArray ores = new JsonArray();
            for (int dx = -8; dx <= 8; dx++) {
                for (int dy = -8; dy <= 8; dy++) {
                    for (int dz = -8; dz <= 8; dz++) {
                        BlockPos p = base.offset(dx, dy, dz);
                        if (!level.hasChunkAt(p)) continue;
                        BlockState state = level.getBlockState(p);
                        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        if (!isOre(id)) continue;
                        JsonObject ore = new JsonObject();
                        ore.addProperty("id", id);
                        ore.addProperty("x", dx);
                        ore.addProperty("y", dy);
                        ore.addProperty("z", dz);
                        ores.add(ore);
                    }
                }
            }
            if (!ores.isEmpty()) EventPush.pushOre(ores);
        } catch (Throwable ignored) {
            // A scan must never fail because the ore side-channel failed.
        }
    }

    private static boolean isOre(String id) {
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return path.endsWith("_ore") || path.equals("ancient_debris");
    }

    private static void trimTo(JsonArray array, int keep) {
        while (array.size() > keep) {
            array.remove(array.size() - 1);
        }
    }

    private static boolean inRadius(BlockPos p, int cx, int cy, int cz, int radius, long radiusSq) {
        double dx = p.getX() - cx;
        double dy = p.getY() - cy;
        double dz = p.getZ() - cz;
        return Math.abs(dx) <= radius && Math.abs(dy) <= radius && Math.abs(dz) <= radius
                && dx * dx + dy * dy + dz * dz <= radiusSq;
    }

    private static int opposite(int index) {
        return switch (index) {
            case 0 -> 1;
            case 1 -> 0;
            case 2 -> 3;
            case 3 -> 2;
            case 4 -> 5;
            default -> 4;
        };
    }

    static boolean isOpen(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return true;
        }
        Block block = state.getBlock();
        if (block instanceof DoorBlock) {
            // P1-1: a door is traversable whether it is open or closed - it must not cut the region.
            return true;
        }
        if (block instanceof TrapDoorBlock) {
            return true;
        }
        if (block instanceof FenceGateBlock) {
            return true;
        }
        if (isContainer(level, pos, state)) {
            return false;
        }
        if (block instanceof FenceBlock || block instanceof WallBlock || block instanceof IronBarsBlock) {
            return false;
        }
        if (!state.getCollisionShape(level, pos).isEmpty()) {
            // Opaque full blocks. Glass and other no-occlusion blocks stay sight-open.
            return !state.canOcclude();
        }
        return true;
    }

    private static boolean isContainer(ClientLevel level, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock) {
            return true;
        }
        try {
            return level.getBlockEntity(pos) instanceof Container;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** ISSUE-8: 非整格可交互方块分类（door/trapdoor/fence_gate/bed/sign），null 表示不是。 */
    private static String nonFullType(String id) {
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        if (path.endsWith("_trapdoor")) {
            return "trapdoor";
        }
        if (path.endsWith("_door")) {
            return "door";
        }
        if (path.endsWith("_fence_gate")) {
            return "fence_gate";
        }
        if (path.endsWith("_bed")) {
            return "bed";
        }
        if (path.endsWith("sign")) {
            return "sign";
        }
        return null;
    }

    /** 把一个位置的非整格方块加进本次扫描的补集（按 BlockPos 去重）。 */
    private static void addNonFull(ClientLevel level, BlockPos pos, Map<Long, SurfaceEntry> out, Vec3 eye) {
        long key = pos.asLong();
        if (out.containsKey(key) || !level.hasChunkAt(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (nonFullType(id) == null) {
            return;
        }
        int faces = 0;
        for (int i = 0; i < DIRS.length; i++) {
            if (isOpen(level, pos.relative(DIRS[i]))) {
                faces |= BITS[i];
            }
        }
        out.put(key, new SurfaceEntry(pos, faces, id, distance(eye, pos)));
    }

    /** doors 字段统一成对象：{pos,id,type,open?,facing?,part?,occupied?}（ISSUE-8）。 */
    private static void addDoorJson(ClientLevel level, SurfaceEntry entry, BlockState state,
                                    JsonArray out, java.util.Set<Long> seen) {
        String type = nonFullType(entry.id);
        if (type == null || !seen.add(entry.pos.asLong())) {
            return;
        }
        JsonObject o = new JsonObject();
        o.add("pos", Json.intArray(entry.pos.getX(), entry.pos.getY(), entry.pos.getZ()));
        o.addProperty("id", entry.id);
        o.addProperty("type", type);
        if (state.hasProperty(BlockStateProperties.OPEN)) {
            o.addProperty("open", state.getValue(BlockStateProperties.OPEN));
        }
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            o.addProperty("facing", state.getValue(BlockStateProperties.HORIZONTAL_FACING).getName());
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)) {
            o.addProperty("part", String.valueOf(state.getValue(BlockStateProperties.BED_PART)));
        }
        if (state.hasProperty(BlockStateProperties.OCCUPIED)) {
            o.addProperty("occupied", state.getValue(BlockStateProperties.OCCUPIED));
        }
        out.add(o);
    }

    private static boolean isVisible(ClientLevel level, Vec3 eye, SurfaceEntry entry, int maxRaySteps,
                                     String mode, Vec3 view, double fovCos) {
        int samples = 5;
        for (int i = 0; i < DIRS.length; i++) {
            if ((entry.faces & BITS[i]) == 0) {
                continue;
            }
            Direction dir = DIRS[i];
            Vec3[] points = faceSamples(entry.pos, dir, samples);
            for (Vec3 target : points) {
                Vec3 delta = target.subtract(eye);
                double distance = delta.length();
                if (distance < 0.05) {
                    return true;
                }
                Vec3 n = delta.scale(1.0 / distance);
                if (mode.equals("fov") && n.dot(view) < fovCos) {
                    continue;
                }
                if (clearPath(level, eye, n, distance, entry.pos, maxRaySteps)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Vec3[] faceSamples(BlockPos pos, Direction dir, int samples) {
        double x = pos.getX();
        double y = pos.getY();
        double z = pos.getZ();
        double off = 0.28;
        List<Vec3> list = new ArrayList<>(samples);
        switch (dir) {
            case UP -> {
                double fy = y + 1.0;
                list.add(new Vec3(x + 0.5, fy, z + 0.5));
                list.add(new Vec3(x + 0.5 - off, fy, z + 0.5 - off));
                list.add(new Vec3(x + 0.5 + off, fy, z + 0.5 - off));
                list.add(new Vec3(x + 0.5 - off, fy, z + 0.5 + off));
                list.add(new Vec3(x + 0.5 + off, fy, z + 0.5 + off));
            }
            case DOWN -> {
                double fy = y;
                list.add(new Vec3(x + 0.5, fy, z + 0.5));
                list.add(new Vec3(x + 0.5 - off, fy, z + 0.5 - off));
                list.add(new Vec3(x + 0.5 + off, fy, z + 0.5 - off));
                list.add(new Vec3(x + 0.5 - off, fy, z + 0.5 + off));
                list.add(new Vec3(x + 0.5 + off, fy, z + 0.5 + off));
            }
            case NORTH -> {
                double fz = z;
                list.add(new Vec3(x + 0.5, y + 0.5, fz));
                list.add(new Vec3(x + 0.5 - off, y + 0.5 - off, fz));
                list.add(new Vec3(x + 0.5 + off, y + 0.5 - off, fz));
                list.add(new Vec3(x + 0.5 - off, y + 0.5 + off, fz));
                list.add(new Vec3(x + 0.5 + off, y + 0.5 + off, fz));
            }
            case SOUTH -> {
                double fz = z + 1.0;
                list.add(new Vec3(x + 0.5, y + 0.5, fz));
                list.add(new Vec3(x + 0.5 - off, y + 0.5 - off, fz));
                list.add(new Vec3(x + 0.5 + off, y + 0.5 - off, fz));
                list.add(new Vec3(x + 0.5 - off, y + 0.5 + off, fz));
                list.add(new Vec3(x + 0.5 + off, y + 0.5 + off, fz));
            }
            case WEST -> {
                double fx = x;
                list.add(new Vec3(fx, y + 0.5, z + 0.5));
                list.add(new Vec3(fx, y + 0.5 - off, z + 0.5 - off));
                list.add(new Vec3(fx, y + 0.5 + off, z + 0.5 - off));
                list.add(new Vec3(fx, y + 0.5 - off, z + 0.5 + off));
                list.add(new Vec3(fx, y + 0.5 + off, z + 0.5 + off));
            }
            case EAST -> {
                double fx = x + 1.0;
                list.add(new Vec3(fx, y + 0.5, z + 0.5));
                list.add(new Vec3(fx, y + 0.5 - off, z + 0.5 - off));
                list.add(new Vec3(fx, y + 0.5 + off, z + 0.5 - off));
                list.add(new Vec3(fx, y + 0.5 - off, z + 0.5 + off));
                list.add(new Vec3(fx, y + 0.5 + off, z + 0.5 + off));
            }
            default -> {
            }
        }
        return list.toArray(new Vec3[0]);
    }

    private static boolean clearPath(ClientLevel level, Vec3 eye, Vec3 dir, double distance, BlockPos target, int maxSteps) {
        int steps = Math.min(maxSteps, Math.max(3, (int) Math.ceil(distance / 0.25)));
        double step = distance / steps;
        BlockPos last = null;
        for (int i = 1; i < steps; i++) {
            Vec3 p = eye.add(dir.scale(step * i));
            BlockPos pos = BlockPos.containing(p);
            if (pos.equals(target)) {
                return true;
            }
            if (pos.equals(last)) {
                continue;
            }
            last = pos;
            if (!level.hasChunkAt(pos)) {
                return false;
            }
            if (!isOpen(level, pos)) {
                return false;
            }
        }
        return true;
    }

    private static String blockId(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static double distance(Vec3 eye, BlockPos pos) {
        double dx = eye.x - (pos.getX() + 0.5);
        double dy = eye.y - (pos.getY() + 0.5);
        double dz = eye.z - (pos.getZ() + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private record SurfaceEntry(BlockPos pos, int faces, String id, double distance) {
    }
}
