package dev.mcp.bridge;

import com.google.gson.JsonObject;

/**
 * Ph6 M3: which half of the system is actually active for this client.
 *
 * <p>The client never needs the server half to work: the loopback bridge, the 84 tools, the chat log
 * and the environment sampler are all client-side. The server half only adds the shared
 * {@code SavedData} memory/todo, the {@code /zff} commands and the server-side sampler. So the state is
 * a capability report, not a startup condition.</p>
 *
 * <ul>
 *   <li>{@link State#SERVER} &mdash; a server half with the same protocol answered the negotiation.</li>
 *   <li>{@link State#LOCAL} &mdash; no server half (any server, including the player's own).</li>
 *   <li>{@link State#MISMATCH} &mdash; a server half exists but speaks another protocol / version.</li>
 *   <li>{@link State#UNKNOWN} &mdash; the negotiation has not finished yet (first ~5 s after joining).</li>
 * </ul>
 *
 * <p>Writes are only done by the client thread and by the negotiation probe thread; readers are HTTP
 * threads, so every field is volatile and no lock is needed.</p>
 */
public final class Backend {
    private Backend() {
    }

    public enum State {
        UNKNOWN, SERVER, LOCAL, MISMATCH
    }

    public static volatile State state = State.UNKNOWN;
    /** Version string the server half reported (null when there is none). */
    public static volatile String serverVersion;
    public static volatile int serverProtocol;
    public static volatile String serverId;
    /** Short human-readable reason for the current state; useful in bridge_info/get_state. */
    public static volatile String reason = "not negotiated yet";
    public static volatile long negotiatedAt;

    public static void reset() {
        state = State.UNKNOWN;
        serverVersion = null;
        serverProtocol = 0;
        serverId = null;
        reason = "left the world";
        negotiatedAt = 0;
    }

    /** Called when a {@code mcpbridge:hello_ack} arrives (client thread). */
    public static void onAck(String modVersion, int protocol, String id) {
        serverVersion = modVersion;
        serverProtocol = protocol;
        serverId = id;
        negotiatedAt = System.currentTimeMillis();
        if (protocol == BridgeConfig.PROTOCOL) {
            state = State.SERVER;
            reason = "hello_ack protocol=" + protocol;
        } else {
            state = State.MISMATCH;
            reason = "protocol mismatch: server=" + protocol + " client=" + BridgeConfig.PROTOCOL;
        }
    }

    /** No ack arrived, but the server answered an rpc probe: the mod is there, just not our protocol. */
    public static void onProbeAnswered(String modVersion, int protocol) {
        if (state == State.SERVER) {
            return;
        }
        serverVersion = modVersion;
        serverProtocol = protocol;
        negotiatedAt = System.currentTimeMillis();
        state = protocol == BridgeConfig.PROTOCOL ? State.SERVER : State.MISMATCH;
        reason = protocol > 0
                ? "answered rpc, no hello_ack (protocol " + protocol + ")"
                : "answered rpc without a version (old server half)";
    }

    /** Nothing answered: plain client-only mode. */
    public static void onNoServer(String why) {
        if (state == State.SERVER || state == State.MISMATCH) {
            return;
        }
        state = State.LOCAL;
        serverVersion = null;
        serverProtocol = 0;
        negotiatedAt = System.currentTimeMillis();
        reason = why;
    }

    /** {@code server} | {@code local} | {@code local(mismatch)} | {@code unknown}. */
    public static String label() {
        return switch (state) {
            case SERVER -> "server";
            case MISMATCH -> "local(mismatch)";
            case LOCAL -> "local";
            default -> "unknown";
        };
    }

    public static JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("backend", label());
        o.addProperty("state", state.name().toLowerCase());
        o.addProperty("clientVersion", BridgeConfig.VERSION);
        o.addProperty("clientProtocol", BridgeConfig.PROTOCOL);
        if (serverVersion != null) {
            o.addProperty("serverVersion", serverVersion);
        }
        if (serverProtocol > 0) {
            o.addProperty("serverProtocol", serverProtocol);
        }
        if (serverId != null) {
            o.addProperty("serverId", serverId);
        }
        o.addProperty("reason", reason);
        o.addProperty("negotiatedAt", negotiatedAt);
        return o;
    }
}
