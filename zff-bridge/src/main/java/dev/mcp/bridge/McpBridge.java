package dev.mcp.bridge;

import dev.mcp.bridge.client.ZffClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * Single mod entry point for <b>both</b> sides (Ph5 T1).
 *
 * <p>The mod used to be {@code dist = Dist.CLIENT}, so a server could not load it at all. It is now a
 * common mod: a dedicated server runs the config, the {@code /zff} commands, the {@code SavedData}
 * store, the mod channel and the server watchdog; a client additionally runs the loopback HTTP bridge
 * and the job machinery.</p>
 *
 * <p>The only reference from common code to a client class is the {@code ZffClient.init} call below,
 * and it is guarded by {@link FMLEnvironment#dist}: on a dedicated server the branch is never taken, so
 * the client package is never loaded (verified by {@code scripts/check-sides.mjs} and by the dedicated
 * server run in {@code evidence/ph5-server-side.json}).</p>
 */
@Mod(value = BridgeConfig.MOD_ID)
public final class McpBridge {
    public McpBridge(IEventBus modBus, ModContainer container) {
        System.out.println("[zff] mcpbridge " + BridgeConfig.VERSION + " loading: dist=" + FMLEnvironment.dist
                + " production=" + FMLEnvironment.production);
        // Common to both sides: configuration, server storage, /zff commands, mod channel.
        ZffConfig.load();
        ZffServer.init(modBus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ZffClient.init(modBus);
        } else {
            System.out.println("[zff] server side only: no loopback HTTP bridge and no client classes on "
                    + FMLEnvironment.dist);
        }
    }
}
