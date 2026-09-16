package com.killer560.hub.runsummary;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The persisted run history: {@code config/killer560smod-runs/history.json}.
 * <p>
 * A SUBFOLDER, deliberately - exactly the reason {@code pathfinding/FairySoulStore} lives in
 * {@code config/killer560smod-pathfinding/}: {@code profiles/ProfileManager} snapshots, applies and
 * overwrites every {@code config/killer560smod-*.json} file, and a run history is real run data, not a
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

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-runsummary");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** {@code config/killer560smod-runs/history.json}. */
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("killer560smod-runs");
    private static final Path FILE = DIR.resolve("history.json");

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
        if (!Files.exists(FILE)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
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
            LOGGER.info("[RunSummary] Loaded {} run(s) from {}", RUNS.size(), FILE);
        } catch (Exception e) {
            // Same rule as CroesusProfitLog: the next save() would overwrite an unreadable history, so keep
            // the original bytes first instead of silently destroying them.
            Path backup = FILE.resolveSibling(FILE.getFileName() + ".corrupt-" + System.currentTimeMillis());
            try {
                Files.copy(FILE, backup);
                LOGGER.warn("[RunSummary] Could not read {} - backed it up to {} and starting a fresh history",
                        FILE.getFileName(), backup.getFileName(), e);
            } catch (Exception backupError) {
                LOGGER.warn("[RunSummary] Could not read {} - starting a fresh history (backup also failed: {})",
                        FILE.getFileName(), backupError.toString(), e);
            }
            RUNS.clear();
        }
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
        boolean beatTime = record.totalMs() > 0 && (previousBestTime <= 0 || record.totalMs() < previousBestTime);
        boolean beatScore = record.effectiveScore() >= 0
                && (previousBestScore < 0 || record.effectiveScore() > previousBestScore);
        RunRecord stored = new RunRecord(record.startedAtMs(), record.endedAtMs(), record.floor(),
                record.masterMode(), record.totalMs(), record.hypixelTime(), record.hypixelNewRecord(),
                record.splits(), record.devices(), record.secretsFound(), record.totalSecrets(), record.crypts(),
                record.deaths(), record.puzzlesFailed(), record.puzzleCount(), record.estimatedScore(),
                record.estimatedRank(), record.hypixelScore(), record.hypixelRank(), record.dungeonClass(),
                record.partySize(), record.chestProfit(), record.chestCount(), beatTime, beatScore);
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
        if (record == null || record.floor() == null || record.floor().isBlank()) {
            return "Unknown";
        }
        return record.floor();
    }

    /** Fastest stored {@code totalMs} for a floor key, or -1 when nothing is stored for it. */
    public static synchronized long bestTimeMs(String floorKey) {
        ensureLoaded();
        long best = -1L;
        for (RunRecord r : RUNS) {
            if (floorKey(r).equals(floorKey) && r.totalMs() > 0 && (best < 0 || r.totalMs() < best)) {
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
            if (floorKey(r).equals(floorKey) && r.effectiveScore() > best) {
                best = r.effectiveScore();
            }
        }
        return best;
    }

    /** Per-floor bests for the tab's summary line, newest floors in insertion order. */
    public static synchronized Map<String, long[]> bestsByFloor() {
        ensureLoaded();
        Map<String, long[]> out = new LinkedHashMap<>();
        for (RunRecord r : RUNS) {
            String key = floorKey(r);
            long[] entry = out.computeIfAbsent(key, k -> new long[]{-1L, -1L});
            if (r.totalMs() > 0 && (entry[0] < 0 || r.totalMs() < entry[0])) {
                entry[0] = r.totalMs();
            }
            if (r.effectiveScore() > entry[1]) {
                entry[1] = r.effectiveScore();
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
        JsonObject root = new JsonObject();
        root.addProperty("version", FILE_VERSION);
        JsonArray array = new JsonArray();
        for (RunRecord r : RUNS) {
            array.add(r.toJson());
        }
        root.add("runs", array);
        String json = GSON.toJson((JsonElement) root);
        WRITER.submit(() -> {
            try {
                Files.createDirectories(DIR);
                Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                LOGGER.warn("[RunSummary] Failed to write {}", FILE, e);
            }
        });
    }
}
