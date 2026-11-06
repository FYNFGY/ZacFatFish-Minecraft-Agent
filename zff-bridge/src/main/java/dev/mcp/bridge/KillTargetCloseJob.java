package dev.mcp.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Ph8: close-range kill job.
 *
 * <p>Targets an Enemy within 16 blocks, walks to it with real movement keys, raises the offhand
 * shield between attacks, and attacks with the requested/available melee weapon. It fails if the
 * player dies, the target dies, or no successful hit lands for 10 seconds. The result always carries
 * health + armor durability.</p>
 */
public final class KillTargetCloseJob implements Jobs.TickTask {
    private final String requestedWeapon;
    private final double searchRadius;
    private final int forcedTargetId;
    private int slot = -1;
    private Entity target;
    private int attacks;
    private int hits;
    private int ticks;
    private int noHitTicks;
    private int lastTargetHealth = -1;
    private long startedAt;
    private long lastAttackAt;
    private long lastHitAt;
    private boolean pendingHit;
    private String outcome;
    private String reason;

    public KillTargetCloseJob(String requestedWeapon) {
        this(requestedWeapon, 16.0, -1);
    }

    public KillTargetCloseJob(String requestedWeapon, double searchRadius) {
        this(requestedWeapon, searchRadius, -1);
    }

    public KillTargetCloseJob(String requestedWeapon, double searchRadius, int forcedTargetId) {
        this.requestedWeapon = requestedWeapon == null ? "" : requestedWeapon;
        this.searchRadius = searchRadius;
        this.forcedTargetId = forcedTargetId;
    }

    @Override
    public void start(Jobs.Job job) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) {
            job.fail(Errors.DISCONNECTED, "player is not in a world");
            return;
        }
        startedAt = System.currentTimeMillis();
        lastHitAt = startedAt;
        slot = CombatUtil.findMelee(p, requestedWeapon);
        if (slot < 0) {
            EventPush.pushCombat("melee weapon not found: " + requestedWeapon);
            job.fail(Errors.BLOCKED, "no usable melee weapon in the inventory");
            return;
        }
        ItemStack weapon = p.getInventory().getItem(slot);
        if (!CombatUtil.usableWeapon(weapon)) {
            EventPush.pushCombat("melee weapon unusable: " + weapon.getItem());
            job.fail(Errors.TOOL_BROKEN, "the selected melee weapon is broken");
            return;
        }
        target = forcedTargetId >= 0
                ? CombatUtil.findEntityById(p, forcedTargetId, searchRadius)
                : CombatUtil.nearestEnemy(p, searchRadius);
        if (target == null) {
            job.fail(Errors.ENTITY_NOT_FOUND, "no Enemy within " + searchRadius + " blocks");
            return;
        }
        lastTargetHealth = Math.round(((LivingEntity) target).getHealth());
        if (slot < 9) Actions.selectSlot(p, slot);
        job.metric("target", target.getName().getString());
        job.metric("targetId", target.getId());
        job.metric("weapon", weapon.getItem().toString());
        job.metric("phase", "chase");
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
        // Confirm the previous swing actually removed health.
        if (pendingHit) {
            int hp = Math.round(((LivingEntity) target).getHealth());
            if (hp < lastTargetHealth || !target.isAlive()) {
                hits++;
                lastHitAt = now;
                noHitTicks = 0;
            }
            lastTargetHealth = hp;
            pendingHit = false;
        }
        noHitTicks++;
        if (noHitTicks >= 200 || now - lastHitAt > 10_000L) {
            outcome = "no_hit_timeout";
            reason = "no successful hit for 10s";
            finish(job, outcome);
            return;
        }
        if (ticks > 20 * 180) {
            finish(job, "timeout");
            return;
        }
        double dist = p.distanceTo(target);
        if (dist > 3.0) {
            CombatUtil.stopMove();
            CombatUtil.moveToward(p, target, true);
            job.metric("phase", "chase");
            job.metric("distance", MathUtil.round2(dist));
            return;
        }
        CombatUtil.stopMove();
        CombatUtil.face(p, target);
        if (CombatUtil.hasShield(p) && !(p.getAttackStrengthScale(0.5f) >= 0.95f)) {
            CombatUtil.shieldUp(p);
        }
        if (p.getAttackStrengthScale(0.5f) < 0.95f) {
            return;
        }
        if (!CombatUtil.usableWeapon(p.getInventory().getItem(slot))) {
            EventPush.pushCombat("melee weapon broke during combat");
            job.fail(Errors.TOOL_BROKEN, "melee weapon broke during combat");
            return;
        }
        CombatUtil.attack(p, target);
        attacks++;
        lastAttackAt = System.currentTimeMillis();
        pendingHit = true;
        job.metric("attacks", attacks);
        job.metric("hits", hits);
        job.metric("phase", "attacking");
    }

    private void finish(Jobs.Job job, String result) {
        CombatUtil.stopMove();
        CombatUtil.releaseUse(Minecraft.getInstance().player);
        JsonObject o = CombatUtil.baseResult(result, Minecraft.getInstance().player);
        o.addProperty("attacks", attacks);
        o.addProperty("hits", hits);
        o.addProperty("targetId", target == null ? -1 : target.getId());
        o.addProperty("targetName", target == null ? "" : target.getName().getString());
        if (reason != null) o.addProperty("reason", reason);
        o.addProperty("elapsedMs", System.currentTimeMillis() - startedAt);
        job.succeed(o);
    }

    @Override
    public void cancel(Jobs.Job job) {
        CombatUtil.stopMove();
        CombatUtil.releaseUse(Minecraft.getInstance().player);
    }
}
