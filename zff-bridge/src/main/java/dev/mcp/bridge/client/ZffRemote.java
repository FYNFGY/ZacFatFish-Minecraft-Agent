package dev.mcp.bridge.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcp.bridge.Backend;
import dev.mcp.bridge.Errors;
import dev.mcp.bridge.MainThread;
import dev.mcp.bridge.ZffPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client side of the memory/todo RPC (Ph5 T2/T4): the loopback HTTP tools call
 * {@link #call(String, JsonObject, long)}, which sends one {@link ZffPayloads.Request} over the mod
 * channel and waits for the matching {@link ZffPayloads.Response} from the server.
 *
 * <p>No socket is opened for this: the request rides the normal play channel, so the dedicated server
 * stays reachable only through Minecraft's own port.</p>
 */
public final class ZffRemote {
    private static final Map<String, CompletableFuture<JsonObject>> PENDING = new ConcurrentHashMap<>();
    private static final AtomicLong IDS = new AtomicLong();

    private ZffRemote() {
    }

    /** Sends one op and blocks the calling (HTTP) thread until the server answers. */
    public static JsonObject call(String op, JsonObject arg, long timeoutMs) {
        // 🔴 2026-11-01 真机：玩家连的服务器**没装**本 mod（比如 zmcp3.rhymc.com）时，服务端通道不存在，
        //   `PacketDistributor.sendToServer` 会抛 `UnsupportedOperationException: Payload mcpbridge:rpc_request
        //   may not be sent to the server!`。以前这条 StackTrace 会被原样丢给 LLM，看起来像插件崩了，
        //   实际只是"这个服务器没有服务端半边"。这里提前拦截并给出明确错误码 + 人话。
        //   注意：协商探针在 UNKNOWN 阶段仍要发包（它正是用来发现"有没有服务端"的），所以只拦 LOCAL/MISMATCH。
        if (Backend.state == Backend.State.LOCAL || Backend.state == Backend.State.MISMATCH) {
            throw new Errors.BridgeException(Errors.NO_SERVER_HALF,
                    "当前服务器没有可用的 ZFF Bridge 服务端半边（backend=" + Backend.state
                            + "）：服务端共享记忆 / 待办 / /zff 指令不可用；本地 cr store 与 memory 文件仍可读写。");
        }
        String id = "r" + IDS.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 6);
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        PENDING.put(id, future);
        try {
            // Sending must happen on the client thread; this only enqueues the packet, it does not wait
            // for the answer.
            MainThread.await(MainThread.submit("zff_rpc", () -> {
                LocalPlayer player = Minecraft.getInstance().player;
                if (player == null || player.connection == null) {
                    throw Errors.notLoaded("the player is not in a world");
                }
                try {
                    PacketDistributor.sendToServer(
                            new ZffPayloads.Request(id, op, arg == null ? "{}" : arg.toString()));
                } catch (UnsupportedOperationException e) {
                    // 通道没协商上（服务器没装 / 版本不匹配 / 单机但配置阶段没跑到）
                    Backend.onNoServer("channel not registered on this connection: " + e.getMessage());
                    throw new Errors.BridgeException(Errors.NO_SERVER_HALF,
                            "这个连接上没有注册 mcpbridge 通道（服务器没装本 mod 或版本不匹配），"
                                    + "服务端共享记忆/待办不可用；本地 cr store 与 memory 文件仍然可用。");
                }
                return true;
            }), 5_000);
            return future.get(Math.max(500, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw Errors.timeout("the server did not answer '" + op + "' in " + timeoutMs + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Errors.internal("interrupted while waiting for the server", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Errors.BridgeException be) {
                throw be;
            }
            throw Errors.internal("server rpc failed: " + (cause == null ? "?" : cause), cause);
        } finally {
            PENDING.remove(id);
        }
    }

    /** Called from the payload handler (client thread) when the server replies. */
    public static void complete(ZffPayloads.Response response) {
        CompletableFuture<JsonObject> future = PENDING.get(response.id());
        if (future == null) {
            return; // a late answer for a request that already timed out
        }
        try {
            JsonObject json = JsonParser.parseString(response.json()).getAsJsonObject();
            future.complete(json);
        } catch (RuntimeException e) {
            future.completeExceptionally(Errors.internal("the server sent invalid JSON: " + response.json()));
        }
    }
}
