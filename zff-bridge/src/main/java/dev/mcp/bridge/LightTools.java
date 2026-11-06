package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Lighting tools: a light map of the walkable area (hostile mobs need block light 0 in 1.18+) and a
 * torch placer that picks a support block and places the torch from the hotbar.
 */
public final class LightTools {
    private LightTools() {
    }

    public static JsonObject lightMap(JsonObject args) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            throw Errors.notLoaded("the player is not in a world");
        }
        BlockPos center = args.has("center") ? pos(args.getAsJsonArray("center")) : player.blockPosition();
        int radius = MathUtil.clamp(Json.i(args, "radius", 8), 1, 32);
        int y = Json.i(args, "y", center.getY());
        JsonArray dark = new JsonArray();
        int samples = 0;
        int minLight = 15;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos floor = new BlockPos(center.getX() + dx, y - 1, center.getZ() + dz);
                BlockPos cell = floor.above();
                if (!DigUtil.solid(level, floor) || !level.getBlockState(cell).isAir()) {
                    continue;
                }
                samples++;
                int block = level.getBrightness(LightLayer.BLOCK, cell);
                int sky = level.getBrightness(LightLayer.SKY, cell);
                minLight = Math.min(minLight, block);
                if (block == 0 && dark.size() < 80) {
                    JsonObject o = new JsonObject();
                    o.add("pos", Json.intArray(cell.getX(), cell.getY(), cell.getZ()));
                    o.addProperty("blockLight", block);
                    o.addProperty("skyLight", sky);
                    dark.add(o);
                }
            }
        }
        JsonObject o = Json.ok();
        o.add("center", Json.intArray(center.getX(), y, center.getZ()));
        o.addProperty("radius", radius);
        o.addProperty("samples", samples);
        o.addProperty("darkCells", dark.size());
        o.addProperty("minBlockLight", samples == 0 ? 15 : minLight);
        o.add("dark", dark);
        o.addProperty("safe", dark.isEmpty() && samples > 0);
        return o;
    }

    /** Place a torch at (x,y,z); picks a solid neighbour as support and clicks its face. */
    public static JsonObject torchAt(JsonObject args) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            throw Errors.notLoaded("the player is not in a world");
        }
        int x = Json.i(args, "x", Integer.MIN_VALUE);
        int y = Json.i(args, "y", Integer.MIN_VALUE);
        int z = Json.i(args, "z", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
            throw new Errors.BridgeException(Errors.INTERNAL, "x, y and z are required");
        }
        BlockPos target = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(target);
        if (!state.isAir()) {
            JsonObject o = Json.ok();
            o.addProperty("placed", false);
            o.addProperty("reason", "target is not air");
            return o;
        }
        int slot = Actions.findItemSlot(player, "minecraft:torch");
        if (slot < 0) {
            // The torches may simply be in the bag: move a stack into the hotbar (server-synced SWAP).
            for (int i = 9; i < 36; i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (!id.equals("minecraft:torch")) {
                    continue;
                }
                int hb = 0;
                for (int h = 8; h >= 0; h--) {
                    if (player.getInventory().getItem(h).isEmpty()) {
                        hb = h;
                        break;
                    }
                }
                mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, i, hb,
                        net.minecraft.world.inventory.ClickType.SWAP, player);
                Actions.selectSlot(player, hb);
                slot = hb;
                break;
            }
        }
        if (slot < 0) {
            throw new Errors.BridgeException(Errors.TOOL_BROKEN, "no torch in the inventory");
        }
        Actions.selectSlot(player, slot);
        List<Direction> order = new ArrayList<>(List.of(Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP));
        for (Direction supportDir : order) {
            BlockPos support = target.relative(supportDir);
            BlockState supportState = level.getBlockState(support);
            if (supportState.isAir() || supportState.getCollisionShape(level, support).isEmpty()) {
                continue;
            }
            Direction hitFace = supportDir.getOpposite();
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support), hitFace, support, false);
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND);
            JsonObject o = Json.ok();
            o.addProperty("placed", true);
            o.add("pos", Json.intArray(x, y, z));
            o.add("support", Json.intArray(support.getX(), support.getY(), support.getZ()));
            o.addProperty("face", hitFace.getName());
            return o;
        }
        JsonObject o = Json.ok();
        o.addProperty("placed", false);
        o.addProperty("reason", "no solid neighbour to attach the torch");
        return o;
    }

    private static BlockPos pos(JsonArray a) {
        if (a == null || a.size() < 3) {
            throw new Errors.BridgeException(Errors.INTERNAL, "center must be [x,y,z]");
        }
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }
}
