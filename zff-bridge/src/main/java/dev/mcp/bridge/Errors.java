package dev.mcp.bridge;

/** Stable error taxonomy required by the MCP contract. */
public final class Errors {
    public static final String TIMEOUT = "timeout";
    public static final String DISCONNECTED = "disconnected";
    public static final String NOT_LOADED = "not_loaded";
    public static final String NO_PERMISSION = "no_permission";
    public static final String UNSUPPORTED = "unsupported";
    public static final String INTERNAL = "internal";
    // Round-3 reasons: always tell the caller *why* an action failed.
    public static final String OUT_OF_REACH = "out_of_reach";
    public static final String TOOL_BROKEN = "tool_broken";
    /** The held/available tool cannot produce drops for this block (e.g. stone pickaxe on diamond ore). */
    public static final String WRONG_TOOL = "wrong_tool";
    public static final String NO_PROGRESS = "no_progress";
    public static final String UNBREAKABLE = "unbreakable";
    public static final String PROTECTED = "protected";
    public static final String WATER_RISK = "water_risk";
    public static final String SERVER_REJECTED = "server_rejected";
    /**
     * 2026-11-01：服务端没有装本 mod（或协议不匹配）时，服务端通道不存在。
     * 以前会直接把 `UnsupportedOperationException: Payload … may not be sent to the server!` 抛给 LLM，
     * 现在收敛成这个错误码 + 一句人话，并明确"本地 store 仍然可用"。
     */
    public static final String NO_SERVER_HALF = "no_server_half";
    public static final String BLOCKED = "blocked";
    public static final String BUDGET_EXCEEDED = "budget_exceeded";
    public static final String STRAIGHT_DOWN_FORBIDDEN = "straight_down_forbidden";
    public static final String ENTITY_NOT_FOUND = "entity_not_found";
    // Ph4 Phase 0: reliability reasons.
    /** No progress for the watchdog window (P1-8). */
    public static final String STALLED = "stalled";
    /** A block the client saw break reappeared, or the player collides with an air bubble (P0-1). */
    public static final String GHOST_BLOCK = "ghost_block";
    /** The chosen tool has fewer than 10 uses left and the target block is valuable (P1-5). */
    public static final String TOOL_LOW = "tool_low";
    /** The server moved the player back (&gt; 1 block in a tick): client/server desync. */
    public static final String SYNC_JUMP = "sync_jump";
    /** delete_item dropped the stack but the player picked it up again before getting away. */
    public static final String REPICKED = "repicked";

    private Errors() {
    }

    public static final class BridgeException extends RuntimeException {
        public final String code;
        public final boolean partial;

        public BridgeException(String code, String message) {
            this(code, message, false);
        }

        public BridgeException(String code, String message, boolean partial) {
            super(message);
            this.code = code;
            this.partial = partial;
        }

        public BridgeException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.partial = false;
        }
    }

    public static BridgeException timeout(String message) {
        return new BridgeException(TIMEOUT, message, true);
    }

    public static BridgeException notLoaded(String message) {
        return new BridgeException(NOT_LOADED, message);
    }

    public static BridgeException unsupported(String message) {
        return new BridgeException(UNSUPPORTED, message);
    }

    public static BridgeException internal(String message) {
        return new BridgeException(INTERNAL, message);
    }

    public static BridgeException internal(String message, Throwable cause) {
        return new BridgeException(INTERNAL, message, cause);
    }
}
