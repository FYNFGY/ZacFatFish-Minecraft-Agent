package dev.mcp.bridge.client;

import com.google.gson.JsonObject;
import dev.mcp.bridge.Backend;
import dev.mcp.bridge.BridgeRuntime;
import dev.mcp.bridge.ChatLog;
import dev.mcp.bridge.EventJournal;
import dev.mcp.bridge.ScreenshotCapture;
import dev.mcp.bridge.StopAll;
import dev.mcp.bridge.ZffConfig;
import dev.mcp.bridge.ZffHooks;
import dev.mcp.bridge.ZffPayloads;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The client half of the mod (Ph5 T1). Nothing in this package is ever loaded on a dedicated server:
 * {@link dev.mcp.bridge.McpBridge} only references this class behind an {@code FMLEnvironment.dist}
 * check, and every other common class talks to the client through {@link ZffHooks}.
 *
 * <p>Responsibilities: start the loopback HTTP bridge, drive the job tick loop, feed chat/damage
 * events, run the job watchdog ({@link ZffWatchdog}) and answer the server's memory/todo replies.</p>
 */
public final class ZffClient implements ZffHooks.ClientSide {
    private ZffClient() {
    }

    public static void init(IEventBus modBus) {
        ZffHooks.clientSide = new ZffClient();
        modBus.addListener(ZffClient::clientSetup);
        NeoForge.EVENT_BUS.addListener(ZffClient::clientTick);
        NeoForge.EVENT_BUS.addListener(ZffClient::chat);
        NeoForge.EVENT_BUS.addListener(ZffClient::loggingOut);
        NeoForge.EVENT_BUS.addListener(ZffClient::loggingIn);
        NeoForge.EVENT_BUS.addListener(ZffClient::renderGui);
        System.out.println("[zff] client-side initialised (loopback bridge + job watchdog)");
    }

    private static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ZffConfig.load();
            BridgeRuntime.start();
        });
    }

    private static void clientTick(ClientTickEvent.Post event) {
        BridgeRuntime.tick();
        ZffWatchdog.tick();
        negotiateTick();
    }

    // ------------------------------------------------------------------ Ph6 M3 negotiation

    private static volatile long joinedAt;
    private static volatile boolean probeInFlight;
    private static volatile int probeAttempts;

    /**
     * The ack is expected within a second of joining. When it never arrives the server half is either
     * absent or older than this protocol, and the client has to say which — but it must never block the
     * client thread doing it, so the probe runs on its own thread and twice at most.
     */
    private static void negotiateTick() {
        if (Backend.state != Backend.State.UNKNOWN) {
            return;
        }
        if (!BridgeRuntime.inWorld) {
            return;
        }
        long since = joinedAt == 0 ? 0 : System.currentTimeMillis() - joinedAt;
        long wait = probeAttempts == 0 ? 4_000 : 8_000;
        if (since < wait || probeInFlight || probeAttempts >= 2) {
            return;
        }
        probeInFlight = true;
        probeAttempts++;
        Thread t = new Thread(() -> {
            try {
                JsonObject answer = dev.mcp.bridge.client.ZffRemote.call("status", new JsonObject(), 3_000);
                boolean ok = answer != null && (!answer.has("ok") || answer.get("ok").getAsBoolean());
                if (ok) {
                    String version = answer.has("modVersion") && !answer.get("modVersion").isJsonNull()
                            ? answer.get("modVersion").getAsString() : null;
                    int protocol = answer.has("protocol") && !answer.get("protocol").isJsonNull()
                            ? answer.get("protocol").getAsInt() : 0;
                    Backend.onProbeAnswered(version, protocol);
                } else {
                    Backend.onProbeAnswered(null, 0);
                }
            } catch (Throwable t2) {
                if (probeAttempts >= 2) {
                    Backend.onNoServer("no server half answered the negotiation probe (" + t2.getClass().getSimpleName() + ")");
                }
            } finally {
                probeInFlight = false;
            }
        }, "mcpbridge-negotiate");
        t.setDaemon(true);
        t.start();
    }

    private static void chat(ClientChatReceivedEvent event) {
        ChatLog.onChat(event);
    }

    private static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        BridgeRuntime.onLoggingOut();
    }

    /**
     * Tell the server that this client runs the mod. It is the opt-in for every server -> client
     * payload: without it the server never sends an {@code mcpbridge:*} packet, so players who do not
     * install the mod never see an unknown-payload/channel error.
     */
    private static void loggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        joinedAt = System.currentTimeMillis();
        probeAttempts = 0;
        probeInFlight = false;
        Backend.state = Backend.State.UNKNOWN;
        Backend.reason = "waiting for hello_ack";
        try {
            // Ph6 M3: the protocol rides inside the version string ("0.2.0+p2") so the channel set stays
            // identical to older builds (a new channel would make both halves incompatible).
            PacketDistributor.sendToServer(new ZffPayloads.Hello(
                    dev.mcp.bridge.BridgeConfig.VERSION + "+p" + dev.mcp.bridge.BridgeConfig.PROTOCOL));
            System.out.println("[zff] announced the mod to the server (protocol "
                    + dev.mcp.bridge.BridgeConfig.PROTOCOL + ")");
        } catch (Throwable t) {
            System.out.println("[zff] could not announce the mod: " + t);
        }
    }

    private static void renderGui(RenderGuiEvent.Post event) {
        ScreenshotCapture.tick(event);
    }

    // ------------------------------------------------------------------ channel (server -> client)

    @Override
    public void onResponse(ZffPayloads.Response payload, IPayloadContext context) {
        // Ph6 M3: the negotiation rides on the rpc_response channel (see ZffPayloads.Hello); an id that
        // starts with "hello:" is the server half telling us its version, not the answer to a request.
        String id = payload.id() == null ? "" : payload.id();
        if (id.startsWith("hello:")) {
            try {
                JsonObject ack = com.google.gson.JsonParser.parseString(payload.json()).getAsJsonObject();
                String version = ack.has("modVersion") ? ack.get("modVersion").getAsString() : null;
                int protocol = ack.has("protocol") ? ack.get("protocol").getAsInt() : 0;
                String serverId = ack.has("serverId") ? ack.get("serverId").getAsString() : null;
                Backend.onAck(version, protocol, serverId);
                System.out.println("[zff] server half: " + version + " protocol=" + protocol
                        + " world=" + serverId + " -> backend=" + Backend.label());
            } catch (Throwable t) {
                System.out.println("[zff] could not read the hello ack: " + t);
            }
            return;
        }
        ZffRemote.complete(payload);
    }

    /**
     * The admin ran {@code /zff stop} on the server: cancel every job here, where the jobs actually
     * live, and publish the event so the MCP side (SR) learns about it through {@code wait_events}.
     */
    @Override
    public void onStop(ZffPayloads.Stop payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            JsonObject out = StopAll.run(payload.reason());
            JsonObject event = new JsonObject();
            event.addProperty("reason", payload.reason());
            event.addProperty("by", payload.by() == null ? "command" : payload.by());
            if (out.has("jobsCancelled")) {
                event.addProperty("jobsCancelled", out.get("jobsCancelled").getAsInt());
            }
            if (out.has("baritoneStopped")) {
                event.addProperty("baritoneStopped", out.get("baritoneStopped").getAsBoolean());
            }
            if (out.has("tasksDropped")) {
                event.addProperty("tasksDropped", out.get("tasksDropped").getAsInt());
            }
            event.addProperty("source", "mod");
            EventJournal.push("zff_stop", event);
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("[zff] stop: " + payload.reason()), false);
            }
        });
    }
}
