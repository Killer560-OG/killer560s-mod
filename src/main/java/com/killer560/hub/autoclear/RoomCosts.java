package com.killer560.hub.autoclear;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.shared.RecordCatalog;
import com.killer560.hub.shared.SharedRecords;
import com.killer560.hub.shared.SharedWriter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Learned Room Costs (killer560, 2026-11-23): how long each room really took Auto Clear, by room name, kept in the shared
 * store ({@link RecordCatalog#ROOM_COSTS}: per room the last {@value #KEEP} clear times in seconds and a count). Recorded
 * where {@link RoomClock} ends a room as cleared; read by the clear-time estimate and Dungeons Autopilot's order
 * planner, which used one guess for every room. A room with fewer than {@value #MIN_SAMPLES} clears keeps the guess, so
 * nothing is invented. The disk is read and written on {@link SharedWriter}, never on the tick.
 */
public final class RoomCosts {

    static final int KEEP = 9;
    static final int MIN_SAMPLES = 3;
    /** A "clear" shorter or longer than this was not a real room clear (a stale clock, a stuck run). */
    static final double MIN_SECONDS = 1.0;
    static final double MAX_SECONDS = 300.0;
    private static final int MAX_ROOMS = 200;

    private static final Map<String, List<Double>> SAMPLES = new HashMap<>();
    private static final Map<String, Integer> COUNT = new HashMap<>();
    /** room -> median, only for rooms with {@link #MIN_SAMPLES} or more; rebuilt on each change. */
    private static volatile Map<String, Double> medians = Map.of();
    private static boolean loadStarted = false;

    private RoomCosts() {
    }

    /** The seconds {@code room} takes to clear: its median once it has {@link #MIN_SAMPLES} clears, else {@code guess}. */
    public static double seconds(String room, double guess) {
        if (!AutoClearConfig.getInstance().isLearnedRoomCosts()) {
            return guess;
        }
        ensureLoaded();
        Double m = room == null ? null : medians.get(room);
        return m != null ? m : guess;
    }

    /** How many clears the room's figure rests on (0 unknown). */
    public static int samples(String room) {
        synchronized (SAMPLES) {
            return COUNT.getOrDefault(room, 0);
        }
    }

    /** A room was cleared in {@code seconds}: kept, and the record saved off this thread. */
    public static void record(String room, double seconds) {
        if (room == null || !(seconds >= MIN_SECONDS && seconds <= MAX_SECONDS)) {
            return;
        }
        ensureLoaded();
        JsonObject snapshot;
        synchronized (SAMPLES) {
            if (!SAMPLES.containsKey(room) && SAMPLES.size() >= MAX_ROOMS) {
                return;
            }
            List<Double> s = SAMPLES.computeIfAbsent(room, k -> new ArrayList<>());
            s.add(seconds);
            if (s.size() > KEEP) {
                s.remove(0);
            }
            COUNT.merge(room, 1, Integer::sum);
            rebuild();
            snapshot = toJson();
        }
        SharedWriter.submit(() -> merge(SharedRecords.save(SharedRecords.file(RecordCatalog.ROOM_COSTS), snapshot)));
    }

    /** Testkit: forgets what is in memory and reads the file again (what a restart does). */
    public static void reloadForTest() {
        SharedWriter.drain(5000);
        synchronized (SAMPLES) {
            SAMPLES.clear();
            COUNT.clear();
            medians = Map.of();
        }
        merge(SharedRecords.load(SharedRecords.file(RecordCatalog.ROOM_COSTS)));
    }

    private static void ensureLoaded() {
        synchronized (SAMPLES) {
            if (loadStarted) {
                return;
            }
            loadStarted = true;
        }
        SharedWriter.submit(() -> merge(SharedRecords.load(SharedRecords.file(RecordCatalog.ROOM_COSTS))));
    }

    /** Takes the disk's figures for rooms this session has not recorded in (so a late load never drops a new clear). */
    private static void merge(JsonObject disk) {
        if (disk == null) {
            return;
        }
        synchronized (SAMPLES) {
            for (Map.Entry<String, JsonElement> e : disk.entrySet()) {
                if (SAMPLES.containsKey(e.getKey()) || !e.getValue().isJsonObject() || SAMPLES.size() >= MAX_ROOMS) {
                    continue;
                }
                JsonObject o = e.getValue().getAsJsonObject();
                List<Double> s = new ArrayList<>();
                if (o.has("s") && o.get("s").isJsonArray()) {
                    for (JsonElement x : o.getAsJsonArray("s")) {
                        s.add(x.getAsDouble());
                    }
                }
                SAMPLES.put(e.getKey(), s);
                COUNT.put(e.getKey(), o.has("n") ? o.get("n").getAsInt() : s.size());
            }
            rebuild();
        }
    }

    private static void rebuild() {
        Map<String, Double> m = new HashMap<>();
        for (Map.Entry<String, List<Double>> e : SAMPLES.entrySet()) {
            if (e.getValue().size() >= MIN_SAMPLES) {
                m.put(e.getKey(), median(e.getValue()));
            }
        }
        medians = m;
    }

    static double median(List<Double> v) {
        List<Double> c = new ArrayList<>(v);
        Collections.sort(c);
        int n = c.size();
        return n % 2 == 1 ? c.get(n / 2) : (c.get(n / 2 - 1) + c.get(n / 2)) / 2.0;
    }

    private static JsonObject toJson() {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, List<Double>> e : SAMPLES.entrySet()) {
            JsonObject r = new JsonObject();
            JsonArray a = new JsonArray();
            for (double d : e.getValue()) {
                a.add(Math.round(d * 10) / 10.0);
            }
            r.add("s", a);
            r.addProperty("n", COUNT.getOrDefault(e.getKey(), a.size()));
            o.add(e.getKey(), r);
        }
        return o;
    }
}
