package dev.mcp.bridge;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Small helpers shared by the dig/branch jobs: fluid probing, solidity, movement. */
public final class DigUtil {
    private DigUtil() {
    }

    public static boolean solid(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(level, pos).isEmpty();
    }

    public static boolean fluid(ClientLevel level, BlockPos pos) {
        return !level.getBlockState(pos).getFluidState().isEmpty();
    }

    public static boolean unbreakable(ClientLevel level, BlockPos pos) {
        return level.getBlockState(pos).getDestroySpeed(level, pos) < 0;
    }

    /** True when any of the next {@code depth} blocks along (dx,dz) holds water/lava. */
    public static boolean fluidAhead(ClientLevel level, BlockPos from, int dx, int dz, int depth, int yFrom, int yTo) {
        BlockPos p = from;
        for (int i = 1; i <= depth; i++) {
            p = new BlockPos(from.getX() + dx * i, from.getY(), from.getZ() + dz * i);
            for (int y = Math.min(yFrom, yTo); y <= Math.max(yFrom, yTo); y++) {
                BlockPos q = new BlockPos(p.getX(), y, p.getZ());
                if (fluid(level, q)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Pushes the player one step along yaw (client-side movement, like WalkJob). */
    public static void walk(LocalPlayer player, float yaw, double speed) {
        double radians = Math.toRadians(yaw);
        double dx = -Math.sin(radians);
        double dz = Math.cos(radians);
        player.setDeltaMovement(dx * speed, player.getDeltaMovement().y, dz * speed);
        player.hurtMarked = true;
    }
}
