package dev.mcp.bridge;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything the mod does on the <b>server</b> side (Ph5 T1/T2/T4/T5):
 * <ul>
 *   <li>the {@code /zff} command tree (status, stop, reload, memory, todo),</li>
 *   <li>the mod channel handlers (memory/todo RPC, {@code /zff stop} broadcast),</li>
 *   <li>the server side of the watchdog (per-player stillness sampling).</li>
 * </ul>
 * This class and everything it touches is free of {@code net.minecraft.client.*} imports: it is loaded
 * on a dedicated server, where no client class exists at all.
 */
public final class ZffServer {
    private static final String LOG_PREFIX = "[zff]";

    /** Per player: last position that was clearly different from the previous sample. */
    private static final Map<UUID, Sample> SAMPLES = new ConcurrentHashMap<>();
    private static volatile long lastSampleAt;

    private ZffServer() {
    }

    private record Sample(double x, double y, double z, long movedAt) {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(ZffServer::registerPayloads);
        NeoForge.EVENT_BUS.addListener(ZffServer::registerCommands);
        NeoForge.EVENT_BUS.addListener(ZffServer::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ZffServer::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ZffServer::onServerTick);
        NeoForge.EVENT_BUS.addListener(ZffServer::onPlayerLoggedOut);
        System.out.println(LOG_PREFIX + " server-side initialised: commands=/zff storage="
                + ZffSavedData.NAME + ".dat watchdogInMod=" + ZffConfig.watchdogInMod);
    }

    // ------------------------------------------------------------------ channel

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // 🔴 Ph6 M3 真机教训：`PayloadRegistrar.optional()` 走的是**拷贝构造**（javap 可见
        // `new PayloadRegistrar(this)` 后置 optional=true），返回的是**新实例**。以前写成
        // `var registrar = event.registrar("1"); registrar.optional();` —— 返回值被丢掉，通道仍然
        // 是"必需"，于是「服务端没装我们的 mod」时 NeoForge 直接踢人：
        //   Channel [mcpbridge:hello] failed to connect ... Incompatible client! Please use NeoForge ...
        // 必须把 optional() 串在赋值里，别丢返回值。
        var registrar = event.registrar("1").optional();
        registrar.playToServer(ZffPayloads.Hello.TYPE, ZffPayloads.Hello.CODEC, ZffServer::onHello);
        registrar.playToServer(ZffPayloads.Request.TYPE, ZffPayloads.Request.CODEC, ZffServer::onRequest);
        registrar.playToClient(ZffPayloads.Response.TYPE, ZffPayloads.Response.CODEC, ZffServer::onResponse);
        registrar.playToClient(ZffPayloads.Stop.TYPE, ZffPayloads.Stop.CODEC, ZffServer::onStop);
    }

    /**
     * Players whose client announced the mod (they sent {@link ZffPayloads.Hello}). Only they ever
     * receive a {@code mcpbridge:*} packet, so a client without the mod is never exposed to our
     * channel at all - no "unknown payload"/channel errors on their side.
     */
    private static final java.util.Set<java.util.UUID> KNOWN_CLIENTS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void onHello(ZffPayloads.Hello hello, IPayloadContext context) {
        try {
            var player = context.player();
            if (player != null) {
                KNOWN_CLIENTS.add(player.getUUID());
                System.out.println(LOG_PREFIX + " client announced the mod: " + player.getName().getString()
                        + " version=" + hello.version() + " (known clients=" + KNOWN_CLIENTS.size() + ")");
                // Ph6 M3: answer on the *existing* rpc_response channel (a new channel would make the
                // two halves incompatible, see ZffPayloads.Hello). The client recognises the "hello:"
                // id prefix; an old server half never sends it, which is exactly how the client tells
                // "same protocol" from "old server half".
                JsonObject ack = new JsonObject();
                ack.addProperty("ok", true);
                ack.addProperty("modVersion", BridgeConfig.VERSION);
                ack.addProperty("protocol", BridgeConfig.PROTOCOL);
                ack.addProperty("serverId", serverId(player));
                context.reply(new ZffPayloads.Response("hello:" + BridgeConfig.VERSION, true, ack.toString()));
            }
        } catch (Throwable t) {
            System.out.println(LOG_PREFIX + " hello failed: " + t);
        }
    }

    /** True when this player's client runs the mod (it told us so after joining). */
    public static boolean isModdedClient(ServerPlayer player) {
        return player != null && KNOWN_CLIENTS.contains(player.getUUID());
    }

    /**
     * Ph6 M3 fallback probe: a client that never saw the hello ack asks over the RPC channel instead.
     * An old server half answers with "unknown op", which still proves it exists.
     */
    private static JsonObject negotiateStatus(net.minecraft.world.entity.player.Player player) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("modVersion", BridgeConfig.VERSION);
        o.addProperty("protocol", BridgeConfig.PROTOCOL);
        o.addProperty("serverId", serverId(player));
        o.addProperty("sameProtocol", true);
        return o;
    }

    private static String serverId(net.minecraft.world.entity.player.Player player) {
        try {
            MinecraftServer server = player == null ? null : player.getServer();
            if (server == null) {
                return "unknown";
            }
            return server.getWorldData().getLevelName();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** Client asks for memory/todo/status; answer on the server thread and reply on the same channel. */
    private static void onRequest(ZffPayloads.Request request, IPayloadContext context) {
        context.enqueueWork(() -> {
            JsonObject answer;
            try {
                answer = handle(request.op(), request.arg(), context);
            } catch (Throwable t) {
                answer = new JsonObject();
                answer.addProperty("ok", false);
                answer.addProperty("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            context.reply(new ZffPayloads.Response(request.id(), true, answer.toString()));
        });
    }

    /**
     * Runs on the server thread. Ops: {@code memory.*}, {@code todo.*}, {@code status}.
     * The argument is a JSON object so a single field is enough for every op.
     */
    private static JsonObject handle(String op, String arg, IPayloadContext context) {
        JsonObject in = parse(arg);
        String name = op == null ? "" : op.trim().toLowerCase();
        var player = context.player();
        MinecraftServer server = player.getServer();
        if (server == null) {
            JsonObject o = new JsonObject();
            o.addProperty("ok", false);
            o.addProperty("error", "no server");
            return o;
        }
        ZffSavedData data = ZffSavedData.get(server);
        return switch (name) {
            case "memory.set" -> data.memorySet(
                    str(in, "key"), str(in, "value"), str(in, "role"), ZffSavedData.parseTags(str(in, "tags")));
            case "memory.get" -> data.memoryGet(str(in, "key"));
            case "memory.list" -> data.memoryList(str(in, "tag"));
            case "memory.forget" -> data.memoryForget(str(in, "key"));
            case "todo.add" -> data.todoAdd(str(in, "title"), num(in, "priority", 5), str(in, "spec"), str(in, "note"));
            case "todo.list" -> data.todoList();
            case "todo.done" -> data.todoDone(str(in, "id"));
            case "todo.update" -> data.todoUpdate(str(in, "id"), str(in, "status"), str(in, "note"));
            case "todo.clear" -> data.todoClear();
            case "status" -> status(server, data);
            case "hello", "negotiate" -> negotiateStatus(player);
            default -> {
                JsonObject o = new JsonObject();
                o.addProperty("ok", false);
                o.addProperty("error", "unknown op: " + name);
                yield o;
            }
        };
    }

    private static void onResponse(ZffPayloads.Response response, IPayloadContext context) {
        var hook = ZffHooks.clientSide;
        if (hook != null) {
            hook.onResponse(response, context);
        }
    }

    private static void onStop(ZffPayloads.Stop stop, IPayloadContext context) {
        var hook = ZffHooks.clientSide;
        if (hook != null) {
            hook.onStop(stop, context);
        }
    }

    // ------------------------------------------------------------------ commands

    private static void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("zff")
                .then(Commands.literal("status").executes(ZffServer::cmdStatus))
                .then(Commands.literal("reload").requires(src -> src.hasPermission(2)).executes(ZffServer::cmdReload))
                .then(Commands.literal("stop").requires(src -> src.hasPermission(2)).executes(ZffServer::cmdStop))
                .then(Commands.literal("memory")
                        .then(Commands.literal("set").requires(src -> src.hasPermission(2))
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> cmdMemorySet(ctx, "sr")))))
                        .then(Commands.literal("get")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .executes(ZffServer::cmdMemoryGet)))
                        .then(Commands.literal("list").executes(ZffServer::cmdMemoryList))
                        .then(Commands.literal("forget").requires(src -> src.hasPermission(2))
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .executes(ZffServer::cmdMemoryForget))))
                .then(Commands.literal("todo")
                        .then(Commands.literal("add").requires(src -> src.hasPermission(2))
                                .then(Commands.argument("title", StringArgumentType.greedyString())
                                        .executes(ZffServer::cmdTodoAdd)))
                        .then(Commands.literal("list").executes(ZffServer::cmdTodoList))
                        .then(Commands.literal("done").requires(src -> src.hasPermission(2))
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(ZffServer::cmdTodoDone)))
                        .then(Commands.literal("clear").requires(src -> src.hasPermission(2))
                                .executes(ZffServer::cmdTodoClear)));
        dispatcher.register(root);
    }

    private static int cmdStatus(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        JsonObject o = status(server, ZffSavedData.get(server));
        info(ctx.getSource(), "zff status (server): " + o);
        return 1;
    }

    private static int cmdReload(CommandContext<CommandSourceStack> ctx) {
        ZffConfig.load();
        info(ctx.getSource(), "zff config reloaded: watchdogMs=" + ZffConfig.watchdogMs
                + " watchdogInMod=" + ZffConfig.watchdogInMod);
        return 1;
    }

    /** Admin stop: cancel the shared todo list, then tell every modded client to stop its jobs. */
    private static int cmdStop(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        int cancelled = ZffSavedData.get(server).cancelRunning("zff_stop_command");
        int notified = broadcastStop(server, "zff_stop_command");
        info(ctx.getSource(), "zff stop: todosCancelled=" + cancelled + " clientsNotified=" + notified);
        return 1;
    }

    /**
     * Sends {@link ZffPayloads.Stop} to the players whose client runs the mod; players without it are
     * skipped on purpose (they have no jobs to cancel and must not receive an unknown payload).
     * Returns how many clients were notified.
     */
    public static int broadcastStop(MinecraftServer server, String reason) {
        int sent = 0;
        int skipped = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!isModdedClient(player)) {
                skipped++;
                continue;
            }
            try {
                PacketDistributor.sendToPlayer(player, new ZffPayloads.Stop(reason, "command"));
                sent++;
            } catch (Throwable t) {
                System.out.println(LOG_PREFIX + " stop broadcast to " + player.getName().getString() + " failed: " + t);
            }
        }
        if (skipped > 0) {
            System.out.println(LOG_PREFIX + " stop: " + sent + " modded client(s) notified, "
                    + skipped + " client(s) without the mod skipped");
        }
        return sent;
    }

    private static int cmdMemorySet(CommandContext<CommandSourceStack> ctx, String role) {
        String key = StringArgumentType.getString(ctx, "key");
        String value = StringArgumentType.getString(ctx, "value");
        ZffSavedData data = ZffSavedData.get(ctx.getSource().getServer());
        JsonObject o = data.memorySet(key, value, role, null);
        info(ctx.getSource(), "zff memory set: " + key + " (rev " + o.get("rev").getAsLong()
                + ", entries " + data.memoryList(null).get("count") + ")");
        return 1;
    }

    private static int cmdMemoryGet(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "key");
        JsonObject o = ZffSavedData.get(ctx.getSource().getServer()).memoryGet(key);
        info(ctx.getSource(), "zff memory get: " + key + " = " + (o.has("entry") ? o.get("entry") : "null"));
        return 1;
    }

    private static int cmdMemoryList(CommandContext<CommandSourceStack> ctx) {
        JsonObject o = ZffSavedData.get(ctx.getSource().getServer()).memoryList(null);
        info(ctx.getSource(), "zff memory list (" + o.get("count") + "): " + o.get("entries"));
        return 1;
    }

    private static int cmdMemoryForget(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "key");
        JsonObject o = ZffSavedData.get(ctx.getSource().getServer()).memoryForget(key);
        info(ctx.getSource(), "zff memory forget: " + key + " removed=" + o.get("removed"));
        return 1;
    }

    private static int cmdTodoAdd(CommandContext<CommandSourceStack> ctx) {
        String title = StringArgumentType.getString(ctx, "title");
        JsonObject o = ZffSavedData.get(ctx.getSource().getServer()).todoAdd(title, 5, null, "command");
        info(ctx.getSource(), "zff todo add: " + o.get("task"));
        return 1;
    }

    private static int cmdTodoList(CommandContext<CommandSourceStack> ctx) {
        JsonObject o = ZffSavedData.get(ctx.getSource().getServer()).todoList();
        info(ctx.getSource(), "zff todo list (" + o.get("count") + "): " + o.get("tasks"));
        return 1;
    }

    private static int cmdTodoDone(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "id");
        JsonObject o = ZffSavedData.get(ctx.getSource().getServer()).todoDone(id);
        info(ctx.getSource(), "zff todo done: " + id + " -> " + (o.has("task") ? o.get("task") : o.get("error")));
        return 1;
    }

    private static int cmdTodoClear(CommandContext<CommandSourceStack> ctx) {
        JsonObject o = ZffSavedData.get(ctx.getSource().getServer()).todoClear();
        info(ctx.getSource(), "zff todo clear: cleared=" + o.get("cleared"));
        return 1;
    }

    // ------------------------------------------------------------------ server side watchdog

    /**
     * Server tick sampling: what the server can see of "is this player actually moving". The job
     * watchdog runs on the client (jobs live there), this keeps a server-side record for /zff status
     * and for a future server-hosted agent.
     */
    private static void onServerTick(ServerTickEvent.Post event) {
        if (!ZffConfig.watchdogInMod) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSampleAt < Math.max(100, ZffConfig.reportIntervalMs)) {
            return;
        }
        lastSampleAt = now;
        try {
            MinecraftServer server = event.getServer();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                Sample old = SAMPLES.get(player.getUUID());
                if (old == null) {
                    SAMPLES.put(player.getUUID(), new Sample(player.getX(), player.getY(), player.getZ(), now));
                    continue;
                }
                double moved = Math.abs(player.getX() - old.x()) + Math.abs(player.getY() - old.y())
                        + Math.abs(player.getZ() - old.z());
                if (moved >= 0.05) {
                    SAMPLES.put(player.getUUID(), new Sample(player.getX(), player.getY(), player.getZ(), now));
                }
            }
        } catch (Throwable t) {
            System.out.println(LOG_PREFIX + " server watchdog sample failed: " + t);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    private static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ZffSavedData data = ZffSavedData.get(server);
        System.out.println(LOG_PREFIX + " SERVER READY: dist=" + net.neoforged.fml.loading.FMLEnvironment.dist
                + " world=" + server.getWorldData().getLevelName()
                + " memory=" + data.memoryList(null).get("count")
                + " todos=" + data.todoList().get("count")
                + " rev=" + data.rev()
                + " dataFile=" + server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .resolve("data").resolve(ZffSavedData.NAME + ".dat"));
    }

    /** A player left: forget that its client runs the mod. */
    private static void onPlayerLoggedOut(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            KNOWN_CLIENTS.remove(player.getUUID());
            SAMPLES.remove(player.getUUID());
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        SAMPLES.clear();
        KNOWN_CLIENTS.clear();
        System.out.println(LOG_PREFIX + " server stopping: agent data stays in data/" + ZffSavedData.NAME + ".dat");
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject status(MinecraftServer server, ZffSavedData data) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("side", "server");
        o.addProperty("dist", String.valueOf(net.neoforged.fml.loading.FMLEnvironment.dist));
        o.addProperty("modVersion", BridgeConfig.VERSION);
        o.addProperty("protocol", BridgeConfig.PROTOCOL);
        o.addProperty("watchdogMs", ZffConfig.watchdogMs);
        o.addProperty("watchdogInMod", ZffConfig.watchdogInMod);
        o.addProperty("crEnabled", ZffConfig.crEnabled);
        JsonObject d = data.summary();
        o.add("data", d);
        long now = System.currentTimeMillis();
        var players = new com.google.gson.JsonArray();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            JsonObject p = new JsonObject();
            p.addProperty("name", player.getName().getString());
            p.addProperty("dimension", player.level().dimension().location().toString());
            p.addProperty("x", Math.round(player.getX() * 10) / 10.0);
            p.addProperty("y", Math.round(player.getY() * 10) / 10.0);
            p.addProperty("z", Math.round(player.getZ() * 10) / 10.0);
            Sample s = SAMPLES.get(player.getUUID());
            if (s != null) {
                p.addProperty("stillMs", now - s.movedAt());
            }
            players.add(p);
        }
        o.add("players", players);
        return o;
    }

    private static JsonObject parse(String text) {
        if (text == null || text.isBlank()) {
            return new JsonObject();
        }
        try {
            var parsed = com.google.gson.JsonParser.parseString(text);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return "";
        }
        try {
            return o.get(key).getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int num(JsonObject o, String key, int def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return def;
        }
        try {
            return o.get(key).getAsInt();
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static void info(CommandSourceStack source, String text) {
        try {
            source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.AQUA), false);
        } catch (Throwable ignored) {
            System.out.println(LOG_PREFIX + " " + text);
        }
        System.out.println(LOG_PREFIX + " " + text);
    }
}
