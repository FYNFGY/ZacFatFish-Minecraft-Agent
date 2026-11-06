package dev.mcp.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * Keeps the AI player's idle head/camera level. Combat/scanning may deliberately pitch down, but
 * once no job is running the character should not stay hunched over.
 */
public final class ViewGuard {
    private ViewGuard() {
    }

    public static void tick(LocalPlayer player, Jobs jobs) {
        if (player == null || !player.isAlive() || player.isSleeping()) return;
        if (Minecraft.getInstance().screen != null) return;
        if (jobs != null && !jobs.active().isEmpty()) return;
        if (Math.abs(player.getXRot()) < 15.0f) return;
        player.setXRot(0.0f);
        player.setYHeadRot(player.getYRot());
        player.setYBodyRot(player.getYRot());
        if (player.connection != null) {
            player.connection.send(new ServerboundMovePlayerPacket.Rot(
                    player.getYRot(), 0.0f, player.onGround()));
        }
    }
}
