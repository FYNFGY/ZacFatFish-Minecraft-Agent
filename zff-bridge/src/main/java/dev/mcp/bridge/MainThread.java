package dev.mcp.bridge;

import net.minecraft.client.Minecraft;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** All world access goes through {@link Minecraft#execute(Runnable)} and returns through a future. */
public final class MainThread {
    private MainThread() {
    }

    public static Minecraft mc() {
        return Minecraft.getInstance();
    }

    public static <T> CompletableFuture<T> submit(String tool, Callable<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            future.completeExceptionally(Errors.notLoaded("Minecraft client is not initialised"));
            return future;
        }
        try {
            client.execute(() -> {
                try {
                    future.complete(action.call());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (Throwable t) {
            future.completeExceptionally(Errors.internal("failed to schedule on the client thread", t));
        }
        return future;
    }

    public static void execute(String tool, Runnable action) {
        submit(tool, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T await(CompletableFuture<T> future, long timeoutMs) {
        try {
            return future.get(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw Errors.timeout("the client did not finish the operation in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Errors.internal("interrupted while waiting for the client", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Errors.BridgeException be) {
                throw be;
            }
            throw Errors.internal(cause == null ? "client operation failed" : cause.toString(), cause);
        }
    }

    public static boolean inWorld() {
        Minecraft client = Minecraft.getInstance();
        return client != null && client.player != null && client.level != null;
    }
}
