package dev.mcp.bridge;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The mod's own Minecraft channel (Ph5 T4: no extra server port).
 *
 * <p>Client to server: a small RPC request ({@link Request}) used by the loopback bridge to read and
 * write the server-side {@link ZffSavedData} (memory/todo). Server to client: the reply
 * ({@link Response}) and the {@code /zff stop} broadcast ({@link Stop}). Everything travels inside the
 * normal play channel, so a dedicated server never has to listen on a port of its own.</p>
 *
 * <p>All three payloads are registered as optional channels: a client without the mod is not kicked,
 * the server simply cannot talk to it.</p>
 */
public final class ZffPayloads {
    private ZffPayloads() {
    }

    private static ResourceLocation loc(String path) {
        return ResourceLocation.fromNamespaceAndPath(BridgeConfig.MOD_ID, path);
    }

    /** client -> server: {id, op, arg}; the server answers with a {@link Response} carrying the same id. */
    public record Request(String id, String op, String arg) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ZffPayloads.loc("rpc_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Request::id,
                ByteBufCodecs.STRING_UTF8, Request::op,
                ByteBufCodecs.STRING_UTF8, Request::arg,
                Request::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * client -> server (Ph5, reused by the Ph6 M3 negotiation): {@code hello{modVersion, protocol}}.
     *
     * <p>The server only sends its other payloads (the {@code /zff stop} broadcast, the rpc answers) to
     * players that announced themselves, so a client without the mod never receives any
     * {@code mcpbridge:*} packet and can never log an unknown-channel error.</p>
     *
     * <p>The negotiation deliberately reuses this payload instead of adding a channel: NeoForge 21.1.228
     * rejects a connection whose two sides do not register the same channel set ("Incompatible client!
     * Please use NeoForge ...") and {@code PayloadRegistrar.optional()} does not prevent that — measured,
     * see {@code docs/ph6/REPORT-M3.md}. The protocol number therefore travels inside the version string
     * ({@code "0.2.0+p2"}); an old server half reads it as an opaque version string and ignores it, which
     * is exactly what keeps an old server half usable from a new client.</p>
     */
    public record Hello(String version) implements CustomPacketPayload {
        public static final Type<Hello> TYPE = new Type<>(ZffPayloads.loc("hello"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Hello::version,
                Hello::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** server -> client: the answer to one {@link Request}. {@code json} is a JSON object string. */
    public record Response(String id, boolean ok, String json) implements CustomPacketPayload {
        public static final Type<Response> TYPE = new Type<>(ZffPayloads.loc("rpc_response"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Response> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Response::id,
                ByteBufCodecs.BOOL, Response::ok,
                ByteBufCodecs.STRING_UTF8, Response::json,
                Response::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** server -> client: the admin ran {@code /zff stop}; stop every job on this client. */
    public record Stop(String reason, String by) implements CustomPacketPayload {
        public static final Type<Stop> TYPE = new Type<>(ZffPayloads.loc("stop"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Stop> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Stop::reason,
                ByteBufCodecs.STRING_UTF8, Stop::by,
                Stop::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
