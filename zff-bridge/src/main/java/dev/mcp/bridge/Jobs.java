package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/** Non-blocking job lifecycle: queued -> running -> done | failed | cancelled. */
public final class Jobs {
    public enum State {
        QUEUED, RUNNING, DONE, FAILED, CANCELLED
    }

    public interface TickTask {
        void start(Job job) throws Exception;

        void tick(Job job) throws Exception;

        default void cancel(Job job) {
        }
    }

    public static final class Job {
        public final String id;
        public final String tool;
        public final long createdAt;
        public final long deadline;
        public final int timeoutSeconds;
        private final TickTask task;
        private final JsonObject metrics = new JsonObject();
        private State state = State.QUEUED;
        private int progress;
        private String reason;
        private String error;
        private String message;
        private JsonObject result;
        private long updatedAt;

        private Job(String id, String tool, int timeoutSeconds, TickTask task) {
            this.id = id;
            this.tool = tool;
            this.timeoutSeconds = timeoutSeconds;
            this.task = task;
            this.createdAt = System.currentTimeMillis();
            this.updatedAt = createdAt;
            this.deadline = createdAt + timeoutSeconds * 1000L;
        }

        public synchronized State state() {
            return state;
        }

        public synchronized boolean isTerminal() {
            return state == State.DONE || state == State.FAILED || state == State.CANCELLED;
        }

        public synchronized void progress(int value) {
            if (value > progress) {
                progress = Math.max(0, Math.min(100, value));
            }
            updatedAt = System.currentTimeMillis();
        }

        public synchronized void metric(String key, JsonElement value) {
            metrics.add(key, value);
            updatedAt = System.currentTimeMillis();
        }

        public synchronized void metric(String key, String value) {
            metrics.addProperty(key, value);
            updatedAt = System.currentTimeMillis();
        }

        public synchronized void metric(String key, Number value) {
            metrics.addProperty(key, value);
            updatedAt = System.currentTimeMillis();
        }

        public synchronized void metric(String key, boolean value) {
            metrics.addProperty(key, value);
            updatedAt = System.currentTimeMillis();
        }

        public synchronized void succeed(JsonObject value) {
            if (isTerminal()) {
                return;
            }
            this.result = value;
            this.progress = 100;
            this.state = State.DONE;
            this.updatedAt = System.currentTimeMillis();
        }

        public synchronized void fail(String errorCode, String detail) {
            if (isTerminal()) {
                return;
            }
            this.state = State.FAILED;
            this.error = errorCode;
            this.reason = errorCode;
            this.message = detail;
            this.updatedAt = System.currentTimeMillis();
        }

        public synchronized void cancel() {
            cancel("cancelled");
        }

        public synchronized void cancel(String reason) {
            if (isTerminal()) {
                return;
            }
            this.state = State.CANCELLED;
            this.reason = reason == null ? "cancelled" : reason;
            this.updatedAt = System.currentTimeMillis();
        }

        public synchronized void markRunning() {
            if (state == State.QUEUED) {
                state = State.RUNNING;
                updatedAt = System.currentTimeMillis();
            }
        }

        public synchronized JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("jobId", id);
            o.addProperty("tool", tool);
            o.addProperty("state", state.name().toLowerCase());
            o.addProperty("progress", progress);
            if (reason != null) {
                o.addProperty("reason", reason);
            }
            if (error != null) {
                o.addProperty("error", error);
            }
            if (message != null) {
                o.addProperty("message", message);
            }
            if (!metrics.isEmpty()) {
                o.add("metrics", metrics.deepCopy());
            }
            if (result != null) {
                o.add("result", result);
            }
            o.addProperty("createdAt", createdAt);
            o.addProperty("updatedAt", updatedAt);
            o.addProperty("deadline", deadline);
            o.addProperty("timeoutSeconds", timeoutSeconds);
            return o;
        }
    }

    private static final AtomicLong COUNTER = new AtomicLong();

    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> order = new ConcurrentLinkedDeque<>();
    private static final int MAX_JOBS = 200;

    public Job create(String tool, int timeoutSeconds, TickTask task) {
        String id = "j-" + Long.toString(COUNTER.incrementAndGet(), 36) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        Job job = new Job(id, tool, timeoutSeconds, task);
        jobs.put(id, job);
        order.addLast(id);
        prune();
        return job;
    }

    public Job get(String id) {
        return id == null ? null : jobs.get(id);
    }

    /**
     * Snapshot of the jobs that have not reached a terminal state yet. Must be called on the client
     * thread. The mod watchdog (Ph5 T3) walks this list every sample; it is a copy, so cancelling a job
     * while iterating is safe.
     */
    public List<Job> active() {
        List<Job> out = new ArrayList<>();
        for (Job job : jobs.values()) {
            if (!job.isTerminal()) {
                out.add(job);
            }
        }
        return out;
    }

    /** Must be called on the client thread. */
    public void tickAll() {
        long now = System.currentTimeMillis();
        List<Job> snapshot = new ArrayList<>(jobs.values());
        for (Job job : snapshot) {
            if (job.isTerminal()) {
                continue;
            }
            if (now > job.deadline) {
                job.fail(Errors.TIMEOUT, "job exceeded its timeout of " + job.timeoutSeconds + "s");
                safeCancel(job);
                continue;
            }
            if (job.state() == State.QUEUED) {
                job.markRunning();
                try {
                    job.task.start(job);
                } catch (Throwable t) {
                    job.fail(codeOf(t), messageOf(t));
                    safeCancel(job);
                    continue;
                }
            }
            if (job.state() == State.RUNNING) {
                try {
                    job.task.tick(job);
                } catch (Throwable t) {
                    job.fail(codeOf(t), messageOf(t));
                    safeCancel(job);
                }
            }
        }
        prune();
    }

    /** Must be called on the client thread. */
    public boolean cancel(String id) {
        Job job = jobs.get(id);
        if (job == null) {
            return false;
        }
        if (!job.isTerminal()) {
            safeCancel(job);
            job.cancel();
        }
        return true;
    }

    /** Must be called on the client thread. Cancels every non-terminal job of one tool. */
    public int cancelTool(String tool) {
        int cancelled = 0;
        for (Job job : new ArrayList<>(jobs.values())) {
            if (!job.isTerminal() && tool.equals(job.tool)) {
                safeCancel(job);
                job.cancel();
                cancelled++;
            }
        }
        return cancelled;
    }

    /** Must be called on the client thread. Cancels every non-terminal job. */
    public int cancelAll(String reason) {
        int cancelled = 0;
        for (Job job : new ArrayList<>(jobs.values())) {
            if (!job.isTerminal()) {
                safeCancel(job);
                job.cancel(reason);
                cancelled++;
            }
        }
        return cancelled;
    }

    /** Must be called on the client thread. */
    public void failAll(String code, String message) {
        for (Job job : new ArrayList<>(jobs.values())) {
            if (!job.isTerminal()) {
                safeCancel(job);
                job.fail(code, message);
            }
        }
    }

    public JsonArray list() {
        JsonArray a = new JsonArray();
        int count = 0;
        for (String id : order) {
            if (count++ >= 100) {
                break;
            }
            Job job = jobs.get(id);
            if (job != null) {
                a.add(job.toJson());
            }
        }
        return a;
    }

    private void safeCancel(Job job) {
        try {
            job.task.cancel(job);
        } catch (Throwable ignored) {
        }
    }

    private void prune() {
        if (jobs.size() <= MAX_JOBS) {
            return;
        }
        int toRemove = jobs.size() - MAX_JOBS;
        int scanned = order.size();
        while (toRemove > 0 && scanned-- > 0) {
            String id = order.pollFirst();
            if (id == null) {
                return;
            }
            Job job = jobs.get(id);
            if (job != null && job.isTerminal()) {
                jobs.remove(id);
                toRemove--;
            } else if (job == null) {
                toRemove--;
            } else {
                order.addLast(id);
            }
        }
    }

    private static String codeOf(Throwable t) {
        if (t instanceof Errors.BridgeException be) {
            return be.code;
        }
        return Errors.INTERNAL;
    }

    private static String messageOf(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
}
