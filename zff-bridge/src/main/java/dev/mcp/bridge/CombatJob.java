package dev.mcp.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;

/**
 * Ph8 M3.1/M3.2: one-target combat job with Bridge-internal AutoAim.
 *
 * <p>Melee uses the vanilla attack cooldown; ranged uses bow/crossbow/trident charge and releases
 * once. AutoAim is not a mod dependency and has no off switch: the job always rotates to the target,
 * with a simple gravity/velocity lead for projectiles.</p>
 */
public final class CombatJob implements Jobs.TickTask {
    private final int entityId;
    private final String wantedWeapon;
    private int ticks;
    private int phase; // 0 prepare, 1 melee windup, 2 ranged charging, 3 done
    private int weaponSlot = -1;
    private boolean ranged;
    private int chargeTicks;
    private int rangedTicks;
    private boolean attacked;

    public CombatJob(int entityId, String wantedWeapon) {
        this.entityId = entityId;
        this.wantedWeapon = wantedWeapon == null ? "" : wantedWeapon;
    }

    @Override
    public void start(Jobs.Job job) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            job.fail(Errors.DISCONNECTED, "player is not in a world");
            return;
        }
        Entity target = findTarget(p);
        if (target == null) {
            job.fail(Errors.ENTITY_NOT_FOUND, "entity " + entityId + " is not loaded");
            return;
        }
        double dist = p.distanceTo(target);
        weaponSlot = chooseWeapon(p, dist);
        if (weaponSlot < 0) {
            job.fail(Errors.WRONG_TOOL, "no suitable melee/ranged weapon in the hotbar");
            return;
        }
        Actions.selectSlot(p, weaponSlot);
        ItemStack held = p.getMainHandItem();
        ranged = held.getItem() instanceof BowItem || held.getItem() instanceof CrossbowItem
                || held.getItem() instanceof TridentItem;
        if (!ranged && !(held.getItem() instanceof SwordItem || held.getItem() instanceof AxeItem)
                && !wantedWeapon.isEmpty() && !wantedWeapon.equals("auto")) {
            job.fail(Errors.WRONG_TOOL, "weapon " + wantedWeapon + " is not available");
            return;
        }
        chargeTicks = held.getItem() instanceof BowItem ? 20 : held.getItem() instanceof CrossbowItem ? 25 : 10;
        job.metric("weapon", held.getItem().toString());
        job.metric("ranged", ranged);
        job.metric("phase", "prepare");
        aim(p, target, ranged);
    }

    @Override
    public void tick(Jobs.Job job) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) {
            job.fail(Errors.DISCONNECTED, "player left the world");
            return;
        }
        if (ticks++ > 200) {
            job.fail(Errors.TIMEOUT, "combat_attack did not finish");
            return;
        }
        Entity target = findTarget(p);
        if (target == null || !target.isAlive()) {
            if (attacked || phase == 3) {
                succeed(job);
            } else {
                job.fail(Errors.ENTITY_NOT_FOUND, "target disappeared before attack");
            }
            return;
        }
        if (!(target instanceof Enemy) && !wantedWeapon.equalsIgnoreCase("force")) {
            job.fail(Errors.BLOCKED, "target is not an Enemy; refusing friendly fire");
            return;
        }
        if (phase == 0) {
            phase = ranged ? 2 : 1;
            job.metric("phase", ranged ? "charging" : "melee");
            return;
        }
        if (phase == 1) {
            double dist = p.distanceTo(target);
            if (dist > 4.2) {
                job.fail(Errors.OUT_OF_REACH, "target is " + MathUtil.round2(dist) + " blocks away");
                return;
            }
            aim(p, target, false);
            float cooldown = p.getAttackStrengthScale(0.5f);
            if (cooldown < 0.92f) return;
            mc.gameMode.attack(p, target);
            p.swing(InteractionHand.MAIN_HAND);
            attacked = true;
            phase = 3;
            job.metric("phase", "struck");
            return;
        }
        if (phase == 2) {
            aim(p, target, true);
            if (rangedTicks == 0) {
                InteractionResult r = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                p.swing(InteractionHand.MAIN_HAND);
                job.metric("use", String.valueOf(r));
            }
            rangedTicks++;
            if (rangedTicks < chargeTicks) return;
            // Release only once; do not re-use the item every tick (that resets the charge).
            mc.gameMode.releaseUsingItem(p);
            p.stopUsingItem();
            attacked = true;
            phase = 3;
            job.metric("phase", "released");
            job.metric("chargeTicks", rangedTicks);
            return;
        }
        if (phase == 3) {
            // Let the projectile/attack land, then report.
            if (ticks > 10) succeed(job);
        }
    }

    private Entity findTarget(LocalPlayer p) {
        ClientLevel level = (ClientLevel) p.level();
        Entity e = level.getEntity(entityId);
        if (e != null && e.isAlive()) return e;
        for (Entity candidate : level.getEntities(p, p.getBoundingBox().inflate(48))) {
            if (candidate.getId() == entityId && candidate.isAlive()) return candidate;
        }
        return null;
    }

    private int chooseWeapon(LocalPlayer p, double dist) {
        int best = -1;
        double bestScore = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            boolean melee = s.getItem() instanceof SwordItem || s.getItem() instanceof AxeItem;
            boolean bow = s.getItem() instanceof BowItem || s.getItem() instanceof CrossbowItem;
            boolean trident = s.getItem() instanceof TridentItem;
            if (dist > 4.2 && !(bow || trident)) continue;
            if (dist <= 4.2 && !(melee || trident || bow)) continue;
            double score = melee ? 100 - dist : 80 - Math.abs(dist - 8);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private static void aim(LocalPlayer p, Entity target, boolean projectile) {
        double dx = target.getX() - p.getX();
        double dz = target.getZ() - p.getZ();
        double dy = (target.getY() + target.getBbHeight() * 0.5) - p.getEyeY();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        if (projectile) {
            double speed = 3.0;
            if (p.getMainHandItem().getItem() instanceof TridentItem) speed = 2.5;
            if (p.getMainHandItem().getItem() instanceof CrossbowItem) speed = 3.15;
            // Lead the target once, then solve the pitch by simulating vanilla arrow drag/gravity.
            double t = horizontal / speed;
            dx += target.getDeltaMovement().x * t;
            dz += target.getDeltaMovement().z * t;
            dy += target.getDeltaMovement().y * t;
            horizontal = Math.sqrt(dx * dx + dz * dz);
            yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            double bestPitch = 0;
            double bestErr = Double.MAX_VALUE;
            for (double upDeg = -75.0; upDeg <= 20.0; upDeg += 0.25) {
                double rad = Math.toRadians(upDeg);
                double vx = speed * Math.cos(rad);
                double vy = speed * Math.sin(rad);
                double x = 0;
                double y = 0;
                for (int ticks = 0; ticks < 260; ticks++) {
                    x += vx;
                    y += vy;
                    vx *= 0.99;
                    vy = vy * 0.99 - 0.05;
                    if (x >= horizontal) break;
                }
                double err = Math.abs(y - dy);
                if (err < bestErr) {
                    bestErr = err;
                    bestPitch = upDeg;
                }
            }
            p.setYRot(yaw);
            p.setXRot((float) -bestPitch);
            p.setYHeadRot(yaw);
            p.setYBodyRot(yaw);
            return;
        }
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.setYHeadRot(yaw);
        p.setYBodyRot(yaw);
    }

    private void succeed(Jobs.Job job) {
        JsonObject out = new JsonObject();
        out.addProperty("entityId", entityId);
        out.addProperty("ranged", ranged);
        out.addProperty("weapon", weaponSlot >= 0 && Minecraft.getInstance().player != null
                ? Minecraft.getInstance().player.getInventory().getItem(weaponSlot).getItem().toString() : "");
        out.addProperty("attacked", attacked);
        job.succeed(out);
        phase = 3;
    }
}
