package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.UUID;

/** Own ring buffer for chat; the client itself only keeps the last 100 messages. */
public final class ChatLog {
    /**
     * Ph6: the ring is also what the mod store persists ({@code <gameDir>/zff/mod-store.json}), so it
     * has to be big enough to be a real "完整聊天记录" (§2.4: 2000 lines). The tool-facing
     * {@link #history(int)} still truncates by bytes; {@link #storeHistory(int)} does not.
     */
    public static final int MAX = 2000;
    private static final Deque<JsonObject> MESSAGES = new ArrayDeque<>();
    private static long SEQ;

    private ChatLog() {
    }

    public static void onChat(ClientChatReceivedEvent event) {
        try {
            Component message = event.getMessage();
            if (message == null) {
                return;
            }
            String text = message.getString();
            if (text == null) {
                return;
            }
            String type = event.isSystem() ? "system" : "chat";
            UUID sender = event.getSender();
            String senderName = null;
            if (sender != null && Minecraft.getInstance().getConnection() != null) {
                var info = Minecraft.getInstance().getConnection().getPlayerInfo(sender);
                if (info != null) {
                    senderName = info.getProfile().getName();
                }
            }
            if (senderName == null) {
                var bound = event.getBoundChatType();
                if (bound != null && bound.chatType() != null) {
                    String key = bound.chatType().toString();
                    if (key.contains("action_bar")) {
                        type = "actionbar";
                    }
                }
            }
            // Ph6: a player line arrives decorated ("<FYNyesterday> 鱼，回来吃饭"). The "<name> " part is
            // display only, and keeping it would break every rule that anchors on the message start
            // (the default chat-interrupt rule is `^鱼`). The raw text is kept for debugging.
            String raw = text;
            text = stripSenderPrefix(text, senderName);
            add(type, senderName, text, raw, false, senderName != null && isSelf(senderName));
        } catch (Throwable ignored) {
            // A malformed chat component must never break the client tick loop.
        }
    }

    /** Removes a leading {@code <name> } / {@code [tag] } decoration from a chat line. */
    static String stripSenderPrefix(String text, String senderName) {
        if (text == null) {
            return null;
        }
        if (senderName != null) {
            String exact = "<" + senderName + "> ";
            if (text.startsWith(exact)) {
                return text.substring(exact.length());
            }
        }
        if (text.startsWith("<")) {
            int end = text.indexOf("> ");
            if (end > 0 && end <= 40) {
                return text.substring(end + 2);
            }
        }
        return text;
    }

    /** True when {@code name} is the local player: those lines are never "the player talking to us". */
    private static boolean isSelf(String name) {
        if (name == null) {
            return false;
        }
        String me = BridgeRuntime.playerName;
        if (me != null && me.equals(name)) {
            return true;
        }
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && name.equals(mc.player.getName().getString());
    }

    public static synchronized void add(String type, String sender, String text, boolean outgoing) {
        add(type, sender, text, null, outgoing, outgoing);
    }

    private static synchronized void add(String type, String sender, String text, String raw, boolean outgoing, boolean self) {
        JsonObject o = new JsonObject();
        long now = System.currentTimeMillis();
        o.addProperty("time", now);
        o.addProperty("type", type == null ? "chat" : type);
        if (sender != null) {
            o.addProperty("sender", sender);
        }
        o.addProperty("text", text == null ? "" : text);
        if (raw != null && !raw.equals(text)) {
            o.addProperty("raw", raw.length() > 500 ? raw.substring(0, 500) + "..." : raw);
        }
        if (outgoing) {
            o.addProperty("outgoing", true);
        }
        if (self) {
            o.addProperty("self", true);
        }
        o.addProperty("seq", ++SEQ);
        MESSAGES.addLast(o);
        while (MESSAGES.size() > MAX) {
            MESSAGES.removeFirst();
        }
        String kind = o.get("type").getAsString();
        if (!"actionbar".equals(kind)) {
            EventJournal.push("chat", o);
            // Ph6 §2.8: every non-actionbar line goes to the MCP server immediately, so a player message
            // can interrupt a blocking tool call. Matching/queueing happen in Node, never here.
            ChatPush.enqueue(o.deepCopy());
        }
        if (!outgoing && !"actionbar".equals(kind)) {
            handleRecoveryPhrase(o);
        }
    }

    /**
     * Ph6 M1: {@code !zff stop} and friends are handled <b>by the mod</b>, so a stuck agent is not
     * required to be responsive for the client to stop working. This is the last-resort brake the
     * player types in chat.
     */
    private static void handleRecoveryPhrase(JsonObject line) {
        String text = line.get("text").getAsString().trim();
        if (!text.startsWith("!zff")) {
            return;
        }
        String rest = text.substring(4).trim();
        String[] parts = rest.isEmpty() ? new String[]{"help"} : rest.split("\\s+", 2);
        String verb = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";
        try {
            switch (verb) {
                case "stop", "halt", "stop_all" -> {
                    JsonObject out = StopAll.run(arg.isEmpty() ? "!zff stop" : "!zff stop: " + arg);
                    JsonObject event = new JsonObject();
                    event.addProperty("by", "!zff stop");
                    event.addProperty("source", "mod");
                    if (out != null && out.has("jobsCancelled")) {
                        event.addProperty("jobsCancelled", out.get("jobsCancelled").getAsInt());
                    }
                    EventJournal.push("zff_stop", event);
                }
                case "save" -> Tools.saveWorld();
                case "status" -> {
                    JsonObject event = new JsonObject();
                    event.addProperty("backend", Backend.label());
                    event.addProperty("player", BridgeRuntime.playerName);
                    event.addProperty("source", "!zff status");
                    EventJournal.push("zff_status", event);
                }
                default -> EventJournal.push("zff_help", new JsonObject());
            }
        } catch (Throwable t) {
            System.out.println("[zff] recovery phrase '" + text + "' failed: " + t);
        }
    }

    /**
     * Ph6 §2.4: the copy that goes into the mod store. Unlike {@link #history(int)} this one is not
     * truncated by total JSON size (it is a file, not a tool answer), only by a per-line cap so a mod
     * that prints a 50 KB line cannot make the store file explode.
     */
    public static synchronized JsonArray storeHistory(int limit) {
        int n = MathUtil.clamp(limit, 1, MAX);
        JsonArray out = new JsonArray();
        int skip = Math.max(0, MESSAGES.size() - n);
        Iterator<JsonObject> it = MESSAGES.iterator();
        int i = 0;
        while (it.hasNext()) {
            JsonObject o = it.next();
            if (i++ < skip) {
                continue;
            }
            JsonObject copy = o.deepCopy();
            if (copy.has("text")) {
                String text = copy.get("text").getAsString();
                if (text.length() > 500) {
                    copy.addProperty("text", text.substring(0, 500) + "...");
                }
            }
            out.add(copy);
        }
        return out;
    }

    public static synchronized JsonArray history(int limit) {        int n = MathUtil.clamp(limit, 1, MAX);
        JsonArray out = new JsonArray();
        int skip = Math.max(0, MESSAGES.size() - n);
        Iterator<JsonObject> it = MESSAGES.iterator();
        int i = 0;
        while (it.hasNext()) {
            JsonObject o = it.next();
            if (i++ >= skip) {
                JsonObject copy = o.deepCopy();
                // Keep the ring buffer bounded even when a mod prints very long chat lines.
                if (copy.has("text")) {
                    String text = copy.get("text").getAsString();
                    if (text.length() > 1_000) {
                        copy.addProperty("text", text.substring(0, 1_000) + "...");
                    }
                }
                out.add(copy);
            }
        }
        while (out.size() > 1 && Json.GSON.toJson(out).length() > 28_000) {
            out.remove(0);
        }
        return out;
    }

    /** Runs on the client thread. */
    public static JsonObject send(String message) {        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.connection == null) {
            throw Errors.notLoaded("not connected to a world");
        }
        String text = sanitizeChat(message, false);
        if (text.isEmpty()) {
            throw new Errors.BridgeException(Errors.INTERNAL, "message is empty");
        }
        if (text.length() > 256) {
            text = text.substring(0, 256);
        }
        if (text.startsWith("/")) {
            return command(text);
        }
        player.connection.sendChat(text);
        add("chat", player.getName().getString(), text, true);
        JsonObject o = Json.ok();
        o.addProperty("sent", true);
        o.addProperty("text", text);
        o.add("history", history(10));
        return o;
    }

    /** Runs on the client thread. */
    public static JsonObject command(String commandLine) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.connection == null) {
            throw Errors.notLoaded("not connected to a world");
        }
        String line = sanitizeChat(commandLine, true);
        if (line.startsWith("/")) {
            line = line.substring(1);
        }
        if (line.isEmpty()) {
            throw new Errors.BridgeException(Errors.INTERNAL, "command is empty");
        }
        if (line.length() > 256) {
            line = line.substring(0, 256);
        }
        boolean known = isKnownCommand(player.connection, line);
        player.connection.sendCommand(line);
        add("command", player.getName().getString(), "/" + line, true);
        JsonObject o = Json.ok();
        o.addProperty("sent", true);
        o.addProperty("command", "/" + line);
        o.addProperty("known", known);
        if (!known) {
            o.addProperty("note", "the command is not in the current client command tree; the server may still accept it");
        }
        o.add("history", history(10));
        return o;
    }

    /**
     * 2026-11-03: a multiplayer server kicked the client with "Illegal characters in chat".
     * Strip control characters, section signs, JS line separators and unpaired surrogates before
     * the string is handed to sendChat/sendCommand.
     */
    private static String sanitizeChat(String raw, boolean command) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            int width = Character.charCount(cp);
            i += width;
            if (cp == 0x00A7 || cp == 0x2028 || cp == 0x2029 || cp < 0x20 || cp == 0x7F
                    || (cp >= 0xD800 && cp <= 0xDFFF)) {
                out.append(' ');
                continue;
            }
            out.appendCodePoint(cp);
        }
        String s = out.toString().replaceAll("\\s+", " ").trim();
        if (s.length() > 256) {
            s = s.substring(0, 256);
        }
        return s;
    }

    private static boolean isKnownCommand(ClientPacketListener connection, String line) {
        try {
            var dispatcher = connection.getCommands();
            if (dispatcher == null) {
                return false;
            }
            String root = line.split("\\s+", 2)[0];
            if (root.contains(":")) {
                root = root.substring(root.indexOf(':') + 1);
            }
            return dispatcher.getRoot().getChild(root) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
