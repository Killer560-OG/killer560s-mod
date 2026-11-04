package com.killer560.hub.runsummary;

import com.killer560.hub.shared.RecordCatalog;
import com.killer560.hub.shared.SharedFile;
import com.killer560.hub.shared.SharedRecords;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The persisted run history: {@code config/killer560/dungeons/runs/killer560smod-runs/history.json}.
 * <p>
 * A SUBFOLDER, deliberately - exactly the reason {@code pathfinding/FairySoulStore} lives in
 * {@code config/killer560/skyblock/pathfinding/killer560smod-pathfinding/}: {@code profiles/ProfileManager} snapshots, applies and
 * overwrites every {@code killer560smod-*.json} file, and a run history is real run data, not a
 * setting. A file inside a folder never matches that pattern, so applying or importing a settings profile
 * can't wipe it (unlike the Croesus/Experiments logs, which had to be hand-added to ProfileManager's
 * {@code EXCLUDED_FILES} to survive).
 * <p>
 * Newest run first. The list is trimmed to {@link RunSummaryConfig#getMaxRuns()} on every add, and again
 * on load if the setting was lowered while the game was closed. Personal bests are DERIVED from the
 * stored runs (fastest {@code totalMs}, highest score, per floor key) rather than stored separately, so
 * lowering the keep-count or deleting the file can never leave a "best" nothing in the file can back up.
 * Writes go through a tmp file + atomic move on a daemon thread, the same way
 * {@code croesus/CroesusProfitLog} writes its own log.
 */
public final class RunHistoryStore {

    private static final Logger LOGGER = ModLog.get("killer560smod-runsummary");

    /** Was {@code config/killer560/dungeons/runs/killer560smod-runs/history.json}; now the shared store (SharedRecords). */
    /** The shared file the history was loaded from (the account signed in then). */
    private static SharedFile file;

    static {
        // The in-game account switcher: finish this account's writes, then read the new account's runs.
        SharedRecords.onAccountChange(() -> {
            try {
                RunHistoryStore.WRITER.submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            synchronized (RunHistoryStore.class) {
                if (RunHistoryStore.loaded) {
                    load();
                }
            }
        });
    }

    /** The shared file, for the testkit (it writes fixture histories here). */
    public static Path pathForTest() {
        return SharedRecords.pathOf(RecordCatalog.RUN_HISTORY);
    }

    private static final int FILE_VERSION = 1;
    /** Hard ceiling regardless of the configured keep-count, so a hand-edited config can't grow the file forever. */
    public static final int HARD_MAX = 500;

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-runhistory-writer");
        t.setDaemon(true);
        return t;
    });

    private static final List<RunRecord> RUNS = new ArrayList<>();
    private static boolean loaded = false;

    /** What a freshly finished run beat, if anything. */
    public record BestFlags(boolean time, boolean score) {
        public boolean any() {
            return time || score;
        }
    }

    private RunHistoryStore() {
    }

    public static synchronized void load() {
        RUNS.clear();
        loaded = true;
        try {
            // This account's runs from every instance (shared-stats-1101); the old per-instance file is folded in.
            file = SharedRecords.file(RecordCatalog.RUN_HISTORY);
            JsonObject root = SharedRecords.load(file);
            JsonArray runs = ConfigJson.getArray(root, "runs");
            if (runs != null) {
                for (JsonElement element : runs) {
                    RunRecord record = RunRecord.fromJson(element);
                    if (record != null) {
                        RUNS.add(record);
                    }
                }
            }
            trim();
            if (repairBogusBests()) {
                saveAsync();
            }
            LOGGER.info("[RunSummary] Loaded {} run(s) from {}", RUNS.size(), file.path());
        } catch (Exception e) {
            // An unreadable shared file is copied aside by SharedFile before anything replaces it.
            LOGGER.warn("[RunSummary] Could not read the run history - starting a fresh one", e);
            RUNS.clear();
        }
    }

    private static int pbMin() {
        return RunSummaryConfig.getInstance().getPbMinScore();
    }

    /**
     * One-time self-repair (2026-10-15): clears the stored "*PB*" marks on any run that was never completed or scored
     * below S (270) - a wiped run once saved itself as a fastest time. The runs stay in the history; they simply stop
     * counting, since bests are derived from {@link RunRecord#countsForPb}. Idempotent, so it just runs on every load.
     *
     * @return true when something was cleared and the file needs rewriting
     */
    static boolean repairBogusBests() {
        boolean changed = false;
        for (int i = 0; i < RUNS.size(); i++) {
            RunRecord r = RUNS.get(i);
            if ((r.bestTime() || r.bestScore()) && !r.countsForPb(RunSummaryConfig.PB_MIN_S)) {
                RUNS.set(i, new RunRecord(r.startedAtMs(), r.endedAtMs(), r.floor(), r.masterMode(), r.totalMs(),
                        r.hypixelTime(), r.hypixelNewRecord(), r.splits(), r.devices(), r.secretsFound(),
                        r.totalSecrets(), r.crypts(), r.deaths(), r.puzzlesFailed(), r.puzzleCount(),
                        r.estimatedScore(), r.estimatedRank(), r.hypixelScore(), r.hypixelRank(), r.dungeonClass(),
                        r.partySize(), r.chestProfit(), r.chestCount(), false, false, r.party(), r.map()));
                LOGGER.info("[RunSummary] Cleared a personal-best mark from an unfinished or sub-S run ({} {})",
                        r.floorLabel(), RunRecord.formatTime(r.totalMs()));
                changed = true;
            }
        }
        return changed;
    }

    /** Test hook: replaces the in-memory history without touching disk, then runs the repair. */
    public static synchronized boolean setRunsForTest(List<RunRecord> runs) {
        RUNS.clear();
        RUNS.addAll(runs);
        loaded = true;
        boolean changed = repairBogusBests();
        return changed;
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    /**
     * Stores one finished run, newest first, and returns what it beat.
     * <p>
     * The bests are read BEFORE the run is inserted, so a run is never compared against itself; the
     * returned flags are written into the stored copy so the history browser can still mark it later.
     */
    public static synchronized BestFlags add(RunRecord record) {
        ensureLoaded();
        if (record == null) {
            return new BestFlags(false, false);
        }
        String key = floorKey(record);
        long previousBestTime = bestTimeMs(key);
        int previousBestScore = bestScore(key);
        // Only a completed run at or above the minimum score can be a personal best at all.
        boolean eligible = record.countsForPb(pbMin());
        boolean beatTime = eligible && record.totalMs() > 0
                && (previousBestTime <= 0 || record.totalMs() < previousBestTime);
        boolean beatScore = eligible && record.hypixelScore() >= 0
                && (previousBestScore < 0 || record.hypixelScore() > previousBestScore);
        RunRecord stored = new RunRecord(record.startedAtMs(), record.endedAtMs(), record.floor(),
                record.masterMode(), record.totalMs(), record.hypixelTime(), record.hypixelNewRecord(),
                record.splits(), record.devices(), record.secretsFound(), record.totalSecrets(), record.crypts(),
                record.deaths(), record.puzzlesFailed(), record.puzzleCount(), record.estimatedScore(),
                record.estimatedRank(), record.hypixelScore(), record.hypixelRank(), record.dungeonClass(),
                record.partySize(), record.chestProfit(), record.chestCount(), beatTime, beatScore,
                record.party(), record.map());
        RUNS.add(0, stored);
        trim();
        LOGGER.info("[RunSummary] Stored run: {} in {} score {} ({}){}", stored.floorLabel(),
                RunRecord.formatTime(stored.totalMs()), stored.effectiveScore(), stored.effectiveRank(),
                beatTime || beatScore ? " - NEW PB (" + (beatTime ? "time" : "") + (beatTime && beatScore ? "+" : "")
                        + (beatScore ? "score" : "") + ")" : "");
        saveAsync();
        return new BestFlags(beatTime, beatScore);
    }

    /** Newest first. */
    public static synchronized List<RunRecord> runs() {
        ensureLoaded();
        return List.copyOf(RUNS);
    }

    public static synchronized RunRecord latest() {
        ensureLoaded();
        return RUNS.isEmpty() ? null : RUNS.get(0);
    }

    public static synchronized int size() {
        ensureLoaded();
        return RUNS.size();
    }

    /** Wipes the history file and the in-memory list ("Clear History" in the tab). */
    public static synchronized void clear() {
        ensureLoaded();
        RUNS.clear();
        LOGGER.info("[RunSummary] Run history cleared by the user");
        saveAsync();
    }

    /** The floor a run is ranked against - Master and normal floors are separate bests. */
    public static String floorKey(RunRecord record) {
        return record == null || record.floor() == null || record.floor().isBlank() ? "Unknown" : record.floor();
    }

    /** Fastest stored {@code totalMs} for a floor key, or -1 when nothing is stored for it. */
    public static synchronized long bestTimeMs(String floorKey) {
        ensureLoaded();
        long best = -1L;
        for (RunRecord r : RUNS) {
            if (floorKey(r).equals(floorKey) && r.countsForPb(pbMin()) && r.totalMs() > 0 && (best < 0 || r.totalMs() < best)) {
                best = r.totalMs();
            }
        }
        return best;
    }

    /** Highest stored score for a floor key (Hypixel's when known, otherwise the estimate), or -1. */
    public static synchronized int bestScore(String floorKey) {
        ensureLoaded();
        int best = -1;
        for (RunRecord r : RUNS) {
            if (floorKey(r).equals(floorKey) && r.countsForPb(pbMin()) && r.hypixelScore() > best) {
                best = r.hypixelScore();
            }
        }
        return best;
    }

    /** Per-floor bests for the tab's summary line, newest floors in insertion order. */
    public static synchronized Map<String, long[]> bestsByFloor() {
        ensureLoaded();
        Map<String, long[]> out = new LinkedHashMap<>();
        for (RunRecord r : RUNS) {
            if (!r.countsForPb(pbMin())) {
                continue;
            }
            String key = floorKey(r);
            long[] entry = out.computeIfAbsent(key, k -> new long[]{-1L, -1L});
            if (r.totalMs() > 0 && (entry[0] < 0 || r.totalMs() < entry[0])) {
                entry[0] = r.totalMs();
            }
            if (r.hypixelScore() > entry[1]) {
                entry[1] = r.hypixelScore();
            }
        }
        return out;
    }

    private static void trim() {
        int max = Math.min(HARD_MAX, Math.max(1, RunSummaryConfig.getInstance().getMaxRuns()));
        while (RUNS.size() > max) {
            RUNS.remove(RUNS.size() - 1);
        }
    }

    /** Called by the tab when the keep-count is lowered, so the file shrinks right away. */
    public static synchronized void applyMaxRuns() {
        ensureLoaded();
        int before = RUNS.size();
        trim();
        if (RUNS.size() != before) {
            saveAsync();
        }
    }

    private static void saveAsync() {
        // Callers hold the class lock. Only the list is copied here (records are immutable); building and printing
        // up to 500 runs' JSON happens on the writer thread, not on the client tick that finished the run.
        List<RunRecord> snapshot = List.copyOf(RUNS);
        SharedFile target = file != null ? file : SharedRecords.file(RecordCatalog.RUN_HISTORY);
        WRITER.submit(() -> {
            try {
                JsonObject root = new JsonObject();
                root.addProperty("version", FILE_VERSION);
                JsonArray array = new JsonArray();
                for (RunRecord r : snapshot) {
                    array.add(r.toJson());
                }
                root.add("runs", array);
                // Merged onto the shared file, so another instance's runs since are kept (shared-stats-1101).
                SharedRecords.save(target, root);
            } catch (Exception e) {
                LOGGER.warn("[RunSummary] Failed to write the run history", e);
            }
        });
    }
}
