package dev.mcp.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * MLG water landing: walk off the edge, then place water under the feet just before impact so the fall
 * is absorbed. Finds the ground below by scanning down, places the water when the drop is < 3 blocks
 * and reports the fall height.
 */
public final class MlgWaterJob implements Jobs.TickTask {
    private final float yaw;
    private final int maxTicks;
    private int ticks;
    private int phase; // 0 = walk off the edge, 1 = falling, 2 = done
    private double startY;
    private double lowestY;
    private boolean placed;
    private boolean waterOk;
    private int placeTries;

    public MlgWaterJob(float yaw, int maxTicks) {
        this.yaw = yaw;
        this.maxTicks = MathUtil.clamp(maxTicks, 20, 600);
    }

    @Override
    public void start(Jobs.Job job) {
        job.metric("phase", "walk_off");
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            startY = player.getY();
            lowestY = player.getY();
        }
        Jobs manager = BridgeRuntime.jobs;
        if (manager != null) {
            manager.cancelTool("path_to");
            manager.cancelTool("walk");
            manager.cancelTool("walk_to");
        }
        BaritoneControl.cancel();
    }

    @Override
    public void tick(Jobs.Job job) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            job.fail(Errors.DISCONNECTED, "player left the world");
            return;
        }
        if (ticks++ > maxTicks) {
            job.fail(Errors.TIMEOUT, "mlg_water did not finish in " + maxTicks + " ticks");
            return;
        }
        lowestY = Math.min(lowestY, player.getY());
        if (phase == 0) {
            if (!player.onGround()) {
                phase = 1;
                job.metric("phase", "falling");
                return;
            }
            double radians = Math.toRadians(yaw);
            player.setDeltaMovement(-Math.sin(radians) * 0.25, player.getDeltaMovement().y, Math.cos(radians) * 0.25);
            player.setYRot(yaw);
            player.hurtMarked = true;
            if (ticks > 40 && player.onGround()) {
                job.fail(Errors.BLOCKED, "did not walk off the edge");
            }
            return;
        }
        if (phase == 1) {
            if (player.onGround() || player.isInWater()) {
                phase = 2;
                JsonObject r = new JsonObject();
                r.addProperty("fallFrom", MathUtil.round2(startY));
                r.addProperty("landedAt", MathUtil.round2(player.getY()));
                r.addProperty("fallDistance", MathUtil.round2(startY - player.getY()));
                r.addProperty("inWater", player.isInWater());
                r.addProperty("waterPlaced", placed);
                r.addProperty("waterOk", waterOk);
                job.succeed(r);
                return;
            }
            // Fall straight down: kill the horizontal drift we had while walking off the edge.
            player.setDeltaMovement(0, player.getDeltaMovement().y, 0);
            player.hurtMarked = true;
            // scan down for the ground
            BlockPos feet = player.blockPosition();
            BlockPos ground = null;
            for (int dy = 1; dy <= 12; dy++) {
                BlockPos p = feet.below(dy);
                BlockState s = level.getBlockState(p);
                if (!s.isAir() && !s.getFluidState().isEmpty()) {
                    ground = p;
                    break;
                }
                if (!s.isAir() && !s.getCollisionShape(level, p).isEmpty()) {
                    ground = p;
                    break;
                }
            }
            if (ground == null) {
                return;
            }
            double gap = player.getY() - (ground.getY() + 1);
            if (gap <= 3.2 && player.getDeltaMovement().y < 0) {
                if (!waterOk) {
                    waterOk = !level.getBlockState(ground.above()).getFluidState().isEmpty();
                }
                if (!waterOk && placeTries < 6) {
                    int slot = Actions.findItemSlot(player, "minecraft:water_bucket");
                    if (slot < 0 && !placed) {
                        job.fail(Errors.UNSUPPORTED, "no water bucket in the hotbar");
                        return;
                    }
                    if (slot >= 0) {
                        Actions.selectSlot(player, slot);
                        // BucketItem does its own camera raycast, so the view must actually point at the
                        // ground - aiming only the packet is not enough.
                        Actions.faceBlock(player, ground);
                        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(ground), Direction.UP, ground, false);
                        net.minecraft.world.InteractionResult res = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                        if (!res.consumesAction()) {
                            mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                        }
                        player.swing(InteractionHand.MAIN_HAND);
                        placed = true;
                        placeTries++;
                        job.metric("waterAt", Json.intArray(ground.getX(), ground.getY() + 1, ground.getZ()));
                    }
                }
            }
        }
    }
}
