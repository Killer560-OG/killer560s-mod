package com.killer560.hub.lfg;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One Custom Party Finder listing as the relay serves it ({@code /lfg/listings}, {@code /lfg/me}), plus the two request
 * shapes. Everything is validated again here even though the relay validated it: a name that will ever reach a command
 * must be a Minecraft name ({@code [A-Za-z0-9_]{1,16}}, so no space or second argument can ride along), and an entry that
 * fails any check is dropped whole rather than shown half-trusted.
 */
public record LfgListing(String id, String leader, String leaderUuid, boolean verified, LfgCategory category,
                         String activity, String note, Map<String, Integer> reqs, int size, int max, String source,
                         long ageMs) {

    public static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    static final Pattern ID = Pattern.compile("^[a-z0-9]{8,32}$");
    private static final Pattern ACTIVITY = Pattern.compile("^[A-Za-z0-9 +\\-]{1,24}$");
    private static final Pattern REQ_KEY = Pattern.compile("^[A-Za-z]{1,20}$");

    /** A request waiting on the player's own listing: someone asked to join. */
    public record Request(String id, String joiner, String joinerUuid, boolean verified, long ageMs) {
        static Request parse(JsonObject o) {
            String id = str(o, "id");
            String joiner = str(o, "joiner");
            if (id == null || !ID.matcher(id).matches() || joiner == null || !NAME.matcher(joiner).matches()) {
                return null;
            }
            return new Request(id, joiner, strOr(o, "joinerUuid", ""), bool(o, "verified"), lng(o, "ageMs"));
        }
    }

    /** One of the player's own join requests, and what its leader did with it. */
    public record Outgoing(String id, String listingId, String leader, LfgCategory category, String activity, String state,
                           long ageMs) {
        static Outgoing parse(JsonObject o) {
            String id = str(o, "id");
            String leader = str(o, "leader");
            String state = str(o, "state");
            if (id == null || !ID.matcher(id).matches() || leader == null || !NAME.matcher(leader).matches()
                    || !("pending".equals(state) || "accepted".equals(state) || "declined".equals(state))) {
                return null;
            }
            return new Outgoing(id, strOr(o, "listingId", ""), leader, LfgCategory.fromId(str(o, "category")),
                    strOr(o, "activity", ""), state, lng(o, "ageMs"));
        }
    }

    /** @return the listing, or null when any field is missing or out of shape. */
    public static LfgListing parse(JsonObject o) {
        if (o == null) {
            return null;
        }
        String id = str(o, "id");
        String leader = str(o, "leader");
        LfgCategory category = LfgCategory.fromId(str(o, "category"));
        String activity = str(o, "activity");
        if (id == null || !ID.matcher(id).matches() || leader == null || !NAME.matcher(leader).matches()
                || category == null || activity == null || !ACTIVITY.matcher(activity).matches()) {
            return null;
        }
        int max = (int) lng(o, "max");
        int size = (int) lng(o, "size");
        if (max < 2 || max > category.maxParty || size < 1 || size > max) {
            return null;
        }
        Map<String, Integer> reqs = new LinkedHashMap<>();
        JsonElement r = o.get("reqs");
        if (r != null && r.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : r.getAsJsonObject().entrySet()) {
                if (reqs.size() >= 6 || !REQ_KEY.matcher(e.getKey()).matches() || !e.getValue().isJsonPrimitive()
                        || !e.getValue().getAsJsonPrimitive().isNumber()) {
                    continue;
                }
                double v;
                try {
                    v = e.getValue().getAsDouble();
                } catch (NumberFormatException ex) {
                    continue;
                }
                if (v >= 0 && v <= 1_000_000 && v == Math.rint(v)) {
                    reqs.put(e.getKey(), (int) v);
                }
            }
        }
        String note = strOr(o, "note", "").replace("§", "");
        if (note.length() > 80) {
            note = note.substring(0, 80);
        }
        return new LfgListing(id, leader, strOr(o, "leaderUuid", ""), bool(o, "verified"), category, activity, note,
                Collections.unmodifiableMap(reqs), size, max, "hypixel".equals(str(o, "source")) ? "hypixel" : "mod",
                lng(o, "ageMs"));
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    static String strOr(JsonObject o, String key, String def) {
        String s = str(o, key);
        return s == null ? def : s;
    }

    static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    static long lng(JsonObject o, String key) {
        JsonElement e = o.get(key);
        try {
            return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsLong() : 0L;
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
