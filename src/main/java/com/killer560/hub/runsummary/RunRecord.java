package com.killer560.hub.runsummary;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One finished dungeon run, exactly as {@link RunSummaryFeature} assembled it - the only thing that ever
 * gets written to {@code config/killer560smod-runs/history.json}.
 * <p>
 * Everything here is COLLECTED, never computed a second time: the per-phase splits come from
 * {@code splittimers/SplitTimersFeature}'s public segment getters, the score estimate from
 * {@code scorecalc/ScoreCalculatorFeature.currentResult()}, the class/party from
 * {@code leapmenu/PartyTracker}. Fields that were never observed keep an explicit "unknown" value
 * ({@code -1} for counts/scores, {@code null} for strings, empty lists) instead of a fake zero, so the
 * summary view can say "-" rather than claim a real 0.
 */
public record RunRecord(
        long startedAtMs,
        long endedAtMs,
        /** Floor as the sidebar reported it: "E", "F1".."F7", "M1".."M7". Null if it was never resolved. */
        String floor,
        boolean masterMode,
        /** Mod-measured wall clock, run start line -> end-of-run line. */
        long totalMs,
        /** Hypixel's own "☠ Defeated X in 12m 34s" text, or null if that line was never seen. */
        String hypixelTime,
        /** Hypixel appended "(NEW RECORD!)" to the Defeated line. */
        boolean hypixelNewRecord,
        List<Split> splits,
        List<Device> devices,
        int secretsFound,
        int totalSecrets,
        int crypts,
        int deaths,
        int puzzlesFailed,
        int puzzleCount,
        /** This mod's own live estimate at the moment the run ended, or -1. */
        int estimatedScore,
        String estimatedRank,
        /** Hypixel's real "Team Score: N (S+)", or -1 if that line was never seen. */
        int hypixelScore,
        String hypixelRank,
        String dungeonClass,
        int partySize,
        /** Croesus chest profit for this run, or {@link Long#MIN_VALUE} when not known (see RunSummaryFeature). */
        long chestProfit,
        int chestCount,
        /** Set by {@link RunHistoryStore} when this run beat the stored personal best for its floor. */
        boolean bestTime,
        boolean bestScore) {

    public static final int UNKNOWN_INT = -1;
    public static final long UNKNOWN_PROFIT = Long.MIN_VALUE;

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withLocale(Locale.US);

    /** One split segment: the phase's plain name and how long it ran. */
    public record Split(String name, long ms) {
    }

    /** One device/lever/terminal completion: who, which kind, the (n/m) count, and how far into the split
     *  segment named {@code phase} it landed ({@code -1} when no segment was being tracked). */
    public record Device(String player, String kind, int index, int total, String phase, long msIntoPhase) {
    }

    public RunRecord {
        splits = splits == null ? List.of() : List.copyOf(splits);
        devices = devices == null ? List.of() : List.copyOf(devices);
    }

    /** "F7" / "M7 (Master)" style label for lists. */
    public String floorLabel() {
        String f = floor == null || floor.isBlank() ? "?" : floor;
        return masterMode ? f + " (Master)" : f;
    }

    /** The score to rank this run by: Hypixel's if it was seen, otherwise the mod's estimate, else -1. */
    public int effectiveScore() {
        if (hypixelScore >= 0) {
            return hypixelScore;
        }
        return estimatedScore >= 0 ? estimatedScore : UNKNOWN_INT;
    }

    public String effectiveRank() {
        if (hypixelScore >= 0 && hypixelRank != null) {
            return hypixelRank;
        }
        return estimatedRank;
    }

    public String dateText() {
        return DATE.format(Instant.ofEpochMilli(endedAtMs > 0 ? endedAtMs : startedAtMs).atZone(ZoneId.systemDefault()));
    }

    /** "12m 34.56s" - the same shape SplitTimersFeature prints, kept here so the GUI and the chat summary
     *  never format a duration two different ways. */
    public static String formatTime(long timeMs) {
        if (timeMs <= 0L) {
            return "-";
        }
        long remaining = timeMs;
        long hours = remaining / 3600000L;
        remaining -= hours * 3600000L;
        long minutes = remaining / 60000L;
        remaining -= minutes * 60000L;
        return (hours > 0 ? hours + "h " : "") + (minutes > 0 ? minutes + "m " : "")
                + String.format(Locale.US, "%.2fs", remaining / 1000f);
    }

    // ------------------------------------------------------------------ json

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("startedAtMs", startedAtMs);
        o.addProperty("endedAtMs", endedAtMs);
        o.addProperty("date", Instant.ofEpochMilli(endedAtMs > 0 ? endedAtMs : startedAtMs).toString());
        if (floor != null) {
            o.addProperty("floor", floor);
        }
        o.addProperty("masterMode", masterMode);
        o.addProperty("totalMs", totalMs);
        if (hypixelTime != null) {
            o.addProperty("hypixelTime", hypixelTime);
        }
        o.addProperty("hypixelNewRecord", hypixelNewRecord);
        JsonArray splitArray = new JsonArray();
        for (Split s : splits) {
            JsonObject e = new JsonObject();
            e.addProperty("name", s.name());
            e.addProperty("ms", s.ms());
            splitArray.add(e);
        }
        o.add("splits", splitArray);
        JsonArray deviceArray = new JsonArray();
        for (Device d : devices) {
            JsonObject e = new JsonObject();
            e.addProperty("player", d.player());
            e.addProperty("kind", d.kind());
            e.addProperty("index", d.index());
            e.addProperty("total", d.total());
            if (d.phase() != null) {
                e.addProperty("phase", d.phase());
            }
            e.addProperty("msIntoPhase", d.msIntoPhase());
            deviceArray.add(e);
        }
        o.add("devices", deviceArray);
        o.addProperty("secretsFound", secretsFound);
        o.addProperty("totalSecrets", totalSecrets);
        o.addProperty("crypts", crypts);
        o.addProperty("deaths", deaths);
        o.addProperty("puzzlesFailed", puzzlesFailed);
        o.addProperty("puzzleCount", puzzleCount);
        o.addProperty("estimatedScore", estimatedScore);
        if (estimatedRank != null) {
            o.addProperty("estimatedRank", estimatedRank);
        }
        o.addProperty("hypixelScore", hypixelScore);
        if (hypixelRank != null) {
            o.addProperty("hypixelRank", hypixelRank);
        }
        if (dungeonClass != null) {
            o.addProperty("class", dungeonClass);
        }
        o.addProperty("partySize", partySize);
        if (chestProfit != UNKNOWN_PROFIT) {
            o.addProperty("chestProfit", chestProfit);
            o.addProperty("chestCount", chestCount);
        }
        o.addProperty("bestTime", bestTime);
        o.addProperty("bestScore", bestScore);
        return o;
    }

    /** Never throws: a malformed field falls back to "unknown" for that field only (the same per-key rule
     *  {@link ConfigJson} exists for), so one bad entry can't take the whole history file down. */
    public static RunRecord fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject o = element.getAsJsonObject();
        List<Split> splits = new ArrayList<>();
        JsonArray splitArray = ConfigJson.getArray(o, "splits");
        if (splitArray != null) {
            for (JsonElement e : splitArray) {
                if (e != null && e.isJsonObject()) {
                    JsonObject s = e.getAsJsonObject();
                    String name = ConfigJson.getString(s, "name", null);
                    if (name != null) {
                        splits.add(new Split(name, ConfigJson.getLong(s, "ms", 0L)));
                    }
                }
            }
        }
        List<Device> devices = new ArrayList<>();
        JsonArray deviceArray = ConfigJson.getArray(o, "devices");
        if (deviceArray != null) {
            for (JsonElement e : deviceArray) {
                if (e != null && e.isJsonObject()) {
                    JsonObject d = e.getAsJsonObject();
                    devices.add(new Device(
                            ConfigJson.getString(d, "player", "?"),
                            ConfigJson.getString(d, "kind", "device"),
                            ConfigJson.getInt(d, "index", 0),
                            ConfigJson.getInt(d, "total", 0),
                            ConfigJson.getString(d, "phase", null),
                            ConfigJson.getLong(d, "msIntoPhase", -1L)));
                }
            }
        }
        return new RunRecord(
                ConfigJson.getLong(o, "startedAtMs", 0L),
                ConfigJson.getLong(o, "endedAtMs", 0L),
                ConfigJson.getString(o, "floor", null),
                ConfigJson.getBool(o, "masterMode", false),
                ConfigJson.getLong(o, "totalMs", 0L),
                ConfigJson.getString(o, "hypixelTime", null),
                ConfigJson.getBool(o, "hypixelNewRecord", false),
                splits,
                devices,
                ConfigJson.getInt(o, "secretsFound", UNKNOWN_INT),
                ConfigJson.getInt(o, "totalSecrets", UNKNOWN_INT),
                ConfigJson.getInt(o, "crypts", UNKNOWN_INT),
                ConfigJson.getInt(o, "deaths", UNKNOWN_INT),
                ConfigJson.getInt(o, "puzzlesFailed", UNKNOWN_INT),
                ConfigJson.getInt(o, "puzzleCount", UNKNOWN_INT),
                ConfigJson.getInt(o, "estimatedScore", UNKNOWN_INT),
                ConfigJson.getString(o, "estimatedRank", null),
                ConfigJson.getInt(o, "hypixelScore", UNKNOWN_INT),
                ConfigJson.getString(o, "hypixelRank", null),
                ConfigJson.getString(o, "class", null),
                ConfigJson.getInt(o, "partySize", UNKNOWN_INT),
                o.has("chestProfit") ? ConfigJson.getLong(o, "chestProfit", 0L) : UNKNOWN_PROFIT,
                ConfigJson.getInt(o, "chestCount", 0),
                ConfigJson.getBool(o, "bestTime", false),
                ConfigJson.getBool(o, "bestScore", false));
    }
}
