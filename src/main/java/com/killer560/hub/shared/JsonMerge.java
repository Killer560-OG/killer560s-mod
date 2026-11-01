package com.killer560.hub.shared;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Three-way merge of one shared record file (shared-stats-1101). Two game instances can hold the same file open: each
 * keeps the JSON it last read or wrote ({@code base}), changes its own copy ({@code mine}), and on save merges onto
 * whatever is on disk now ({@code disk}, which another instance may have written since). The rules:
 *
 * <ul>
 * <li>An object key or a keyed array element that is in {@code base} but not in {@code mine} was removed here (a reset,
 *     a trim, an expiry), and stays removed.</li>
 * <li>One that is in neither {@code base} nor {@code mine} but is on disk was added by another instance, and is kept.</li>
 * <li>A COUNTER number ({@link Policy#number}) gets this instance's change added to the disk value:
 *     {@code disk + (mine - base)}. So two instances that each counted a session both count.</li>
 * <li>Any other value (a STATE) takes {@code mine} when this instance changed it, else the disk's.</li>
 * <li>MAX and MIN take the larger or smaller of disk and mine (first/last-seen times).</li>
 * </ul>
 *
 * <p>Arrays are atomic values unless the policy names a key for their elements; then they merge like objects keyed by
 * that key, so the same record written by two instances (or imported from two copies of one old file) is kept once.
 * This class depends on Gson only, so the standalone test in {@code tools/shared-records/} runs it without Minecraft.
 */
public final class JsonMerge {

    private JsonMerge() {
    }

    /** Joins path segments: a character no key in these files holds (item names do hold '.'). */
    public static final String SEP = "\u001f";

    public enum NumberMode { STATE, COUNTER, MAX, MIN }

    /** What a record file's fields mean. Paths are the object keys from the root joined by {@link #SEP}, with "[]" for
     *  an element of a keyed array; {@link #matches} takes a pattern written with '.' instead:
     *  {@code buckets.[].items.*.count}. */
    public interface Policy {
        /** The key of an element of the array at {@code path}, or null to treat the array as one value. */
        default Function<JsonElement, String> arrayKey(String path) {
            return null;
        }

        default NumberMode number(String path) {
            return NumberMode.STATE;
        }

        /** A whole-file merge of its own, or null for the field rules. */
        default JsonObject custom(JsonObject disk, JsonObject base, JsonObject mine) {
            return null;
        }

        /** Runs on the merged root before it is written (sort, renumber, recompute a derived field). */
        default void finish(JsonObject merged) {
        }
    }

    public static final Policy DEFAULT = new Policy() {
    };

    /** The merge of the three roots; any of them may be null (no file, nothing loaded yet). Never returns null. */
    public static JsonObject merge(JsonObject disk, JsonObject base, JsonObject mine, Policy policy) {
        JsonObject custom = policy.custom(disk, base, mine);
        if (custom != null) {
            return custom;
        }
        JsonElement out = merge("", disk, base, mine, policy);
        JsonObject root = out != null && out.isJsonObject() ? out.getAsJsonObject() : new JsonObject();
        policy.finish(root);
        return root;
    }

    private static JsonElement merge(String path, JsonElement d, JsonElement b, JsonElement m, Policy p) {
        d = nul(d);
        b = nul(b);
        m = nul(m);
        if (m == null && b != null) {
            return null; // removed here
        }
        if (m == null && b == null) {
            return d == null ? null : d.deepCopy(); // added elsewhere, or never here
        }
        // m != null from here on.
        if (m.isJsonObject() && (d == null || d.isJsonObject()) && (b == null || b.isJsonObject())) {
            return mergeObjects(path, obj(d), obj(b), m.getAsJsonObject(), p);
        }
        if (m.isJsonArray() && (d == null || d.isJsonArray()) && (b == null || b.isJsonArray())) {
            Function<JsonElement, String> key = p.arrayKey(path);
            if (key != null) {
                return mergeArrays(path, arr(d), arr(b), m.getAsJsonArray(), key, p);
            }
        }
        if (isNumber(m) && (d == null || isNumber(d)) && (b == null || isNumber(b))) {
            return mergeNumbers(p.number(path), d, b, m);
        }
        // A STATE value (string, boolean, an un-keyed array, a type change).
        if (b == null || !m.equals(b)) {
            return m.deepCopy();
        }
        return d == null ? null : d.deepCopy(); // unchanged here; the disk's (null = removed elsewhere)
    }

    private static JsonObject mergeObjects(String path, JsonObject d, JsonObject b, JsonObject m, Policy p) {
        if (d == null && b != null && m.equals(b)) {
            return null; // removed elsewhere and untouched here
        }
        Set<String> keys = new LinkedHashSet<>();
        if (d != null) {
            keys.addAll(d.keySet());
        }
        keys.addAll(m.keySet());
        JsonObject out = new JsonObject();
        for (String k : keys) {
            JsonElement v = merge(join(path, k), d == null ? null : d.get(k), b == null ? null : b.get(k), m.get(k), p);
            if (v != null) {
                out.add(k, v);
            }
        }
        return out;
    }

    private static JsonArray mergeArrays(String path, JsonArray d, JsonArray b, JsonArray m,
                                         Function<JsonElement, String> key, Policy p) {
        Map<String, JsonElement> dk = index(d, key);
        Map<String, JsonElement> bk = index(b, key);
        Map<String, JsonElement> mk = index(m, key);
        List<String> order = new ArrayList<>(dk.keySet());
        for (String k : mk.keySet()) {
            if (!dk.containsKey(k)) {
                order.add(k);
            }
        }
        JsonArray out = new JsonArray();
        String elementPath = join(path, "[]");
        for (String k : order) {
            JsonElement v = merge(elementPath, dk.get(k), bk.get(k), mk.get(k), p);
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }

    private static JsonElement mergeNumbers(NumberMode mode, JsonElement d, JsonElement b, JsonElement m) {
        switch (mode) {
            case COUNTER: {
                if (integral(d) && integral(b) && integral(m)) {
                    return new JsonPrimitive(lng(d) + lng(m) - lng(b));
                }
                return new JsonPrimitive(dbl(d) + dbl(m) - dbl(b));
            }
            case MAX:
                return d == null || dbl(m) >= dbl(d) ? m.deepCopy() : d.deepCopy();
            case MIN:
                // 0 is "not set" in these files (BestFriends' firstPartiedAtMs), never a real earliest time.
                if (d == null || dbl(d) == 0) {
                    return m.deepCopy();
                }
                return dbl(m) != 0 && dbl(m) <= dbl(d) ? m.deepCopy() : d.deepCopy();
            default:
                if (b == null || !m.equals(b)) {
                    return m.deepCopy();
                }
                return d == null ? null : d.deepCopy();
        }
    }

    /** Elements by key, first one kept when a key repeats (a file that already held a duplicate loses it here). */
    private static Map<String, JsonElement> index(JsonArray a, Function<JsonElement, String> key) {
        Map<String, JsonElement> out = new LinkedHashMap<>();
        if (a == null) {
            return out;
        }
        int i = 0;
        for (JsonElement e : a) {
            String k;
            try {
                k = key.apply(e);
            } catch (RuntimeException bad) {
                k = null;
            }
            out.putIfAbsent(k == null ? "\u0000unkeyed-" + i + "-" + e : k, e);
            i++;
        }
        return out;
    }

    private static String join(String path, String k) {
        return path.isEmpty() ? k : path + SEP + k;
    }

    private static JsonElement nul(JsonElement e) {
        return e == null || e instanceof JsonNull ? null : e;
    }

    private static JsonObject obj(JsonElement e) {
        return e == null ? null : e.getAsJsonObject();
    }

    private static JsonArray arr(JsonElement e) {
        return e == null ? null : e.getAsJsonArray();
    }

    private static boolean isNumber(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    private static boolean integral(JsonElement e) {
        if (e == null) {
            return true;
        }
        String s = e.getAsJsonPrimitive().getAsString();
        return s.indexOf('.') < 0 && s.indexOf('e') < 0 && s.indexOf('E') < 0;
    }

    private static long lng(JsonElement e) {
        return e == null ? 0L : e.getAsLong();
    }

    private static double dbl(JsonElement e) {
        return e == null ? 0.0 : e.getAsDouble();
    }

    // ---- helpers for policies ----

    /** A key made of the named fields of an object element, joined with '|'. */
    public static Function<JsonElement, String> fields(String... names) {
        return e -> {
            JsonObject o = e.getAsJsonObject();
            StringBuilder sb = new StringBuilder();
            for (String n : names) {
                JsonElement v = o.get(n);
                sb.append(v == null || v.isJsonNull() ? "" : v.isJsonPrimitive() ? v.getAsString() : v.toString()).append('|');
            }
            return sb.toString();
        };
    }

    /** Whether {@code path} matches {@code pattern}, where a '*' segment matches any one key. */
    public static boolean matches(String path, String pattern) {
        String[] a = path.split(SEP, -1);
        String[] b = pattern.split("\\.", -1);
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!b[i].equals("*") && !Objects.equals(a[i], b[i])) {
                return false;
            }
        }
        return true;
    }
}
