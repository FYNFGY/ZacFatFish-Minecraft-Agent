package dev.mcp.bridge;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Writes the discovery file required by the MCP side and refreshes it every 5 seconds.
 * The token is only ever serialized into this file; it is never logged.
 */
public final class InstanceRegistry implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("mcpbridge/registry");

    private final Path file;
    private final JsonObject base;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread heartbeat;
    private volatile boolean warned;

    private InstanceRegistry(Path file, JsonObject base) {
        this.file = file;
        this.base = base;
        this.heartbeat = new Thread(this::heartbeatLoop, "mcpbridge-heartbeat");
        this.heartbeat.setDaemon(true);
    }

    public static InstanceRegistry start(Path stateDir, int port, String token, Path gameDir, String mcVersion) throws IOException {
        Path dir = BridgeConfig.instanceDir(stateDir);
        Files.createDirectories(dir);
        secureDirectory(dir);
        String id = UUID.randomUUID().toString();
        Path file = dir.resolve(id + ".json");
        JsonObject base = new JsonObject();
        base.addProperty("protocol", BridgeConfig.PROTOCOL);
        base.addProperty("port", port);
        base.addProperty("token", token);
        base.addProperty("bridgeVersion", BridgeConfig.VERSION);
        base.addProperty("loader", BridgeConfig.LOADER);
        base.addProperty("mcVersion", mcVersion);
        base.addProperty("gameDir", gameDir.toAbsolutePath().toString());
        base.addProperty("started", System.currentTimeMillis());
        base.addProperty("heartbeatAt", System.currentTimeMillis());
        InstanceRegistry registry = new InstanceRegistry(file, base);
        registry.write();
        registry.heartbeat.start();
        return registry;
    }

    public Path file() {
        return file;
    }

    private void heartbeatLoop() {
        while (running.get()) {
            try {
                Thread.sleep(BridgeConfig.HEARTBEAT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running.get()) {
                return;
            }
            try {
                write();
            } catch (IOException e) {
                if (!warned) {
                    warned = true;
                    LOG.warn("heartbeat write failed for {}: {}", file.getFileName(), e.toString());
                }
            }
        }
    }

    private void write() throws IOException {
        JsonObject copy = base.deepCopy();
        copy.addProperty("heartbeatAt", System.currentTimeMillis());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.GSON.toJson(copy), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        secureFile(tmp);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        secureFile(file);
    }

    private static void secureDirectory(Path dir) {
        try {
            if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
                Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwx------");
                Files.setPosixFilePermissions(dir, perms);
            }
        } catch (Exception ignored) {
            // Windows and unusual filesystems have no POSIX permissions.
        }
    }

    private static void secureFile(Path file) {
        try {
            if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            }
        } catch (Exception ignored) {
            // Best effort only.
        }
    }

    @Override
    public void close() {
        running.set(false);
        heartbeat.interrupt();
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("could not delete registration {}: {}", file.getFileName(), e.toString());
        }
    }
}
