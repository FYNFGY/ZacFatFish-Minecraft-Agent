package dev.mcp.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * Drives the local player's velocity for a few ticks so the agent can step off ledges, line up a block,
 * jump a gap, sprint or swim up. This is client movement (the server still validates positions).
 */
public final class WalkJob implements Jobs.TickTask {
    private final float yaw;
    private final int ticks;
    private final boolean jump;
    private final boolean sprint;
    private final boolean up;
    private int done;

    public WalkJob(float yaw, int ticks, boolean jump, boolean sprint, boolean up) {
        this.yaw = yaw;
        this.ticks = MathUtil.clamp(ticks, 1, 400);
        this.jump = jump;
        this.sprint = sprint;
        this.up = up;
    }

    @Override
    public void start(Jobs.Job job) {
        job.metric("ticks", ticks);
        Jobs manager = BridgeRuntime.jobs;
        if (manager != null) {
            manager.cancelTool("path_to");
        }
        BaritoneControl.cancel();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && sprint) {
            player.setSprinting(true);
        }
    }

    @Override
    public void tick(Jobs.Job job) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            job.fail(Errors.DISCONNECTED, "the player left the world");
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        BlockPos head = player.blockPosition().above(2);
        boolean headBlocked = level != null && !level.getBlockState(head).isAir()
                && !level.getBlockState(head).canBeReplaced();
        if (done == 0 && jump && player.onGround() && !headBlocked) {
            player.jumpFromGround();
        } else if (jump && !headBlocked && player.onGround() && player.horizontalCollision && done % 10 == 0) {
            // A single initial jump is not enough on a 1-block lip: retry while the head is clear.
            player.jumpFromGround();
        }
        if (headBlocked) job.metric("headBlocked", head.toShortString());
        float direction = Float.isNaN(yaw) ? player.getYRot() : yaw;
        double radians = Math.toRadians(direction);
        double dx = -Math.sin(radians);
        double dz = Math.cos(radians);
        double dy = player.getDeltaMovement().y;
        if (up) {
            if (player.isInWater()) {
                // Swim upward; this is what saves the player after falling into an aquifer.
                dy = Math.max(dy, 0.32);
            } else if (player.onGround() && done % 12 == 0) {
                player.jumpFromGround();
                dy = player.getDeltaMovement().y;
            }
        }
        double speed = sprint ? 0.30 : 0.22;
        player.setDeltaMovement(dx * speed, dy, dz * speed);
        player.hurtMarked = true;
        done++;
        if (done >= ticks) {
            player.setDeltaMovement(0.0, player.getDeltaMovement().y, 0.0);
            player.setSprinting(false);
            JsonObject result = new JsonObject();
            result.addProperty("ticks", done);
            result.addProperty("x", MathUtil.round2(player.getX()));
            result.addProperty("y", MathUtil.round2(player.getY()));
            result.addProperty("z", MathUtil.round2(player.getZ()));
            result.addProperty("inWater", player.isInWater());
            result.addProperty("air", player.getAirSupply());
            job.succeed(result);
        }
    }
}
