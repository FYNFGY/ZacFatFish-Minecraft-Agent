package dev.mcp.bridge;

import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The one link between the common (server-safe) half of the mod and the client-only half.
 *
 * <p>Payload handlers are registered in {@link ZffServer}, i.e. in common code. If that code referred
 * to a client class directly the class would be resolved while a dedicated server registers the
 * channel, which is exactly what Ph5 T1 forbids. The client installs its handlers here during client
 * setup; on a dedicated server the fields stay {@code null} and every packet handler is a no-op.</p>
 */
public final class ZffHooks {
    private ZffHooks() {
    }

    /** Implemented by the client entry point only. */
    public interface ClientSide {
        /** Server answered a memory/todo/status request. */
        void onResponse(ZffPayloads.Response payload, IPayloadContext context);

        /** The admin ran {@code /zff stop}: cancel every job on this client. */
        void onStop(ZffPayloads.Stop payload, IPayloadContext context);
    }

    /** Set by {@code dev.mcp.bridge.client.ZffClient}; null on a dedicated server. */
    public static volatile ClientSide clientSide;
}
