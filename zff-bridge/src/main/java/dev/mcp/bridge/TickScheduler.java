package dev.mcp.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs short pieces of work on the client thread from the tick event. Used for operations that
 * need one or more ticks without ever blocking a render/tick callback in a loop.
 */
public final class TickScheduler {
    private static final Logger LOG = LoggerFactory.getLogger("mcpbridge/ticks");
    private static final ConcurrentLinkedQueue<Runnable> TASKS = new ConcurrentLinkedQueue<>();
    private static final int MAX_TASKS_PER_TICK = 2_000;

    private TickScheduler() {
    }

    public static void schedule(Runnable task) {
        TASKS.add(task);
    }

    /** Drops every pending task. Pending poll futures never complete; callers must not depend on them. */
    public static int clear() {
        int dropped = 0;
        while (TASKS.poll() != null) {
            dropped++;
        }
        return dropped;
    }

    public static void tick() {        // Tasks scheduled while this batch runs wait for the next tick. Poll chains must observe
        // state that only changes between ticks (server round trips, world updates), otherwise a
        // 60-tick poll completes inside a single tick and everything looks timed out.
        java.util.List<Runnable> due = new java.util.ArrayList<>();
        int budget = MAX_TASKS_PER_TICK;
        Runnable task;
        while (budget-- > 0 && (task = TASKS.poll()) != null) {
            due.add(task);
        }
        for (Runnable runnable : due) {
            try {
                runnable.run();
            } catch (Throwable t) {
                LOG.warn("tick task failed: {}", t.toString());
            }
        }
    }

    /**
     * Polls a supplier once per client tick until it returns non-null. Completes exceptionally with
     * {@code timeout} when the tick budget is exhausted.
     */
    public static <T> CompletableFuture<T> poll(int maxTicks, java.util.function.IntFunction<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        AtomicInteger ticks = new AtomicInteger();
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (future.isDone()) {
                return;
            }
            try {
                T value = supplier.apply(ticks.get());
                if (value != null) {
                    future.complete(value);
                    return;
                }
            } catch (Throwable t) {
                future.completeExceptionally(t);
                return;
            }
            if (ticks.incrementAndGet() >= maxTicks) {
                future.completeExceptionally(Errors.timeout("condition was not met within " + maxTicks + " ticks"));
                return;
            }
            schedule(step[0]);
        };
        schedule(step[0]);
        return future;
    }

    /** Schedules a runnable after a number of client ticks. */
    public static void afterTicks(int ticks, Runnable action) {
        if (ticks <= 0) {
            schedule(action);
            return;
        }
        AtomicInteger remaining = new AtomicInteger(ticks);
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (remaining.decrementAndGet() <= 0) {
                action.run();
            } else {
                schedule(step[0]);
            }
        };
        schedule(step[0]);
    }
}
