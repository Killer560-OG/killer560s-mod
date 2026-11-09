package com.killer560.hub.carrytracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.killer560.hub.runsummary.RunRecord;
import com.killer560.hub.shared.RecordCatalog;
import com.killer560.hub.shared.SharedRecords;
import com.killer560.hub.shared.SharedWriter;
import com.killer560.hub.dungeonalerts.DungeonAlertsFeature;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Carry Tracker (featG-1109; Athen's carrier tool): when a dungeon run ends with a tracked player in the party, that player's
 * run count goes up by one and the run goes in a history. Both are RECORDS ({@link RecordCatalog#CARRY}, per account in the shared
 * store, so two instances add to the same count). Everything that touches the disk runs on {@link SharedWriter}'s one thread;
 * the tab reads the in-memory copy, which is empty until {@link #warm} has loaded the file.
 */
public final class CarryTracker {

    /** One finished run in the history, newest last in the file. */
    public record Entry(long endedAtMs, String player, String floor, long totalMs, int score) {
    }

    private static JsonObject root = new JsonObject();
    private static volatile boolean loaded;
    private static volatile boolean warming;
    private static volatile int rev;

    private CarryTracker() {
    }

    /** Called by RunSummaryFeature when it stores a finished run. */
    public static void onRun(RunRecord r) {
        if (!CarryTrackerConfig.getInstance().isEnabled()) {
            return;
        }
        List<String> names = new ArrayList<>();
        r.party().forEach(m -> names.add(m.name()));
        record(r.endedAtMs(), r.floorLabel(), r.totalMs(), r.hypixelScore() >= 0 ? r.hypixelScore() : r.estimatedScore(), names);
    }

    /** Counts one run for every tracked name in {@code party} (case does not matter). */
    static void record(long endedAtMs, String floor, long totalMs, int score, List<String> party) {
        CarryTrackerConfig cfg = CarryTrackerConfig.getInstance();
        List<String> hit = new ArrayList<>();
        for (String n : party) {
            if (n != null && cfg.isTracked(n)) {
                hit.add(n);
            }
        }
        if (hit.isEmpty()) {
            return;
        }
        List<String> reached = new ArrayList<>();
        update(r -> {
            JsonObject counts = obj(r, "counts");
            JsonArray log = array(r, "log");
            for (String n : hit) {
                String key = n.toLowerCase(Locale.ROOT);
                long before = counts.has(key) ? counts.get(key).getAsLong() : 0L;
                counts.addProperty(key, before + 1);
                int target = cfg.getRuns(n);
                if (before < target && before + 1 >= target) {
                    reached.add(n);
                }
                JsonObject e = new JsonObject();
                e.addProperty("endedAtMs", endedAtMs);
                e.addProperty("player", n);
                e.addProperty("floor", floor);
                e.addProperty("totalMs", totalMs);
                e.addProperty("score", score);
                log.add(e);
            }
        }, () -> reached.forEach(CarryTracker::alert));
    }

    /** A client reached their target: a title, a sound and one chat line, then removal when Remove When Done is on. */
    private static void alert(String name) {
        CarryTrackerConfig cfg = CarryTrackerConfig.getInstance();
        int n = cfg.getRuns(name);
        String line = "Stop carrying " + name + ": " + n + "/" + n + " runs done";
        DungeonAlertsFeature.showTitle("§aStop carrying " + name, "§7" + n + "/" + n + " runs done", 0, 40, 10);
        DungeonAlertsFeature.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.5f);
        ModChat.send("Carry Tracker", ModChat.text(line));
        alerts++;
        lastAlert = line;
        if (cfg.isRemoveWhenDone()) {
            cfg.remove(name);
            cfg.save();
            reset(name);
        }
    }

    /** Puts a name's run count back to 0 (a client added again starts fresh). */
    public static void reset(String name) {
        update(r -> obj(r, "counts").remove(name.toLowerCase(Locale.ROOT)), null);
    }

    /** Changes so far (the HUD rebuilds its rows when it moves). */
    public static int rev() { return rev; }

    // testkit readouts
    private static volatile int alerts;
    private static volatile String lastAlert = "";

    static int alertsForTest() { return alerts; }

    static String lastAlertForTest() { return lastAlert; }

    /** Loads the records once on the writer thread (a no-op afterwards), then runs {@code done} on the client thread (the tab rebuilds with the counts). */
    public static void warm(Runnable done) {
        if (!loaded && !warming) {
            warming = true;
            update(r -> { }, done);
        }
    }

    /** Runs {@code change} on the writer thread over the loaded file, saves (merging with the disk) and keeps the merged copy. */
    private static void update(java.util.function.Consumer<JsonObject> change, Runnable done) {
        SharedWriter.submit(() -> {
            var file = SharedRecords.file(RecordCatalog.CARRY);
            if (!loaded) {
                JsonObject disk = SharedRecords.load(file);
                synchronized (CarryTracker.class) {
                    root = disk;
                    loaded = true;
                }
            }
            JsonObject snap;
            synchronized (CarryTracker.class) {
                change.accept(root);
                snap = root.deepCopy();
            }
            JsonObject merged = SharedRecords.save(file, snap);
            synchronized (CarryTracker.class) {
                root = merged;
            }
            rev++;
            if (done != null) {
                Minecraft.getInstance().execute(done);
            }
        });
    }

    /** Runs counted for {@code name} so far (0 until {@link #warm} has loaded). */
    public static synchronized long count(String name) {
        JsonObject c = root.get("counts") instanceof JsonObject o ? o : null;
        String key = name.toLowerCase(Locale.ROOT);
        return c != null && c.has(key) ? c.get(key).getAsLong() : 0L;
    }

    /** The newest {@code max} history entries, newest first. */
    public static synchronized List<Entry> recent(int max) {
        List<Entry> out = new ArrayList<>();
        JsonArray log = root.get("log") instanceof JsonArray a ? a : new JsonArray();
        for (int i = log.size() - 1; i >= 0 && out.size() < max; i--) {
            JsonObject e = log.get(i).getAsJsonObject();
            out.add(new Entry(e.get("endedAtMs").getAsLong(), e.get("player").getAsString(), e.get("floor").getAsString(),
                    e.get("totalMs").getAsLong(), e.get("score").getAsInt()));
        }
        return out;
    }

    private static JsonObject obj(JsonObject r, String key) {
        if (!(r.get(key) instanceof JsonObject)) {
            r.add(key, new JsonObject());
        }
        return r.getAsJsonObject(key);
    }

    private static JsonArray array(JsonObject r, String key) {
        if (!(r.get(key) instanceof JsonArray)) {
            r.add(key, new JsonArray());
        }
        return r.getAsJsonArray(key);
    }

    // ---- testkit hooks ----

    static void recordForTest(String floor, long totalMs, int score, List<String> party) {
        record(System.currentTimeMillis(), floor, totalMs, score, party);
    }

    static void drainForTest() { SharedWriter.drain(10000); }

    /** Forgets the in-memory copy and loads the file again (what a restart does). */
    static void reloadForTest() {
        SharedWriter.drain(10000);
        synchronized (CarryTracker.class) {
            root = new JsonObject();
            loaded = false;
            warming = false;
        }
        update(r -> { }, null);
        SharedWriter.drain(10000);
    }
}
