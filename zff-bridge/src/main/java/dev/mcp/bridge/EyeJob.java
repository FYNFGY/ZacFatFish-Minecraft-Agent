package dev.mcp.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Ender-eye throw protocol (Ph4 P1-7).
 *
 * <p>One throw per job: select the eye, use it, capture the projectile within the first 4 ticks,
 * track it until it despawns and report the bearing (from the throw position to the last tracked
 * position) plus the landing point. When the eye drops as an item it walks over and picks it back
 * up, so a failed throw does not cost an eye.</p>
 *
 * <p>Triangulation is done by the agent (two throws from spots &gt; 40 blocks apart, then the
 * {@code triangulate} tool); the job deliberately never re-throws from the same spot.</p>
 */
public final class EyeJob implements Jobs.TickTask {
    private static final int CAPTURE_TICKS = 4;
    private static final int TRACK_TICKS = 100;
    private static final int PICKUP_TICKS = 90;

    private int state; // 0 find eye, 1 throw, 2 capture, 3 track, 4 pickup, 5 done
    private int ticks;
    private int throwX;
    private int throwY;
    private int throwZ;
    private double originX;
    private double originY;
    private double originZ;
    private int eyeId = -1;
    private double lastX;
    private double lastY;
    private double lastZ;
    private int trackedTicks;
    private int hotbar = -1;
    private int eyesBefore;
    private int eyesAfter;
    private boolean pickedUp;
    private String lostReason;
    private boolean waitForEntity;

    @Override
    public void start(Jobs.Job job) {
        job.metric("state", "find");
        Jobs manager = BridgeRuntime.jobs;
        if (manager != null) {
            manager.cancelTool("path_to");
            manager.cancelTool("walk");
            manager.cancelTool("walk_to");
        }
        BaritoneControl.cancel();
    }

    private static String idOf(ItemStack stack) {
        return stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static int countEyes(LocalPlayer player) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.isEmpty() && "minecraft:ender_eye".equals(idOf(s))) {
                n += s.getCount();
            }
        }
        return n;
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
        switch (state) {
            case 0 -> {
                eyesBefore = countEyes(player);
                if (eyesBefore <= 0) {
                    job.fail(Errors.UNSUPPORTED, "no eye of ender in the inventory");
                    return;
                }
                hotbar = -1;
                for (int i = 0; i < 9; i++) {
                    if ("minecraft:ender_eye".equals(idOf(player.getInventory().getItem(i)))) {
                        hotbar = i;
                        break;
                    }
                }
                if (hotbar < 0) {
                    for (int i = 9; i < 36; i++) {
                        if ("minecraft:ender_eye".equals(idOf(player.getInventory().getItem(i)))) {
                            hotbar = 0;
                            for (int h = 8; h >= 0; h--) {
                                if (player.getInventory().getItem(h).isEmpty()) {
                                    hotbar = h;
                                    break;
                                }
                            }
                            mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, i, hotbar,
                                    net.minecraft.world.inventory.ClickType.SWAP, player);
                            break;
                        }
                    }
                }
                if (hotbar < 0) {
                    job.fail(Errors.UNSUPPORTED, "could not move an eye of ender to the hotbar");
                    return;
                }
                Actions.selectSlot(player, hotbar);
                job.metric("state", "throw");
                state = 1;
            }
            case 1 -> {
                Actions.selectSlot(player, hotbar);
                originX = player.getX();
                originY = player.getEyeY();
                originZ = player.getZ();
                throwX = (int) Math.floor(player.getX());
                throwY = (int) Math.floor(player.getEyeY());
                throwZ = (int) Math.floor(player.getZ());
                // A second throw from (almost) the same spot is useless: refuse instead of wasting eyes.
                mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                player.swing(InteractionHand.MAIN_HAND);
                ticks = 0;
                waitForEntity = true;
                job.metric("state", "capture");
                state = 2;
            }
            case 2 -> {
                ticks++;
                EyeOfEnder found = nearestEye(level, player, originX, originY, originZ, 8.0);
                if (found != null) {
                    eyeId = found.getId();
                    lastX = found.getX();
                    lastY = found.getY();
                    lastZ = found.getZ();
                    job.metric("eyeId", eyeId);
                    job.metric("state", "track");
                    state = 3;
                    ticks = 0;
                    return;
                }
                if (ticks > CAPTURE_TICKS) {
                    job.fail(Errors.NO_PROGRESS, "the eye of ender projectile did not appear within "
                            + CAPTURE_TICKS + " ticks (held: "
                            + idOf(player.getInventory().getItem(hotbar)) + ")");
                }
            }
            case 3 -> {
                ticks++;
                EyeOfEnder eye = null;
                for (Entity e : level.getEntities(player, player.getBoundingBox().inflate(64), e -> e instanceof EyeOfEnder)) {
                    if (e.getId() == eyeId) {
                        eye = (EyeOfEnder) e;
                        break;
                    }
                }
                if (eye != null) {
                    lastX = eye.getX();
                    lastY = eye.getY();
                    lastZ = eye.getZ();
                    trackedTicks = ticks;
                    job.metric("trackedTicks", trackedTicks);
                    job.metric("last", Json.doubleArray(lastX, lastY, lastZ));
                    if (ticks >= TRACK_TICKS) {
                        lostReason = "still flying after " + TRACK_TICKS + " ticks";
                        finishTracking(job, player, level);
                    }
                    return;
                }
                // The projectile is gone: it either dropped as an item or broke.
                finishTracking(job, player, level);
            }
            case 4 -> {
                // Walk to the dropped eye item and pick it up.
                ticks++;
                List<Entity> items = level.getEntities(player,
                        player.getBoundingBox().inflate(20), e -> e instanceof ItemEntity it
                                && "minecraft:ender_eye".equals(idOf(it.getItem())));
                if (items.isEmpty()) {
                    if (ticks > 30) {
                        finish(job, player);
                    }
                    return;
                }
                Entity target = items.get(0);
                double dx = target.getX() - player.getX();
                double dz = target.getZ() - player.getZ();
                double dist = Math.hypot(dx, dz);
                if (dist < 1.1) {
                    pickedUp = true;
                    finish(job, player);
                    return;
                }
                if (ticks > PICKUP_TICKS) {
                    job.metric("pickupTimeout", true);
                    finish(job, player);
                    return;
                }
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                player.setYRot(yaw);
                player.setYHeadRot(yaw);
                if (player.onGround() && player.horizontalCollision) {
                    player.jumpFromGround();
                }
                player.setDeltaMovement(dx / dist * 0.25, player.getDeltaMovement().y, dz / dist * 0.25);
                player.hurtMarked = true;
            }
            default -> job.fail(Errors.INTERNAL, "bad state " + state);
        }
    }

    private void finishTracking(Jobs.Job job, LocalPlayer player, ClientLevel level) {
        double dx = lastX - originX;
        double dz = lastZ - originZ;
        double horizontal = Math.hypot(dx, dz);
        float bearing = (float) Math.toDegrees(Math.atan2(-dx, dz));
        JsonObject m = new JsonObject();
        m.add("throwFrom", Json.doubleArray(originX, originY, originZ));
        m.add("lastSeen", Json.doubleArray(lastX, lastY, lastZ));
        m.addProperty("bearing", Json.round(bearing, 2));
        m.addProperty("horizontalDistance", Json.round(horizontal, 2));
        m.addProperty("trackedTicks", trackedTicks);
        if (lostReason != null) {
            m.addProperty("lostReason", lostReason);
        }
        job.metric("flight", m);
        job.metric("state", "pickup");
        // The eye flies up to ~80 ticks and lands ~12 blocks away; the item can be a couple of blocks
        // further than the last tracked position, so search a wide box.
        List<Entity> items = level.getEntities(player,
                player.getBoundingBox().inflate(20), e -> e instanceof ItemEntity it
                        && "minecraft:ender_eye".equals(idOf(it.getItem())));
        if (items.isEmpty()) {
            job.metric("eyeItem", "none");
            finish(job, player);
            return;
        }
        state = 4;
        ticks = 0;
    }

    private void finish(Jobs.Job job, LocalPlayer player) {
        eyesAfter = countEyes(player);
        JsonObject r = new JsonObject();
        r.addProperty("throwFromX", originX);
        r.addProperty("throwFromZ", originZ);
        r.addProperty("lastX", lastX);
        r.addProperty("lastY", lastY);
        r.addProperty("lastZ", lastZ);
        double dx = lastX - originX;
        double dz = lastZ - originZ;
        r.addProperty("bearing", Json.round(Math.toDegrees(Math.atan2(-dx, dz)), 2));
        r.addProperty("horizontalDistance", Json.round(Math.hypot(dx, dz), 2));
        r.addProperty("trackedTicks", trackedTicks);
        r.addProperty("eyesBefore", eyesBefore);
        r.addProperty("eyesAfter", eyesAfter);
        r.addProperty("pickedUp", pickedUp);
        r.addProperty("eyeConsumed", eyesBefore - eyesAfter);
        if (lostReason != null) {
            r.addProperty("lostReason", lostReason);
        }
        job.succeed(r);
    }

    private static EyeOfEnder nearestEye(ClientLevel level, LocalPlayer player,
                                         double ox, double oy, double oz, double radius) {
        AABB box = new AABB(ox - radius, oy - radius, oz - radius, ox + radius, oy + radius, oz + radius);
        EyeOfEnder best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : level.getEntities(player, box, e -> e instanceof EyeOfEnder)) {
            double d = e.distanceToSqr(ox, oy, oz);
            if (d < bestDist) {
                bestDist = d;
                best = (EyeOfEnder) e;
            }
        }
        return best;
    }
}
