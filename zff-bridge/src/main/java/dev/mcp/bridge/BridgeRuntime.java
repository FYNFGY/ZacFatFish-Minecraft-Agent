package dev.mcp.bridge;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the loopback server, the job manager and the registration heartbeat for one client session. */
public final class BridgeRuntime {
    private static final Logger LOG = LoggerFactory.getLogger("mcpbridge");
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean STOPPING = new AtomicBoolean();

    public static volatile long startedAt;
    public static volatile Path stateDir;
    public static volatile Path screenshotDir;
    /** The game directory; the mod store lives under {@code <gameDir>/zff}. */
    public static volatile Path gameDir;
    public static volatile Jobs jobs;
    /** Updated on the client thread; HTTP threads only read these primitives. */
    public static volatile boolean inWorld;
    public static volatile String playerName;
    public static volatile String dimension;
    private static volatile InstanceRegistry registry;
    private static volatile HttpBridge http;

    private BridgeRuntime() {
    }

    public static void start() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        startedAt = System.currentTimeMillis();
        try {
            Minecraft mc = Minecraft.getInstance();
            Path gameDir = mc.gameDirectory.toPath().toAbsolutePath().normalize();
            BridgeRuntime.gameDir = gameDir;
            stateDir = BridgeConfig.stateDir(gameDir);
            screenshotDir = BridgeConfig.screenshotDir(stateDir);
            Files.createDirectories(screenshotDir);
            jobs = new Jobs();
            String token = newToken();
            // Ph6 M1: chat has to leave the game immediately, not on the next agent poll.
            ChatPush.start(BridgeConfig.chatPushUrl(), token);
            // Ph7 M1-3: fall / damage / hunger / inventory_full edge events feed the interrupt engine.
            EventPush.start(BridgeConfig.eventPushUrl(), token);
            HttpBridge bridge = new HttpBridge(token, jobs);
            int port = bridge.start(BridgeConfig.requestedPort());
            http = bridge;
            try {
                registry = InstanceRegistry.start(stateDir, port, token, gameDir, BridgeConfig.MC_VERSION);
            } catch (Throwable t) {
                LOG.error("could not write the MCP registration file: {}", t.toString());
            }
            Runtime.getRuntime().addShutdownHook(new Thread(BridgeRuntime::stop, "mcpbridge-shutdown"));
            LOG.info("MCP bridge listening on 127.0.0.1:{} (protocol {}, version {})", port,
                    BridgeConfig.PROTOCOL, BridgeConfig.VERSION);
            if (registry != null) {
                LOG.info("registration file: {}", registry.file());
            }
        } catch (Throwable t) {
            LOG.error("MCP bridge failed to start: {}", t.toString());
        }
    }

    public static void tick() {
        try {
            updateCache();
            // 2026-11-02 真机：窗口一失焦，单人游戏就暂停；暂停后 MainThread.submit/StopAll 都不执行，
            // 自动化会看起来"卡住"。客户端由 mod 托管时必须保持运行（玩家自己的实例不走这条路径）。
            Minecraft client = Minecraft.getInstance();
            if (client != null && client.options != null) {
                client.options.pauseOnLostFocus = false;
            }
            // Ph6 M1: the environment is sampled here, in the client, and never by an agent.
            EnvSampler.tick();
            EnvInfo.tick();
            ModStore.tick();
            Ghosts.tick(Minecraft.getInstance().player);
            DamageWatch.tick();
            EventPush.tick(Minecraft.getInstance().player);
            AutoRespawn.tick();
            TickScheduler.tick();
            Jobs manager = jobs;
            if (manager != null) {
                manager.tickAll();
            }
            ViewGuard.tick(Minecraft.getInstance().player, manager);
        } catch (Throwable t) {
            LOG.warn("bridge tick failed: {}", t.toString());
        }
    }

    /** Runs on the client thread; keeps /info safe for HTTP threads. */
    private static void updateCache() {
        Minecraft mc = Minecraft.getInstance();
        boolean in = mc != null && mc.player != null && mc.level != null;
        inWorld = in;
        if (in) {
            playerName = mc.player.getName().getString();
            dimension = mc.level.dimension().location().toString();
        } else {
            playerName = null;
            dimension = null;
        }
    }

    public static void onLoggingOut() {
        LOG.info("client left the world; failing in-flight jobs");
        Backend.reset();
        Jobs manager = jobs;
        if (manager != null) {
            manager.failAll(Errors.DISCONNECTED, "the client disconnected from the world");
        }
        BaritoneControl.cancel();
        ScreenshotCapture.failAll("client disconnected");
    }

    public static void stop() {
        if (!STOPPING.compareAndSet(false, true)) {
            return;
        }
        // Shutdown hooks run after FML may have closed the mod class loader. Do not
        // touch classes that may not have been loaded (e.g. ScreenshotCapture); keep
        // each step isolated so one failure does not leave the registration file behind.
        try {
            Jobs manager = jobs;
            if (manager != null) {
                manager.failAll(Errors.DISCONNECTED, "the client is shutting down");
            }
        } catch (Throwable t) {
            LOG.warn("shutdown: jobs cleanup failed: {}", t.toString());
        }
        try {
            HttpBridge bridge = http;
            if (bridge != null) {
                bridge.stop();
            }
        } catch (Throwable t) {
            LOG.warn("shutdown: HTTP stop failed: {}", t.toString());
        }
        try {
            InstanceRegistry r = registry;
            if (r != null) {
                r.close();
            }
        } catch (Throwable t) {
            LOG.warn("shutdown: registration delete failed: {}", t.toString());
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
