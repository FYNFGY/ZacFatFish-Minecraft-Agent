package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs a chain of mine/place/use operations without any per-operation HTTP round trip. Verification
 * happens once, when the whole batch is finished (all targets are read back in one pass).
 */
public final class BatchJob implements Jobs.TickTask {
    private record Op(String type, BlockPos pos, String face, String item) {
    }

    private final List<Op> ops;
    private int index;
    private int ticks;
    private boolean started;
    private int placed;
    private int mined;
    private int used;
    private int failed;
    private final Map<Integer, BlockMiner> miners = new HashMap<>();
    private final List<String> errors = new ArrayList<>();

    public BatchJob(JsonArray array) {
        List<Op> list = new ArrayList<>();
        int limit = Math.min(2048, array.size());
        for (int i = 0; i < limit; i++) {
            JsonObject o = array.get(i).getAsJsonObject();
            String type = Json.str(o, "op", "place").toLowerCase(Locale.ROOT);
            int x = Json.i(o, "x", Integer.MIN_VALUE);
            int y = Json.i(o, "y", Integer.MIN_VALUE);
            int z = Json.i(o, "z", Integer.MIN_VALUE);
            if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
                throw new Errors.BridgeException(Errors.INTERNAL, "op " + i + " needs x, y and z");
            }
            list.add(new Op(type, new BlockPos(x, y, z), Json.str(o, "face", null), Json.str(o, "item", null)));
        }
        this.ops = list;
    }

    @Override
    public void start(Jobs.Job job) {
        job.metric("total", ops.size());
        job.metric("placed", 0);
        job.metric("mined", 0);
        job.metric("used", 0);
        job.metric("failed", 0);
        // A batch is a movement-sensitive sequence: stop any path job that would walk the player away.
        Jobs manager = BridgeRuntime.jobs;
        if (manager != null) {
            manager.cancelTool("path_to");
        }
        BaritoneControl.cancel();
    }

    @Override
    public void tick(Jobs.Job job) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            job.fail(Errors.DISCONNECTED, "the player left the world");
            return;
        }
        if (index >= ops.size()) {
            finish(job, level);
            return;
        }
        Op op = ops.get(index);
        switch (op.type) {
            case "place" -> tickPlace(level, player, op);
            case "mine" -> tickMine(level, player, op);
            case "use" -> {
                doUse(level, player, op);
                used++;
                index++;
            }
            default -> {
                failed++;
                errors.add("unknown op: " + op.type);
                index++;
            }
        }
        job.metric("placed", placed);
        job.metric("mined", mined);
        job.metric("used", used);
        job.metric("failed", failed);
        job.progress(Math.min(99, (int) Math.round(100.0 * index / Math.max(1, ops.size()))));
    }

    private void tickPlace(ClientLevel level, LocalPlayer player, Op op) {
        if (!started) {
            BlockState state = level.getBlockState(op.pos);
            if (!(state.isAir() || state.canBeReplaced())) {
                failed++;
                errors.add("occupied " + shortPos(op.pos));
                index++;
                return;
            }
            Direction face = resolveFace(level, player, op);
            if (face == null) {
                failed++;
                errors.add("no support " + shortPos(op.pos));
                index++;
                return;
            }
            int slot = Actions.findItemSlot(player, op.item);
            if (slot < 0) {
                failed++;
                errors.add("no matching item in hotbar for " + shortPos(op.pos));
                index++;
                return;
            }
            Actions.selectSlot(player, slot);
            BlockPos support = op.pos.relative(face.getOpposite());
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support), face, support, false);
            Minecraft.getInstance().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND);
            started = true;
            ticks = 0;
            return;
        }
        ticks++;
        if (ticks >= 2) {
            placed++;
            index++;
            started = false;
            ticks = 0;
        }
    }

    private void tickMine(ClientLevel level, LocalPlayer player, Op op) {
        BlockMiner miner = miners.computeIfAbsent(index, k -> new BlockMiner(ops.get(k).pos));
        BlockMiner.Outcome outcome = miner.tick(player, level);
        switch (outcome) {
            case DONE, ALREADY_DONE -> {
                mined++;
                index++;
            }
            case FAILED -> {
                failed++;
                errors.add((miner.code() == null ? Errors.NO_PROGRESS : miner.code()) + " " + shortPos(op.pos));
                index++;
            }
            case WORKING -> {
            }
        }
    }

    private void doUse(ClientLevel level, LocalPlayer player, Op op) {
        Direction face = Actions.parseDirection(null, player, op.pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(op.pos), face, op.pos, false);
        Minecraft.getInstance().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND);
    }

    private Direction resolveFace(ClientLevel level, LocalPlayer player, Op op) {
        if (op.face != null && !op.face.isBlank()) {
            try {
                Direction face = Direction.valueOf(op.face.trim().toUpperCase(Locale.ROOT));
                BlockPos support = op.pos.relative(face.getOpposite());
                BlockState state = level.getBlockState(support);
                if (!state.isAir() && !state.getCollisionShape(level, support).isEmpty()) {
                    return face;
                }
            } catch (IllegalArgumentException ignored) {
                // fall through to auto selection
            }
        }
        for (Direction candidate : Direction.values()) {
            BlockPos support = op.pos.relative(candidate);
            BlockState state = level.getBlockState(support);
            if (!state.isAir() && !state.getCollisionShape(level, support).isEmpty()) {
                return candidate.getOpposite();
            }
        }
        return null;
    }

    private void finish(Jobs.Job job, ClientLevel level) {
        int ok = 0;
        JsonArray unverified = new JsonArray();
        for (Op op : ops) {
            boolean good;
            switch (op.type) {
                case "place" -> {
                    BlockState state = level.getBlockState(op.pos);
                    good = !state.isAir() && !state.canBeReplaced();
                }
                case "mine" -> good = level.getBlockState(op.pos).isAir();
                default -> good = true;
            }
            if (good) {
                ok++;
            } else {
                unverified.add(op.type + " " + shortPos(op.pos));
            }
        }
        JsonObject r = new JsonObject();
        r.addProperty("total", ops.size());
        r.addProperty("verifiedOk", ok);
        r.addProperty("placed", placed);
        r.addProperty("mined", mined);
        r.addProperty("used", used);
        r.addProperty("failed", failed);
        r.add("unverified", unverified);
        JsonArray err = new JsonArray();
        errors.forEach(err::add);
        r.add("errors", err);
        job.succeed(r);
    }

    private static String shortPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
