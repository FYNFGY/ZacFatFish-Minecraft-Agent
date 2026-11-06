package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Thread-safe journal of in-game events (chat, damage, death, respawn) for the agent.
 * {@code wait_events} blocks on it so the agent learns about messages and hits immediately
 * instead of polling the world state.
 */
public final class EventJournal {
    private static final int MAX = 300;
    private static final Deque<Entry> ENTRIES = new ArrayDeque<>();
    private static long seq;
    private static long delivered;

    private EventJournal() {
    }

    private record Entry(long seq, JsonObject event) {
    }

    public static synchronized void push(String type, JsonObject payload) {
        JsonObject e = new JsonObject();
        e.addProperty("seq", ++seq);
        e.addProperty("type", type);
        e.addProperty("at", System.currentTimeMillis());
        if (payload != null) {
            for (String key : payload.keySet()) {
                if ("type".equals(key)) {
                    e.add("kind", payload.get(key));
                } else {
                    e.add(key, payload.get(key));
                }
            }
        }
        ENTRIES.addLast(new Entry(seq, e));
        while (ENTRIES.size() > MAX) {
            ENTRIES.removeFirst();
        }
    }

    public static synchronized long lastSeq() {
        return seq;
    }

    /** Returns events after {@code from}; does not touch the delivered cursor. */
    public static synchronized JsonObject since(long from, int limit) {
        JsonArray out = new JsonArray();
        long max = from;
        for (Entry entry : ENTRIES) {
            if (entry.seq() > from) {
                out.add(entry.event().deepCopy());
                max = entry.seq();
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        JsonObject result = new JsonObject();
        result.add("events", out);
        result.addProperty("cursor", max);
        return result;
    }

    /** Returns events after the delivered cursor and advances it (repeated polling never repeats). */
    public static synchronized JsonObject takeDelivered(int limit) {
        JsonObject result = since(delivered, limit);
        JsonArray events = result.getAsJsonArray("events");
        if (!events.isEmpty()) {
            delivered = result.get("cursor").getAsLong();
        }
        return result;
    }
}
