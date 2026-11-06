package dev.mcp.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;

/**
 * Ph8: far-range kill job (64 block search). Uses a real bow/crossbow charge and release; never
 * repeats useItem every tick. Results always include health + armor durability.
 */
public final class KillTargetFarJob implements Jobs.TickTask {
    private final String requestedWeapon;
    private final double searchRadius;
    private final double aimOffset;
    private int slot = -1;
    private Entity target;
    private int chargeTicks = 20;
    private int rangedTicks;
    private int attacks;
    private int hits;
    private int ticks;
    private int lastTargetHealth = -1;
    private long startedAt;
    private long lastHitAt;
    private long shieldUntil;
    private boolean shieldHeld;
    private boolean pendingHit;
    private boolean using;
    private String outcome;

    public KillTargetFarJob(String requestedWeapon) {
        this(requestedWeapon, 64.0, 0.0);
    }

    public KillTargetFarJob(String requestedWeapon, double searchRadius) {
        this(requestedWeapon, searchRadius, 0.0);
    }

    public KillTargetFarJob(String requestedWeapon, double searchRadius, double aimOffset) {
        this.requestedWeapon = requestedWeapon == null ? "" : requestedWeapon;
        this.searchRadius = searchRadius;
        this.aimOffset = aimOffset;
    }

    @Override
    public void start(Jobs.Job job) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            job.fail(Errors.DISCONNECTED, "player is not in a world");
            return;
        }
        startedAt = System.currentTimeMillis();
        lastHitAt = startedAt;
        slot = CombatUtil.findRanged(p, requestedWeapon);
        if (slot < 0) {
            EventPush.pushCombat("ranged weapon not found: " + requestedWeapon);
            job.fail(Errors.BLOCKED, "no usable bow/crossbow/trident in the inventory");
            return;
        }
        ItemStack weapon = p.getInventory().getItem(slot);
        if (!CombatUtil.usableWeapon(weapon)) {
            EventPush.pushCombat("ranged weapon broken: " + weapon.getItem());
            job.fail(Errors.TOOL_BROKEN, "the selected ranged weapon is broken");
            return;
        }
        if (weapon.getItem() instanceof BowItem || weapon.getItem() instanceof CrossbowItem) {
            if (CombatUtil.arrows(p) <= 0) {
                EventPush.pushCombat("no arrows for " + weapon.getItem());
                job.fail(Errors.BLOCKED, "no arrows in the inventory");
                return;
            }
        }
        target = CombatUtil.nearestEnemy(p, searchRadius);
        if (target == null) {
            job.fail(Errors.ENTITY_NOT_FOUND, "no Enemy within " + searchRadius + " blocks");
            return;
        }
        lastTargetHealth = Math.round(((LivingEntity) target).getHealth());
        if (slot < 9) Actions.selectSlot(p, slot);
        chargeTicks = weapon.getItem() instanceof BowItem ? 20 : weapon.getItem() instanceof CrossbowItem ? 25 : 10;
        job.metric("target", target.getName().getString());
        job.metric("targetId", target.getId());
        job.metric("weapon", weapon.getItem().toString());
        job.metric("chargeTicks", chargeTicks);
        job.metric("phase", "aim");
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
        long now = System.currentTimeMillis();
        if (!p.isAlive() || p.getHealth() <= 0) {
            finish(job, "player_died");
            return;
        }
        if (target == null || !target.isAlive()) {
            finish(job, "target_killed");
            return;
        }
        if (pendingHit) {
            int hp = Math.round(((LivingEntity) target).getHealth());
            if (hp < lastTargetHealth || !target.isAlive()) {
                hits++;
                lastHitAt = now;
            }
            lastTargetHealth = hp;
            pendingHit = false;
        }
        if (now - lastHitAt > 10_000L) {
            outcome = "no_hit_timeout";
            finish(job, outcome);
            return;
        }
        if (p.distanceTo(target) > 24) {
            // Far search is 64 blocks, but the actual bow shot is taken after closing to a reliable
            // range. This is a moving AI, not a stationary 60-block accuracy lottery.
            if (CombatUtil.hasShield(p)) {
                CombatUtil.face(p, target);
                CombatUtil.shieldUp(p);
                shieldHeld = true;
            }
            CombatUtil.moveToward(p, target, true);
            job.metric("phase", "chase_shield");
            job.metric("distance", MathUtil.round2(p.distanceTo(target)));
            return;
        }
        // Between bow shots, block with the offhand shield instead of standing unprotected.
        if (CombatUtil.hasShield(p) && now < shieldUntil) {
            CombatUtil.stopMove();
            CombatUtil.face(p, target);
            CombatUtil.shieldUp(p);
            shieldHeld = true;
            job.metric("phase", "shield");
            return;
        }
        if (shieldHeld) {
            CombatUtil.releaseUse(p);
            shieldHeld = false;
        }
        CombatUtil.stopMove();
        if (p.distanceTo(target) > 70) {
            finish(job, "target_lost");
            return;
        }
        aim(p, target, aimOffset);
        if (!using) {
            CombatUtil.mouseRight(true);
            mc.options.keyUse.setDown(true);
            using = true;
            rangedTicks = 0;
            job.metric("use", "mouse_inject");
            job.metric("phase", "charging");
            return;
        }
        rangedTicks++;
        if (rangedTicks < chargeTicks) return;
        CombatUtil.mouseRight(false);
        mc.options.keyUse.setDown(false);
        // This is the packet that actually fires the bow. stopUsingItem() is client-only.
        mc.gameMode.releaseUsingItem(p);
        p.stopUsingItem();
        using = false;
        shieldUntil = now + 700L;
        attacks++;
        pendingHit = true;
        job.metric("attacks", attacks);
        job.metric("hits", hits);
        job.metric("phase", "released");
        if (CombatUtil.arrows(p) <= 0) {
            EventPush.pushCombat("no arrows left after shot");
            finish(job, "ammo_empty");
        }
    }

    private void finish(Jobs.Job job, String result) {
        CombatUtil.stopMove();
        CombatUtil.releaseUse(Minecraft.getInstance().player);
        using = false;
        shieldHeld = false;
        JsonObject o = CombatUtil.baseResult(result, Minecraft.getInstance().player);
        o.addProperty("attacks", attacks);
        o.addProperty("hits", hits);
        o.addProperty("targetId", target == null ? -1 : target.getId());
        o.addProperty("targetName", target == null ? "" : target.getName().getString());
        o.addProperty("elapsedMs", System.currentTimeMillis() - startedAt);
        job.succeed(o);
    }

    private static void aim(LocalPlayer p, Entity target, double aimOffset) {
        double dx = target.getX() - p.getX();
        double dz = target.getZ() - p.getZ();
        double dy = (target.getY() + target.getBbHeight() * 0.5 + aimOffset) - p.getEyeY();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double speed = p.getMainHandItem().getItem() instanceof BowItem ? 3.0
                : p.getMainHandItem().getItem() instanceof CrossbowItem ? 3.15 : 2.5;
        double t = horizontal / speed;
        dx += target.getDeltaMovement().x * t;
        dz += target.getDeltaMovement().z * t;
        dy += target.getDeltaMovement().y * t;
        horizontal = Math.sqrt(dx * dx + dz * dz);
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double bestPitch = 0;
        double bestErr = Double.MAX_VALUE;
        for (double upDeg = -75; upDeg <= 80; upDeg += 0.25) {
            double rad = Math.toRadians(upDeg);
            double vx = speed * Math.cos(rad);
            double vy = speed * Math.sin(rad);
            double x = 0, y = 0;
            for (int i = 0; i < 260; i++) {
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
        p.setYRot((float) yaw);
        p.setXRot((float) -bestPitch);
        p.setYHeadRot((float) yaw);
        p.setYBodyRot((float) yaw);
        // The client-only setters do not reach the integrated server; without this packet the
        // server uses the old rotation and long/high shots miss even though the client looks right.
        p.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                p.getYRot(), p.getXRot(), p.onGround()));
    }

    @Override
    public void cancel(Jobs.Job job) {
        Minecraft mc = Minecraft.getInstance();
        CombatUtil.stopMove();
        CombatUtil.releaseUse(mc.player);
        using = false;
    }
}
