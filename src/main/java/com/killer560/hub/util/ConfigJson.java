package com.killer560.hub.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * Per-key, never-throwing readers for the mod's {@code killer560smod-*.json} config files (2026-09-15
 * persistence audit). Every {@code XyzConfig.load()} used to read all its keys inside ONE try/catch, so a
 * single malformed or null value (hand-edit, a type changed between versions, a profile from an older
 * build) threw, reset EVERY setting in that file to its default, and the next {@code save()} then wrote
 * those defaults back over the user's real settings. Each getter here falls back to {@code def} for just
 * the one bad/missing key instead, so the rest of the file still loads.
 */
public final class ConfigJson {

    private ConfigJson() {
    }

    private static JsonElement prim(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key)) {
            return null;
        }
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() ? el : null;
    }

    public static boolean getBool(JsonObject obj, String key, boolean def) {
        try {
            JsonElement el = prim(obj, key);
            return el == null ? def : el.getAsBoolean();
        } catch (Exception e) {
            return def;
        }
    }

    public static int getInt(JsonObject obj, String key, int def) {
        try {
            JsonElement el = prim(obj, key);
            return el == null ? def : el.getAsInt();
        } catch (Exception e) {
            return def;
        }
    }

    public static long getLong(JsonObject obj, String key, long def) {
        try {
            JsonElement el = prim(obj, key);
            return el == null ? def : el.getAsLong();
        } catch (Exception e) {
            return def;
        }
    }

    /** NaN/Infinity are treated as malformed and fall back to {@code def}. */
    public static float getFloat(JsonObject obj, String key, float def) {
        try {
            JsonElement el = prim(obj, key);
            if (el == null) {
                return def;
            }
            float v = el.getAsFloat();
            return Float.isFinite(v) ? v : def;
        } catch (Exception e) {
            return def;
        }
    }

    /** NaN/Infinity are treated as malformed and fall back to {@code def}. */
    public static double getDouble(JsonObject obj, String key, double def) {
        try {
            JsonElement el = prim(obj, key);
            if (el == null) {
                return def;
            }
            double v = el.getAsDouble();
            return Double.isFinite(v) ? v : def;
        } catch (Exception e) {
            return def;
        }
    }

    public static String getString(JsonObject obj, String key, String def) {
        try {
            JsonElement el = prim(obj, key);
            return el == null ? def : el.getAsString();
        } catch (Exception e) {
            return def;
        }
    }

    /** Enums are saved by {@link Enum#name()}; unknown/renamed constants fall back to {@code def}.
     *  Case-insensitive so a hand-edited lowercase value still loads. */
    public static <E extends Enum<E>> E getEnum(JsonObject obj, String key, Class<E> type, E def) {
        String s = getString(obj, key, null);
        if (s == null) {
            return def;
        }
        try {
            return Enum.valueOf(type, s.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            try {
                return Enum.valueOf(type, s.trim());
            } catch (Exception e2) {
                return def;
            }
        }
    }

    /** @return the nested object at {@code key}, or {@code null} if missing / not an object. */
    public static JsonObject getObject(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key)) {
            return null;
        }
        JsonElement el = obj.get(key);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    /** @return the nested array at {@code key}, or {@code null} if missing / not an array. */
    public static JsonArray getArray(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key)) {
            return null;
        }
        JsonElement el = obj.get(key);
        return el != null && el.isJsonArray() ? el.getAsJsonArray() : null;
    }
}
