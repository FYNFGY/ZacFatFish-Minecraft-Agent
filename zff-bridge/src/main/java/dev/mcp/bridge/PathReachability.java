package dev.mcp.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Local walkability search for combat targeting. A mob behind a wall is not a target even if it is
 * within the raw radius; only a path shorter than the limit counts as reachable.
 */
public final class PathReachability {
    private PathReachability() {
    }

    public static boolean reachable(LocalPlayer player, Entity target, int maxSteps) {
        if (player == null || target == null) return false;
        Level level = player.level();
        BlockPos start = player.blockPosition();
        BlockPos goal = target.blockPosition();
        if (start.distSqr(goal) > (maxSteps + 2L) * (maxSteps + 2L)) return false;
        if (start.equals(goal)) return true;
        ArrayDeque<Node> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        reached = false;
        queue.add(new Node(start, 0));
        seen.add(key(start));
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            if (n.steps >= maxSteps) continue;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos horizontal = n.pos.relative(dir);
                consider(level, horizontal, n.steps + 1, goal, queue, seen, maxSteps);
                // Step up one block (player jump).
                consider(level, horizontal.above(), n.steps + 2, goal, queue, seen, maxSteps);
                // Drop down up to 3 blocks.
                for (int drop = 1; drop <= 3; drop++) {
                    BlockPos down = horizontal.below(drop);
                    if (!floor(level, down)) continue;
                    consider(level, down, n.steps + 1 + drop / 2, goal, queue, seen, maxSteps);
                }
            }
        }
        return reached;
    }

    private static void consider(Level level, BlockPos pos, int steps, BlockPos goal,
                                 ArrayDeque<Node> queue, Set<Long> seen, int maxSteps) {
        if (steps > maxSteps || !standable(level, pos)) return;
        if (!seen.add(key(pos))) return;
        if (pos.equals(goal)) {
            queue.add(new Node(pos, steps));
            // Mark reached by adding the goal; caller checks queue node? We return via sentinel below.
            reached = true;
            return;
        }
        queue.add(new Node(pos, steps));
    }

    // The BFS uses a per-call flag instead of returning a path just to keep the hot path small.
    private static boolean reached;

    private static boolean standable(Level level, BlockPos pos) {
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        if (!passable(level, pos, feet) || !passable(level, pos.above(), head)) return false;
        return floor(level, pos);
    }

    private static boolean passable(Level level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof FenceGateBlock
                || state.getBlock() instanceof TrapDoorBlock) return true;
        return state.getCollisionShape(level, pos, CollisionContext.empty()).isEmpty();
    }

    private static boolean floor(Level level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return !below.getCollisionShape(level, pos.below(), CollisionContext.empty()).isEmpty();
    }

    private static long key(BlockPos pos) {
        return ((long) (pos.getX() & 0x3FFFFFF) << 38)
                | ((long) (pos.getZ() & 0x3FFFFFF) << 12)
                | (pos.getY() & 0xFFF);
    }

    private record Node(BlockPos pos, int steps) {
    }
}
