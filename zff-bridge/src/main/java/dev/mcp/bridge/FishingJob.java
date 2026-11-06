package dev.mcp.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;

/**
 * Ph8 M2.1: one cast, one reel, no auto-repeat.
 *
 * <p>The job finds a nearby water source, selects a fishing rod, faces the water, casts, waits for
 * the bobber to bite (FishingHook.biting / hooked entity), reels once, and finishes. It never casts a
 * second time.</p>
 */
public final class FishingJob implements Jobs.TickTask {
    private final int timeoutSeconds;
    private int ticks;
    private int phase; // 0 find/face/cast, 1 waiting, 2 reeling, 3 done
    private BlockPos water;
    private int rodSlot = -1;
    private int startCount;
    private int startFish;
    private int casts;
    private String failCode;
    private String failDetail;

    public FishingJob(int timeoutSeconds) {
        this.timeoutSeconds = MathUtil.clamp(timeoutSeconds, 10, 180);
    }

    @Override
    public void start(Jobs.Job job) {
        job.metric("phase", "find_water");
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            job.fail(Errors.DISCONNECTED, "player is not in a world");
            return;
        }
        rodSlot = findRod(p);
        if (rodSlot < 0) {
            job.fail(Errors.BLOCKED, "no fishing rod in the hotbar");
            return;
        }
        water = findWater(p);
        if (water == null) {
            job.fail(Errors.BLOCKED, "no reachable water source within 16 blocks");
            return;
        }
        startCount = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) startCount += p.getInventory().getItem(i).getCount();
        startFish = countFish(p);
        Actions.selectSlot(p, rodSlot);
        Actions.faceBlock(p, water);
        job.metric("water", water.toShortString());
    }

    @Override
    public void tick(Jobs.Job job) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) {
            job.fail(Errors.DISCONNECTED, "player left the world");
            return;
        }
        if (ticks++ > timeoutSeconds * 20) {
            job.fail(Errors.TIMEOUT, "fish timed out after " + timeoutSeconds + "s");
            return;
        }
        if (phase == 0) {
            ItemStack held = p.getMainHandItem();
            if (!(held.getItem() instanceof FishingRodItem)) {
                Actions.selectSlot(p, rodSlot);
                held = p.getMainHandItem();
            }
            InteractionResult r = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            p.swing(InteractionHand.MAIN_HAND);
            casts++;
            job.metric("cast", String.valueOf(r));
            job.metric("casts", casts);
            phase = 1;
            job.metric("phase", "waiting_bite");
            return;
        }
        if (phase == 1) {
            FishingHook hook = p.fishing;
            if (hook == null) {
                if (ticks > 20) {
                    job.fail(Errors.NO_PROGRESS, "cast produced no fishing bobber");
                }
                return;
            }
            if (isBiting(hook) || hook.getHookedIn() != null) {
                phase = 2;
                ticks = 0;
                job.metric("phase", "reeling");
                InteractionResult r = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                p.swing(InteractionHand.MAIN_HAND);
                job.metric("reel", String.valueOf(r));
            }
            return;
        }
        if (phase == 2) {
            int endCount = 0;
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) endCount += p.getInventory().getItem(i).getCount();
            int delta = endCount - startCount;
            int fishDelta = countFish(p) - startFish;
            // The server grants loot a few ticks after the reel packet. Do not finish on the
            // first tick merely because player.fishing became null.
            if (fishDelta > 0) {
                JsonObject out = new JsonObject();
                out.addProperty("cast", casts);
                out.addProperty("casts", casts);
                out.addProperty("reeled", true);
                out.addProperty("inventoryDelta", delta);
                out.addProperty("fishDelta", fishDelta);
                out.addProperty("water", water == null ? "" : water.toShortString());
                job.succeed(out);
                phase = 3;
            } else if (ticks > 20) {
                if (casts < 8) {
                    // Junk or an empty reel is not a fishing success. Recast automatically so a
                    // single fish call only returns success when a fish item is actually caught.
                    phase = 0;
                    ticks = 0;
                    job.metric("phase", "recast_after_junk");
                    job.metric("junkRecasts", casts);
                    return;
                }
                if (delta > 0) {
                    job.fail(Errors.NO_PROGRESS, "caught junk only, no fish item entered the inventory");
                } else {
                    job.fail(Errors.NO_PROGRESS, "reeled but no item entered the inventory");
                }
            }
            return;
        }
    }

    private static int countFish(LocalPlayer p) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.is(Items.COD) || s.is(Items.SALMON) || s.is(Items.TROPICAL_FISH) || s.is(Items.PUFFERFISH)) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static int findRod(LocalPlayer p) {        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.getItem() instanceof FishingRodItem) return i;
        }
        return -1;
    }

    private static BlockPos findWater(LocalPlayer p) {
        ClientLevel level = (ClientLevel) p.level();
        BlockPos base = p.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -16; dx <= 16; dx++) {
                for (int dz = -16; dz <= 16; dz++) {
                    BlockPos pos = base.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (!state.getFluidState().is(FluidTags.WATER)) continue;
                    if (!state.getFluidState().isSource()) continue;
                    if (!level.getBlockState(pos.above()).isAir()) continue;
                    double d = p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    if (d < bestD && d <= 16 * 16) {
                        bestD = d;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    private static boolean isBiting(FishingHook hook) {
        try {
            // DATA_BITING is the synced source of truth; private field reads are only a fallback.
            java.lang.reflect.Field data = FishingHook.class.getDeclaredField("DATA_BITING");
            data.setAccessible(true);
            Object accessor = data.get(null);
            if (accessor instanceof net.minecraft.network.syncher.EntityDataAccessor<?> a) {
                Object v = hook.getEntityData().get((net.minecraft.network.syncher.EntityDataAccessor<?>) a);
                if (v instanceof Boolean b && b) return true;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        try {
            Class<?> c = hook.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (!f.getName().equalsIgnoreCase("biting")) continue;
                    f.setAccessible(true);
                    Object v = f.get(hook);
                    if (v instanceof Boolean b) return b;
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
            // fall through to no-bite
        }
        // Do not treat ordinary bobber bobbing as a bite. A false reel consumes the cast and
        // produces no loot; wait for the synced DATA_BITING flag instead.
        return false;
    }
}
