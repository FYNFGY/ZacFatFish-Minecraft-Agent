package dev.mcp.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;

/** Small JSON helpers shared by every tool. */
public final class Json {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {
    }

    public static JsonObject obj() {
        return new JsonObject();
    }

    public static JsonObject obj(String key, Object value) {
        JsonObject o = new JsonObject();
        put(o, key, value);
        return o;
    }

    public static void put(JsonObject o, String key, Object value) {
        if (value == null) {
            o.add(key, null);
        } else if (value instanceof JsonElement e) {
            o.add(key, e);
        } else if (value instanceof Number n) {
            o.addProperty(key, n);
        } else if (value instanceof Boolean b) {
            o.addProperty(key, b);
        } else {
            o.addProperty(key, String.valueOf(value));
        }
    }

    public static JsonObject error(String code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", code);
        if (message != null) {
            o.addProperty("message", message);
        }
        return o;
    }

    public static JsonObject ok() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    public static JsonElement parse(String s) {
        return JsonParser.parseString(s);
    }

    public static String str(JsonObject o, String key, String def) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsString();
    }

    public static String requireStr(JsonObject o, String key) {
        String v = str(o, key, null);
        if (v == null || v.isBlank()) {
            throw new Errors.BridgeException(Errors.INTERNAL, "missing argument: " + key);
        }
        return v;
    }

    public static int i(JsonObject o, String key, int def) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsInt();
    }

    public static Integer iOrNull(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsInt();
    }

    public static double d(JsonObject o, String key, double def) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsDouble();
    }

    public static boolean b(JsonObject o, String key, boolean def) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsBoolean();
    }

    public static JsonObject sub(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static JsonArray arr(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    public static int utf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    public static double round(double d, int places) {
        double scale = Math.pow(10, places);
        return Math.round(d * scale) / scale;
    }

    public static JsonArray intArray(int... values) {
        JsonArray a = new JsonArray();
        for (int v : values) {
            a.add(v);
        }
        return a;
    }

    public static JsonArray doubleArray(double... values) {
        JsonArray a = new JsonArray();
        for (double v : values) {
            a.add(round(v, 3));
        }
        return a;
    }
}
