package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server-side agent storage (Ph5 T2): the shared memory and todo list of every AI that works on this
 * world, saved as {@code <world>/data/zff_agent.dat} through {@link SavedData}.
 *
 * <p>It lives on the <b>server</b> (also the integrated server of a single player world), so two
 * clients, a reconnect or a restart all see the same list. The client bridge never touches this class:
 * it asks for data over the mod channel (see {@link ZffPayloads}), which keeps the client free of world
 * file access and keeps the server free of client classes.</p>
 */
public final class ZffSavedData extends SavedData {
    public static final String NAME = "zff_agent";
    private static final int FORMAT = 1;

    /** One memory entry: a dotted key, a string value (JSON allowed) and the role that wrote it. */
    public static final class Entry {
        String key;
        String value;
        String role;
        List<String> tags = new ArrayList<>();
        long updatedAt;

        Entry(String key, String value, String role, List<String> tags, long updatedAt) {
            this.key = key;
            this.value = value;
            this.role = role;
            this.tags = tags;
            this.updatedAt = updatedAt;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("key", key);
            o.addProperty("value", value);
            o.addProperty("role", role);
            JsonArray t = new JsonArray();
            for (String tag : tags) {
                t.add(tag);
            }
            o.add("tags", t);
            o.addProperty("updatedAt", updatedAt);
            return o;
        }
    }

    /** One task of the todo list. Kept in a stable order so /zff todo list is readable. */
    public static final class Todo {
        String id;
        String title;
        String status = "pending";
        int priority = 5;
        String spec = "";
        String note = "";
        String jobId = "";
        String actor = "";
        long createdAt;
        long updatedAt;

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("title", title);
            o.addProperty("status", status);
            o.addProperty("priority", priority);
            if (spec != null && !spec.isBlank()) {
                o.addProperty("spec", spec);
            }
            o.addProperty("note", note);
            if (jobId != null && !jobId.isBlank()) {
                o.addProperty("jobId", jobId);
            }
            o.addProperty("actorId", actor);
            o.addProperty("createdAt", createdAt);
            o.addProperty("updatedAt", updatedAt);
            return o;
        }
    }

    private final Map<String, Entry> memory = new LinkedHashMap<>();
    private final Map<String, Todo> todos = new LinkedHashMap<>();
    private String actorId = "ai-1";
    private long rev;
    private long seq;
    private String world = "";

    public ZffSavedData() {
    }

    // ------------------------------------------------------------------ plumbing

    public static ZffSavedData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        ZffSavedData data = overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ZffSavedData::new, ZffSavedData::load, DataFixTypes.LEVEL),
                NAME);
        data.world = server.getWorldData().getLevelName();
        return data;
    }

    public static ZffSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ZffSavedData data = new ZffSavedData();
        data.actorId = tag.contains("actorId") ? tag.getString("actorId") : "ai-1";
        data.rev = tag.getLong("rev");
        data.seq = tag.getLong("seq");
        ListTag entries = tag.getList("memory", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag e = entries.getCompound(i);
            List<String> tags = new ArrayList<>();
            for (String s : e.getString("tags").split(",")) {
                if (!s.isBlank()) {
                    tags.add(s.trim());
                }
            }
            Entry entry = new Entry(e.getString("key"), e.getString("value"), e.getString("role"),
                    tags, e.getLong("updatedAt"));
            if (!entry.key.isBlank()) {
                data.memory.put(entry.key, entry);
            }
        }
        ListTag tasks = tag.getList("todos", Tag.TAG_COMPOUND);
        for (int i = 0; i < tasks.size(); i++) {
            CompoundTag t = tasks.getCompound(i);
            Todo todo = new Todo();
            todo.id = t.getString("id");
            todo.title = t.getString("title");
            todo.status = t.getString("status");
            todo.priority = t.getInt("priority");
            todo.spec = t.getString("spec");
            todo.note = t.getString("note");
            todo.jobId = t.getString("jobId");
            todo.actor = t.getString("actorId");
            todo.createdAt = t.getLong("createdAt");
            todo.updatedAt = t.getLong("updatedAt");
            if (!todo.id.isBlank()) {
                data.todos.put(todo.id, todo);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("format", FORMAT);
        tag.putString("actorId", actorId);
        tag.putLong("rev", rev);
        tag.putLong("seq", seq);
        ListTag entries = new ListTag();
        for (Entry e : memory.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("key", e.key);
            t.putString("value", e.value);
            t.putString("role", e.role);
            t.putString("tags", String.join(",", e.tags));
            t.putLong("updatedAt", e.updatedAt);
            entries.add(t);
        }
        tag.put("memory", entries);
        ListTag tasks = new ListTag();
        for (Todo todo : todos.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("id", todo.id);
            t.putString("title", todo.title);
            t.putString("status", todo.status);
            t.putInt("priority", todo.priority);
            t.putString("spec", todo.spec == null ? "" : todo.spec);
            t.putString("note", todo.note == null ? "" : todo.note);
            t.putString("jobId", todo.jobId == null ? "" : todo.jobId);
            t.putString("actorId", todo.actor == null ? "" : todo.actor);
            t.putLong("createdAt", todo.createdAt);
            t.putLong("updatedAt", todo.updatedAt);
            tasks.add(t);
        }
        tag.put("todos", tasks);
        return tag;
    }

    private void touch() {
        rev++;
        setDirty();
    }

    // ------------------------------------------------------------------ memory

    public synchronized JsonObject memorySet(String key, String value, String role, List<String> tags) {
        String k = key == null ? "" : key.trim();
        if (k.isEmpty()) {
            return error("key is required");
        }
        Entry e = memory.get(k);
        if (e == null) {
            e = new Entry(k, value, role, new ArrayList<>(), System.currentTimeMillis());
            memory.put(k, e);
        }
        e.value = value == null ? "" : value;
        e.role = role == null || role.isBlank() ? "sr" : role;
        e.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
        e.updatedAt = System.currentTimeMillis();
        touch();
        JsonObject o = ok();
        o.addProperty("rev", rev);
        o.add("entry", e.toJson());
        return o;
    }

    public synchronized JsonObject memoryGet(String key) {
        Entry e = memory.get(key == null ? "" : key.trim());
        JsonObject o = ok();
        o.addProperty("found", e != null);
        if (e != null) {
            o.add("entry", e.toJson());
        }
        return o;
    }

    public synchronized JsonObject memoryList(String tag) {
        JsonArray arr = new JsonArray();
        int n = 0;
        for (Entry e : memory.values()) {
            if (tag != null && !tag.isBlank() && !e.tags.contains(tag)) {
                continue;
            }
            arr.add(e.toJson());
            n++;
        }
        JsonObject o = ok();
        o.addProperty("count", n);
        o.add("entries", arr);
        o.addProperty("rev", rev);
        return o;
    }

    public synchronized JsonObject memoryForget(String key) {
        boolean removed = memory.remove(key == null ? "" : key.trim()) != null;
        if (removed) {
            touch();
        }
        JsonObject o = ok();
        o.addProperty("removed", removed);
        o.addProperty("rev", rev);
        return o;
    }

    // ------------------------------------------------------------------ todo list

    public synchronized JsonObject todoAdd(String title, int priority, String spec, String note) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) {
            return error("title is required");
        }
        Todo todo = new Todo();
        todo.id = "t-" + (++seq);
        todo.title = t;
        todo.status = "pending";
        todo.priority = Math.max(0, Math.min(9, priority));
        todo.spec = spec == null ? "" : spec;
        todo.note = note == null ? "" : note;
        todo.actor = actorId;
        todo.createdAt = System.currentTimeMillis();
        todo.updatedAt = todo.createdAt;
        todos.put(todo.id, todo);
        touch();
        JsonObject o = ok();
        o.add("task", todo.toJson());
        o.addProperty("rev", rev);
        return o;
    }

    public synchronized JsonObject todoList() {
        JsonArray arr = new JsonArray();
        for (Todo t : todos.values()) {
            arr.add(t.toJson());
        }
        JsonObject o = ok();
        o.addProperty("count", todos.size());
        o.add("tasks", arr);
        o.addProperty("rev", rev);
        o.addProperty("actorId", actorId);
        return o;
    }

    public synchronized JsonObject todoDone(String id) {
        Todo t = todos.get(id == null ? "" : id.trim());
        if (t == null) {
            return error("unknown task id: " + id);
        }
        t.status = "done";
        t.updatedAt = System.currentTimeMillis();
        touch();
        JsonObject o = ok();
        o.add("task", t.toJson());
        o.addProperty("rev", rev);
        return o;
    }

    public synchronized JsonObject todoUpdate(String id, String status, String note) {
        Todo t = todos.get(id == null ? "" : id.trim());
        if (t == null) {
            return error("unknown task id: " + id);
        }
        if (status != null && !status.isBlank()) {
            t.status = status.toLowerCase(Locale.ROOT);
        }
        if (note != null) {
            t.note = note;
        }
        t.updatedAt = System.currentTimeMillis();
        touch();
        JsonObject o = ok();
        o.add("task", t.toJson());
        o.addProperty("rev", rev);
        return o;
    }

    public synchronized JsonObject todoClear() {
        int n = todos.size();
        todos.clear();
        if (n > 0) {
            touch();
        }
        JsonObject o = ok();
        o.addProperty("cleared", n);
        o.addProperty("rev", rev);
        return o;
    }

    /** {@code /zff stop}: every task that is still running becomes cancelled. */
    public synchronized int cancelRunning(String reason) {
        int n = 0;
        for (Todo t : todos.values()) {
            if ("running".equals(t.status) || "pending".equals(t.status)) {
                t.status = "cancelled";
                t.note = reason == null ? "cancelled" : reason;
                t.updatedAt = System.currentTimeMillis();
                n++;
            }
        }
        if (n > 0) {
            touch();
        }
        return n;
    }

    // ------------------------------------------------------------------ misc

    public synchronized JsonObject summary() {
        JsonObject o = ok();
        o.addProperty("side", "server");
        o.addProperty("file", "zff_agent.dat");
        o.addProperty("world", world);
        o.addProperty("actorId", actorId);
        o.addProperty("rev", rev);
        o.addProperty("memory", memory.size());
        o.addProperty("todos", todos.size());
        int running = 0;
        for (Todo t : todos.values()) {
            if ("running".equals(t.status)) {
                running++;
            }
        }
        o.addProperty("running", running);
        return o;
    }

    public long rev() {
        return rev;
    }

    public String actorId() {
        return actorId;
    }

    public synchronized void setActor(String actor) {
        if (actor != null && !actor.isBlank()) {
            actorId = actor.trim();
            touch();
        }
    }

    /** Parses a JSON array of tags, tolerating a plain comma separated list. */
    public static List<String> parseTags(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        String text = raw.trim();
        if (text.startsWith("[")) {
            try {
                JsonArray arr = JsonParser.parseString(text).getAsJsonArray();
                for (var e : arr) {
                    out.add(e.getAsString());
                }
                return out;
            } catch (RuntimeException ignored) {
                // fall through to the comma form
            }
        }
        for (String s : text.split(",")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    private static JsonObject ok() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    private static JsonObject error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", message);
        return o;
    }
}
