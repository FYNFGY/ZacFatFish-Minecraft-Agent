package dev.mcp.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Baritone pathing with door handling: while walking, any closed door / fence gate / trapdoor within
 * reach is opened instead of letting the path get stuck against it. This is the round-3 "path rules"
 * fix for villages (farms are fenced and the gates used to block the agent).
 *
 * <p>Ph6 changes:</p>
 * <ul>
 *   <li>ISSUE-5: 只对 *_door / fence_gate / trapdoor 发交互，不再顺手开箱子；
 *       新参数 {@code openContainers}（默认 false）可以恢复旧的全开行为；结束/失败时自动关掉容器界面。</li>
 *   <li>ISSUE-6: 卡住（140 tick 无位移）时只做一次“吸附到最近门方块中心 → 重新 path”的重试，
 *       不修改 Baritone 本身，失败后仍按原来的 blocked 退出。</li>
 * </ul>
 */
public final class PathOpenJob implements Jobs.TickTask {
    /** 卡住多少 tick 后尝试门对齐（7 秒）。 */
    private static final int ALIGN_TRIGGER_TICKS = 140;
    /** 对齐阶段的最长 tick 数。 */
    private static final int ALIGN_TIMEOUT_TICKS = 80;
    /** 找门方块的半径。 */
    private static final int ALIGN_RANGE = 3;

    private final int x;
    private final int y;
    private final int z;
    private final int range;
    private final boolean openContainers;
    private int ticks;
    private int lastProgressTick;
    private double lastX;
    private double lastZ;
    private int opened;
    private int containersOpened;
    // ISSUE-6: 只重试一次“对齐到门中心 → 重新寻路”，避免把现有寻路逻辑弄坏。
    private boolean alignAttempted;
    private boolean aligning;
    private int alignTicks;
    private BlockPos alignDoor;

    public PathOpenJob(int x, int y, int z, int range) {
        this(x, y, z, range, false);
    }

    public PathOpenJob(int x, int y, int z, int range, boolean openContainers) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.range = MathUtil.clamp(range, 0, 64);
        this.openContainers = openContainers;
    }

    @Override
    public void start(Jobs.Job job) {
        job.metric("target", Json.intArray(x, y, z));
        job.metric("range", range);
        job.metric("doorsOpened", 0);
        job.metric("openContainers", openContainers);
        // A new path supersedes any older one, like path_to does.
        Jobs manager = BridgeRuntime.jobs;
        if (manager != null) {
            manager.cancelTool("path_to");
        }
        BaritoneControl.configureForPathing();
        BaritoneControl.start(x, y, z, range);
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            lastX = player.getX();
            lastZ = player.getZ();
            lastProgressTick = 0;
        }
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
        ticks++;
        if (ticks > 6000) {
            BaritoneControl.cancel();
            boolean closed = closeContainerIfOpen();
            job.fail(Errors.TIMEOUT, "path_open did not reach the target in 6000 ticks"
                    + (closed ? " (container screen closed)" : ""));
            return;
        }
        if (aligning) {
            tickAlign(job, player, level);
            return;
        }
        double distance = BaritoneControl.distanceTo(x, y, z);
        if (distance <= range + 1.0) {
            BaritoneControl.cancel();
            // ISSUE-5: 寻路结束后如果还挂着 ContainerScreen（沿路误开的箱子），自动关掉。
            boolean closed = closeContainerIfOpen();
            JsonObject r = Json.ok();
            r.addProperty("distance", MathUtil.round2(distance));
            r.addProperty("doorsOpened", opened);
            r.addProperty("containersOpened", containersOpened);
            r.addProperty("containerClosed", closed);
            r.addProperty("openContainers", openContainers);
            r.addProperty("ticks", ticks);
            job.succeed(r);
            return;
        }
        // Open gates/doors in reach every few ticks; this is what used to leave the agent stuck.
        if (ticks % 8 == 0) {
            opened += openNearbyDoors(player, level);
            job.metric("doorsOpened", opened);
            job.metric("containersOpened", containersOpened);
        }
        double moved = Math.hypot(player.getX() - lastX, player.getZ() - lastZ);
        if (moved > 0.6) {
            lastX = player.getX();
            lastZ = player.getZ();
            lastProgressTick = ticks;
        } else if (ticks - lastProgressTick > ALIGN_TRIGGER_TICKS) {
            // ISSUE-6: 1 格门 + 0.3 格对齐误差会让 Baritone 一直判定 blocked。卡住时先只做一次
            // “走到门方块中心”的吸附，然后重新发起同一条 path；仍失败才按原逻辑退出。
            if (!alignAttempted) {
                BlockPos door = findDoorToAlign(player, level);
                if (door != null) {
                    startAlign(job, door);
                    return;
                }
            }
            // Stuck for 7 seconds: try a wider door sweep, then give up with a reason.
            opened += openNearbyDoors(player, level);
            if (ticks - lastProgressTick > 220) {
                BaritoneControl.cancel();
                boolean closed = closeContainerIfOpen();
                job.fail(Errors.BLOCKED, "path made no progress for 11s at "
                        + player.blockPosition().toShortString() + " (doors opened: " + opened
                        + (closed ? ", container screen closed" : "") + ")");
                return;
            }
        }
        job.metric("distance", MathUtil.round2(distance));
        job.metric("doorsOpened", opened);
        job.metric("containersOpened", containersOpened);
        job.progress(Math.max(1, Math.min(99, (int) (100 - distance))));
    }

    // ------------------------------------------------------------------ ISSUE-6: 门对齐重试

    private void startAlign(Jobs.Job job, BlockPos door) {
        aligning = true;
        alignDoor = door;
        alignTicks = 0;
        BaritoneControl.cancel();
        job.metric("state", "align");
        job.metric("aligningTo", door.toShortString());
    }

    /** 直接把玩家速度指向门方块中心（不经过 Baritone），到位后重新 path。 */
    private void tickAlign(Jobs.Job job, LocalPlayer player, ClientLevel level) {
        alignTicks++;
        if (alignTicks > ALIGN_TIMEOUT_TICKS) {
            failAlign(job, player, "could not align to the door centre in " + ALIGN_TIMEOUT_TICKS + " ticks");
            return;
        }
        double tx = alignDoor.getX() + 0.5;
        double tz = alignDoor.getZ() + 0.5;
        double dx = tx - player.getX();
        double dz = tz - player.getZ();
        double dist = Math.hypot(dx, dz);
        if (dist < 0.35) {
            finishAlign(job, player, level);
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        double speed = 0.17;
        player.setDeltaMovement(dx / dist * speed, player.getDeltaMovement().y, dz / dist * speed);
        player.hurtMarked = true;
        if (player.onGround() && player.horizontalCollision && alignTicks % 10 == 0) {
            player.jumpFromGround();
        }
        job.metric("alignDistance", MathUtil.round2(dist));
        // 对齐期间也算“有进度”，避免外部 watchdog 误判。
        job.progress(5);
    }

    private void finishAlign(Jobs.Job job, LocalPlayer player, ClientLevel level) {
        aligning = false;
        alignAttempted = true;
        alignTicks = 0;
        // 先把门打开（玩家现在就在门中心），再重开同一条 path。
        opened += openNearbyDoors(player, level);
        BaritoneControl.configureForPathing();
        BaritoneControl.start(x, y, z, range);
        lastX = player.getX();
        lastZ = player.getZ();
        lastProgressTick = ticks;
        job.metric("state", "pathing");
        job.metric("alignedTo", alignDoor == null ? "" : alignDoor.toShortString());
        job.metric("doorsOpened", opened);
    }

    private void failAlign(Jobs.Job job, LocalPlayer player, String reason) {
        aligning = false;
        alignAttempted = true;
        BaritoneControl.cancel();
        closeContainerIfOpen();
        job.fail(Errors.BLOCKED, reason + " at " + player.blockPosition().toShortString());
    }

    /** 附近最近的门/栅栏门（关着的优先）；没有就返回 null。 */
    private BlockPos findDoorToAlign(LocalPlayer player, ClientLevel level) {
        BlockPos center = player.blockPosition();
        BlockPos bestClosed = null;
        double bestClosedDistance = Double.MAX_VALUE;
        BlockPos bestOpen = null;
        double bestOpenDistance = Double.MAX_VALUE;
        for (int dx = -ALIGN_RANGE; dx <= ALIGN_RANGE; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -ALIGN_RANGE; dz <= ALIGN_RANGE; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    if (!(id.endsWith("_door") || id.endsWith("_fence_gate"))) {
                        continue;
                    }
                    if (!state.hasProperty(BlockStateProperties.OPEN)) {
                        continue;
                    }
                    double d = player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos));
                    if (d > 16.0) {
                        continue;
                    }
                    if (!state.getValue(BlockStateProperties.OPEN)) {
                        if (d < bestClosedDistance) {
                            bestClosed = pos;
                            bestClosedDistance = d;
                        }
                    } else if (d < bestOpenDistance) {
                        bestOpen = pos;
                        bestOpenDistance = d;
                    }
                }
            }
        }
        return bestClosed != null ? bestClosed : bestOpen;
    }

    // ------------------------------------------------------------------ ISSUE-5: 只开门，不顺手开箱

    private int openNearbyDoors(LocalPlayer player, ClientLevel level) {
        int count = 0;
        BlockPos center = player.blockPosition();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (!state.hasProperty(BlockStateProperties.OPEN) || state.getValue(BlockStateProperties.OPEN)) {
                        continue;
                    }
                    String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    // 模组门不一定继承原版 DoorBlock，id 后缀再兜一层。
                    boolean doorLike = state.getBlock() instanceof DoorBlock
                            || state.getBlock() instanceof FenceGateBlock
                            || state.getBlock() instanceof TrapDoorBlock
                            || blockId.endsWith("_door")
                            || blockId.endsWith("_fence_gate")
                            || blockId.endsWith("_trapdoor");
                    // 默认只开“门”这类通道方块：BarrelBlock 等也有 OPEN 属性，旧逻辑会把箱子一起打开。
                    if (!doorLike && !openContainers) {
                        continue;
                    }
                    if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > 16.0) {
                        continue;
                    }
                    Direction face = Direction.getNearest(
                            player.getX() - pos.getX(),
                            player.getEyeY() - (pos.getY() + 0.5),
                            player.getZ() - pos.getZ());
                    BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
                    Minecraft.getInstance().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                    player.swing(InteractionHand.MAIN_HAND);
                    if (doorLike) {
                        count++;
                    } else {
                        containersOpened++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * ISSUE-5: 关闭误开的容器界面。这里不调 {@code Containers.close()} 是因为本方法已经在客户端线程
     * 的 tick 里执行，再 submit 一个 MainThread 任务只会晚一 tick；直接 closeContainer + setScreen(null)。
     *
     * @return true 表示确实关掉了什么
     */
    private boolean closeContainerIfOpen() {
        try {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null) {
                return false;
            }
            boolean menuOpen = player.containerMenu != null && player.containerMenu != player.inventoryMenu;
            boolean screenOpen = mc.screen instanceof AbstractContainerScreen<?>;
            if (!menuOpen && !screenOpen) {
                return false;
            }
            player.closeContainer();
            if (screenOpen) {
                mc.setScreen(null);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void cancel(Jobs.Job job) {
        BaritoneControl.cancel();
        closeContainerIfOpen();
    }
}
