package dev.mcp.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;

import java.util.List;

/** Ph8: flee while > threshold enemies are within 16 blocks; stop when 20x20 is safe. */
public final class EscapeJob implements Jobs.TickTask {
    private final int threshold;
    private int ticks;
    private int startHealth;

    public EscapeJob(int threshold) {
        this.threshold = MathUtil.clamp(threshold, 1, 30);
    }

    @Override
    public void start(Jobs.Job job) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            job.fail(Errors.DISCONNECTED, "player is not in a world");
            return;
        }
        startHealth = (int) Math.ceil(p.getHealth());
        job.metric("threshold", threshold);
        job.metric("phase", "flee");
    }

    @Override
    public void tick(Jobs.Job job) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) {
            finish(job, "disconnected");
            return;
        }
        ticks++;
        if (!p.isAlive() || p.getHealth() <= 0) {
            finish(job, "player_died");
            return;
        }
        int close = CombatUtil.countEnemies(p, 8);   // 16x16 horizontal area
        int safe = CombatUtil.countEnemies(p, 10);   // 20x20 stop condition
        if (safe <= threshold - 1) {
            finish(job, "safe");
            return;
        }
        if (ticks > 20 * 120) {
            finish(job, "timeout");
            return;
        }
        List<Entity> enemies = p.level().getEntities(p, p.getBoundingBox().inflate(16),
                e -> e instanceof Enemy && e.isAlive());
        double cx = 0, cz = 0;
        for (Entity e : enemies) {
            cx += e.getX();
            cz += e.getZ();
        }
        if (!enemies.isEmpty()) {
            cx /= enemies.size();
            cz /= enemies.size();
            double dx = p.getX() - cx;
            double dz = p.getZ() - cz;
            if (Math.abs(dx) + Math.abs(dz) < 0.01) {
                dx = 1;
                dz = 0;
            }
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            p.setYRot(yaw);
            p.setYHeadRot(yaw);
            p.setYBodyRot(yaw);
            mc.options.keyUp.setDown(true);
            mc.options.keySprint.setDown(true);
            mc.options.keyJump.setDown(p.horizontalCollision && p.onGround());
        }
        job.metric("closeEnemies", close);
        job.metric("safeEnemies", safe);
        job.metric("phase", "flee");
    }

    private void finish(Jobs.Job job, String result) {
        CombatUtil.stopMove();
        JsonObject o = CombatUtil.baseResult(result, Minecraft.getInstance().player);
        o.addProperty("startHealth", startHealth);
        o.addProperty("closeEnemies", CombatUtil.countEnemies(Minecraft.getInstance().player, 8));
        o.addProperty("safeEnemies", CombatUtil.countEnemies(Minecraft.getInstance().player, 10));
        o.addProperty("elapsedMs", ticks * 50L);
        job.succeed(o);
    }

    @Override
    public void cancel(Jobs.Job job) {
        CombatUtil.stopMove();
    }
}
