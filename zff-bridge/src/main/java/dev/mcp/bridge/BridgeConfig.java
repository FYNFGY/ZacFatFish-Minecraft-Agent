package dev.mcp.bridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Runtime configuration. Every path is handled with Path; nothing is concatenated into shell text. */
public final class BridgeConfig {
    public static final String MOD_ID = "mcpbridge";
    public static final String VERSION = "0.2.0";
    /**
     * Channel/feature protocol version (Ph6 M3). A server whose {@code PROTOCOL} differs is reported as
     * {@code backend=local(mismatch)} and no server-only feature is used. Bumping this means the two
     * halves no longer agree on the payload set, not that they cannot talk at all.
     */
    public static final int PROTOCOL = 2;
    public static final String MC_VERSION = "1.21.1";
    public static final String LOADER = "NeoForge";

    public static final int HEARTBEAT_MS = 5_000;
    public static final int EXPIRE_MS = 20_000;
    public static final int MAX_RESPONSE_BYTES = 30_000;
    public static final int MAX_BODY_BYTES = 1_048_576;

    private BridgeConfig() {
    }

    public static Path stateDir(Path gameDir) {
        String explicit = firstNonBlank(System.getProperty("mcp.bridge.state"), System.getenv("MCP_BRIDGE_STATE"));
        if (explicit != null) {
            return Paths.get(explicit).toAbsolutePath().normalize();
        }
        String local = System.getenv("LOCALAPPDATA");
        if (local == null || local.isBlank()) {
            local = System.getenv("XDG_STATE_HOME");
        }
        Path base;
        if (local != null && !local.isBlank() && !local.equals(System.getenv("XDG_STATE_HOME"))) {
            base = Paths.get(local, "state", "headlessmc-mcp");
        } else if (local != null && !local.isBlank()) {
            base = Paths.get(local, "headlessmc-mcp");
        } else {
            base = Paths.get(System.getProperty("user.home", "."), ".local", "state", "headlessmc-mcp");
        }
        try {
            Files.createDirectories(base);
            Path probe = Files.createTempFile(base, ".write-probe", ".tmp");
            Files.deleteIfExists(probe);
            return base.toAbsolutePath().normalize();
        } catch (IOException | RuntimeException e) {
            // Never fail the client because the preferred state directory is not writable.
            Path fallback = gameDir.resolve(".mcpbridge").toAbsolutePath().normalize();
            try {
                Files.createDirectories(fallback);
            } catch (IOException ignored) {
                // callers will report the failure when they try to write the registration file
            }
            return fallback;
        }
    }

    public static int requestedPort() {
        String v = firstNonBlank(System.getProperty("mcp.bridge.port"), System.getenv("MCP_BRIDGE_PORT"));
        if (v == null) {
            return 0;
        }
        try {
            int p = Integer.parseInt(v.trim());
            return p >= 0 && p <= 65535 ? p : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Where chat events are pushed (Ph6 M1 / §2.8): the Node daemon listens on 57611 and forwards to
     * the MCP server. Loopback only; the request carries the same bearer token as the bridge.
     */
    public static String chatPushUrl() {
        String explicit = firstNonBlank(System.getProperty("mcp.chat.pushUrl"), System.getenv("MCP_CHAT_PUSH_URL"));
        return explicit != null ? explicit : "http://127.0.0.1:57611/chat_event";
    }

    /** Ph7 M1-3: where edge events are pushed; defaults to the chat URL with a different endpoint. */
    public static String eventPushUrl() {
        String explicit = firstNonBlank(System.getProperty("mcp.event.pushUrl"), System.getenv("MCP_EVENT_PUSH_URL"));
        if (explicit != null) {
            return explicit;
        }
        String chat = chatPushUrl();
        if (chat.endsWith("/chat_event")) {
            return chat.substring(0, chat.length() - "/chat_event".length()) + "/event";
        }
        return chat;
    }

    public static Path instanceDir(Path stateDir) {
        return stateDir.resolve("instances");
    }

    /** Ph6: the mod's own read-only store ({@code <gameDir>/zff/mod-store.json}). */
    public static Path modStoreDir(Path gameDir) {
        return gameDir.resolve("zff");
    }

    public static Path screenshotDir(Path stateDir) {
        return stateDir.resolve("screenshots");
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
