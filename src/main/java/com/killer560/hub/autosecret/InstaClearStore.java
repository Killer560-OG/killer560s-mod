package com.killer560.hub.autosecret;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The evidence {@link InstaClearTracker} has gathered, and the rule that turns it into "known to insta clear".
 *
 * <p>Pure Java plus Gson - no Minecraft type - so the decision logic is the same thing whether it is fed by a real
 * room entry or by a test's fake one.
 *
 * <p>One {@link Obs} per room entry. Only three outcomes count: {@link Outcome#INSTA} is a success, {@link
 * Outcome#KILLED} and {@link Outcome#NO_CLEAR} are failures, and everything else (no starred mobs, room already
 * cleared, no dungeon map, flip not observable) is kept for the record but proves nothing either way. A manual
 * {@code verdict} set by killer560 with {@code /autosecret instaclear mark} overrides the measured outcome.
 *
 * <p>The rule ({@link #known}): at least {@link #minSuccesses()} successes and ZERO failures for that exact room and
 * entry key. One failure ever disqualifies the entry, because Auto Secret is only allowed to try an insta clear it
 * knows will work.
 */
final class InstaClearStore {

    enum Outcome {
        /** The room flipped to cleared on the map while starred mobs were still alive in it. Success. */
        INSTA,
        /** The room flipped only after every starred mob seen in it was gone. Failure (a real clear). */
        KILLED,
        /** The room had live starred mobs and did not flip within the window. Failure. */
        NO_CLEAR,
        /** Not counted: no starred mob was ever seen in the room. */
        NO_STARS,
        /** Not counted: the map already showed the room cleared when he entered. */
        ALREADY_CLEARED,
        /** Not counted: no calibrated dungeon map, so a flip could not be seen (the sim, p3sim, boss). */
        NO_MAP,
        /** Not counted: the flip happened while he was too far from the room to see its mobs. */
        UNOBSERVED,
        /** Manual verdict only: "this was not an insta clear" - counted as a failure. */
        NOT_INSTA,
        /** Manual verdict only: drop this entry from the counts. */
        IGNORE;

        boolean success() {
            return this == INSTA;
        }

        boolean failure() {
            return this == KILLED || this == NO_CLEAR || this == NOT_INSTA;
        }
    }

    /** One room entry and what followed it. Field names are the JSON keys. */
    static final class Obs {
        long t;              // epoch ms of the entry
        String floor;        // "F7", "M7", ... or null
        String from;         // room he came from, or null
        String fromState;    // map state of that room at the entry
        String before;       // map state of THIS room at the entry
        String method;       // etherwarp / teleport / walk
        String driver;       // manual / autosecret / autoroutes / interactivemap
        double travel;       // blocks moved by the move that entered the room
        int skip;            // room tiles between the from tile and the entered tile (0 = adjacent)
        int stars;           // distinct starred stands seen in the room after the entry
        int kills;           // of those, how many disappeared while he was close enough to see it
        int aliveAtFlip = -1;
        long flipMs = -1;    // ms from entry to the map flip, -1 when it never flipped
        Outcome outcome;
        Outcome verdict;     // manual override, or null

        Outcome effective() {
            return verdict != null ? verdict : outcome;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("t", t);
            if (floor != null) o.addProperty("floor", floor);
            if (from != null) o.addProperty("from", from);
            if (fromState != null) o.addProperty("fromState", fromState);
            if (before != null) o.addProperty("before", before);
            o.addProperty("method", method);
            o.addProperty("driver", driver);
            o.addProperty("travel", Math.round(travel * 10.0) / 10.0);
            o.addProperty("skip", skip);
            o.addProperty("stars", stars);
            o.addProperty("kills", kills);
            o.addProperty("aliveAtFlip", aliveAtFlip);
            o.addProperty("flipMs", flipMs);
            o.addProperty("outcome", outcome == null ? null : outcome.name());
            if (verdict != null) o.addProperty("verdict", verdict.name());
            return o;
        }

        static Obs fromJson(JsonObject o) {
            Obs b = new Obs();
            b.t = ConfigJson.getLong(o, "t", 0L);
            b.floor = str(o, "floor");
            b.from = str(o, "from");
            b.fromState = str(o, "fromState");
            b.before = str(o, "before");
            b.method = str(o, "method");
            b.driver = str(o, "driver");
            b.travel = ConfigJson.getDouble(o, "travel", 0.0);
            b.skip = ConfigJson.getInt(o, "skip", -1);
            b.stars = ConfigJson.getInt(o, "stars", 0);
            b.kills = ConfigJson.getInt(o, "kills", 0);
            b.aliveAtFlip = ConfigJson.getInt(o, "aliveAtFlip", -1);
            b.flipMs = ConfigJson.getLong(o, "flipMs", -1L);
            b.outcome = outcome(str(o, "outcome"));
            b.verdict = outcome(str(o, "verdict"));
            return b;
        }

        private static String str(JsonObject o, String key) {
            JsonElement e = o.get(key);
            return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
        }

        private static Outcome outcome(String name) {
            if (name == null) {
                return null;
            }
            try {
                return Outcome.valueOf(name);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** Per room, per entry key, oldest first. A key keeps at most this many (the oldest go first). */
    static final int MAX_PER_KEY = 100;
    static final int DEFAULT_MIN_SUCCESSES = 2;
    static final int DEFAULT_WINDOW_SECONDS = 15;

    /** No HTML escaping: entry keys hold '=', which Gson would otherwise write as a unicode escape and make the file unreadable. */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Map<String, Map<String, List<Obs>>> rooms = new TreeMap<>();
    private int minSuccesses = DEFAULT_MIN_SUCCESSES;
    private int windowSeconds = DEFAULT_WINDOW_SECONDS;
    private Obs last;
    private String lastRoom;
    private String lastKey;

    synchronized int minSuccesses() {
        return minSuccesses;
    }

    synchronized void setMinSuccesses(int n) {
        minSuccesses = Math.max(1, Math.min(50, n));
    }

    synchronized int windowSeconds() {
        return windowSeconds;
    }

    synchronized void setWindowSeconds(int s) {
        windowSeconds = Math.max(2, Math.min(120, s));
    }

    synchronized void add(String room, String key, Obs obs) {
        List<Obs> list = rooms.computeIfAbsent(room, r -> new TreeMap<>()).computeIfAbsent(key, k -> new ArrayList<>());
        list.add(obs);
        while (list.size() > MAX_PER_KEY) {
            list.remove(0);
        }
        last = obs;
        lastRoom = room;
        lastKey = key;
    }

    synchronized void clear() {
        rooms.clear();
        last = null;
        lastRoom = null;
        lastKey = null;
    }

    synchronized Obs last() {
        return last;
    }

    synchronized String lastRoom() {
        return lastRoom;
    }

    synchronized String lastKey() {
        return lastKey;
    }

    synchronized int size() {
        int n = 0;
        for (Map<String, List<Obs>> keys : rooms.values()) {
            for (List<Obs> l : keys.values()) {
                n += l.size();
            }
        }
        return n;
    }

    /** @return {@code {successes, failures, notCounted}} for one room + entry. */
    synchronized int[] counts(String room, String key) {
        int[] c = new int[3];
        Map<String, List<Obs>> keys = rooms.get(room);
        List<Obs> list = keys == null ? null : keys.get(key);
        if (list != null) {
            for (Obs o : list) {
                tally(c, o);
            }
        }
        return c;
    }

    private static void tally(int[] c, Obs o) {
        Outcome e = o.effective();
        if (e != null && e.success()) {
            c[0]++;
        } else if (e != null && e.failure()) {
            c[1]++;
        } else {
            c[2]++;
        }
    }

    /** THE RULE: {@code >= minSuccesses} successes and no failure at all, for exactly this room and entry key. */
    synchronized boolean known(String room, String key) {
        if (room == null || key == null) {
            return false;
        }
        int[] c = counts(room, key);
        return c[0] >= minSuccesses && c[1] == 0;
    }

    /** Entry keys that pass {@link #known} for this room, most successes first. */
    synchronized List<String> knownKeys(String room) {
        List<String> out = new ArrayList<>();
        Map<String, List<Obs>> keys = room == null ? null : rooms.get(room);
        if (keys == null) {
            return out;
        }
        Map<String, Integer> successes = new LinkedHashMap<>();
        for (String key : keys.keySet()) {
            if (known(room, key)) {
                out.add(key);
                successes.put(key, counts(room, key)[0]);
            }
        }
        out.sort((a, b) -> Integer.compare(successes.get(b), successes.get(a)));
        return out;
    }

    /** The newest observation recorded for a room, as {@code key|json}, or null. */
    synchronized String newestIn(String room) {
        Map<String, List<Obs>> keys = rooms.get(room);
        if (keys == null) {
            return null;
        }
        String bestKey = null;
        Obs best = null;
        for (Map.Entry<String, List<Obs>> e : keys.entrySet()) {
            for (Obs o : e.getValue()) {
                if (best == null || o.t >= best.t) {
                    best = o;
                    bestKey = e.getKey();
                }
            }
        }
        return best == null ? null : bestKey + "|" + best.toJson();
    }

    synchronized List<String> roomNames() {
        return new ArrayList<>(rooms.keySet());
    }

    synchronized List<String> keys(String room) {
        Map<String, List<Obs>> keys = rooms.get(room);
        return keys == null ? new ArrayList<>() : new ArrayList<>(keys.keySet());
    }

    /** Median ms from entry to flip over the SUCCESSES of a room (key null) or one entry, or -1 with none. */
    synchronized long medianFlipMs(String room, String key) {
        Map<String, List<Obs>> keys = rooms.get(room);
        if (keys == null) {
            return -1;
        }
        List<Long> times = new ArrayList<>();
        for (Map.Entry<String, List<Obs>> e : keys.entrySet()) {
            if (key != null && !key.equals(e.getKey())) {
                continue;
            }
            for (Obs o : e.getValue()) {
                Outcome eff = o.effective();
                if (eff != null && eff.success() && o.flipMs >= 0) {
                    times.add(o.flipMs);
                }
            }
        }
        if (times.isEmpty()) {
            return -1;
        }
        Collections.sort(times);
        int n = times.size();
        return n % 2 == 1 ? times.get(n / 2) : (times.get(n / 2 - 1) + times.get(n / 2)) / 2;
    }

    // ------------------------------------------------------------------------------------------- persistence

    synchronized JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject settings = new JsonObject();
        settings.addProperty("minSuccesses", minSuccesses);
        settings.addProperty("windowSeconds", windowSeconds);
        root.add("settings", settings);
        JsonObject roomsJson = new JsonObject();
        for (Map.Entry<String, Map<String, List<Obs>>> r : rooms.entrySet()) {
            JsonObject keysJson = new JsonObject();
            for (Map.Entry<String, List<Obs>> k : r.getValue().entrySet()) {
                JsonArray arr = new JsonArray();
                for (Obs o : k.getValue()) {
                    arr.add(o.toJson());
                }
                keysJson.add(k.getKey(), arr);
            }
            roomsJson.add(r.getKey(), keysJson);
        }
        root.add("rooms", roomsJson);
        return root;
    }

    /** Replaces everything held with what {@code root} says; a malformed piece is skipped, never the whole file. */
    synchronized void fromJson(JsonObject root) {
        clear();
        minSuccesses = DEFAULT_MIN_SUCCESSES;
        windowSeconds = DEFAULT_WINDOW_SECONDS;
        if (root == null) {
            return;
        }
        JsonElement s = root.get("settings");
        if (s != null && s.isJsonObject()) {
            setMinSuccesses(ConfigJson.getInt(s.getAsJsonObject(), "minSuccesses", DEFAULT_MIN_SUCCESSES));
            setWindowSeconds(ConfigJson.getInt(s.getAsJsonObject(), "windowSeconds", DEFAULT_WINDOW_SECONDS));
        }
        JsonElement r = root.get("rooms");
        if (r == null || !r.isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> room : r.getAsJsonObject().entrySet()) {
            if (!room.getValue().isJsonObject()) {
                continue;
            }
            for (Map.Entry<String, JsonElement> key : room.getValue().getAsJsonObject().entrySet()) {
                if (!key.getValue().isJsonArray()) {
                    continue;
                }
                for (JsonElement o : key.getValue().getAsJsonArray()) {
                    if (o.isJsonObject()) {
                        try {
                            add(room.getKey(), key.getKey(), Obs.fromJson(o.getAsJsonObject()));
                        } catch (RuntimeException ignored) {
                            // one bad observation is dropped, never the file
                        }
                    }
                }
            }
        }
        last = null;
        lastRoom = null;
        lastKey = null;
    }

    /** @return whether the file existed and parsed. A missing file is an empty store, not an error. */
    synchronized boolean load(Path file) throws java.io.IOException {
        if (file == null || !Files.isRegularFile(file)) {
            fromJson(null);
            return false;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        fromJson(JsonParser.parseString(text).getAsJsonObject());
        return true;
    }

    /** Written to a temp file beside it and moved over, so a crash mid-write cannot leave half a file. */
    synchronized void save(Path file) throws java.io.IOException {
        String text = GSON.toJson(toJson());
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
