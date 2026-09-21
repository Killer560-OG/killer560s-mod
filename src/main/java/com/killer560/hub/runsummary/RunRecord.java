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
        boolean bestScore,
        /** Who was in the party, keyed on UUID - see {@link PartyMember}. Empty when nothing was readable. */
        List<PartyMember> party,
        /** The dungeon map as it looked, or null when nothing ever scanned it (see {@link MapSnapshot}). */
        MapSnapshot map) {

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

    /**
     * One party member of a logged run, for the {@code /log} run log.
     * <p>
     * <b>Keyed on {@link #uuid}, never on the IGN</b> - killer560's standing rule, so a name change never
     * loses history. {@link #name} is only what they were called at the time; the run log resolves the
     * CURRENT name from the UUID when that player is online and falls back to this one otherwise.
     * {@code -1} in any count means "not tracked" (Run Stats was off, or the API never answered).
     */
    public record PartyMember(String uuid, String name, String dungeonClass, int classLevel,
                              int soloRooms, int stackedRooms, int secrets, int deaths) {

        /** "{@code 4}" when nobody stacked with them, otherwise "{@code 4-7}" - same shape Run Stats prints. */
        public String roomsLabel() {
            if (soloRooms < 0) {
                return "-";
            }
            return stackedRooms <= 0 ? String.valueOf(soloRooms) : soloRooms + "-" + (soloRooms + stackedRooms);
        }
    }

    /** One room of a {@link MapSnapshot}: its name and its room-database type ("puzzle", "trap", ...). */
    public record MapRoom(String name, String type) {
    }

    /**
     * The dungeon map as it stood at the end of the run - killer560, 2026-09-20: "This should log the map
     * that was shown".
     * <p>
     * An 11x11 grid, the same one {@code livemap/DungeonLayout} works in: {@link #roomOfCell} holds a room
     * index (or -1) per cell and {@link #doorOfCell} the door type, with bit 8 set when the door was still
     * locked. Room scanning only runs while some consumer of it is enabled (Live Map, Secret Waypoints, a
     * puzzle solver...), so a run done with all of those off has no map and this is null.
     */
    public record MapSnapshot(int[] roomOfCell, int[] doorOfCell, List<MapRoom> rooms) {

        public static final int GRID = 11;
        public static final int LOCKED_BIT = 8;

        public MapSnapshot {
            roomOfCell = roomOfCell == null ? new int[GRID * GRID] : roomOfCell.clone();
            doorOfCell = doorOfCell == null ? new int[GRID * GRID] : doorOfCell.clone();
            rooms = rooms == null ? List.of() : List.copyOf(rooms);
        }

        public boolean isEmpty() {
            return rooms.isEmpty();
        }

        public int roomAt(int cell) {
            return cell >= 0 && cell < roomOfCell.length ? roomOfCell[cell] : -1;
        }

        public int doorAt(int cell) {
            return cell >= 0 && cell < doorOfCell.length ? doorOfCell[cell] & ~LOCKED_BIT : 0;
        }

        public boolean lockedAt(int cell) {
            return cell >= 0 && cell < doorOfCell.length && (doorOfCell[cell] & LOCKED_BIT) != 0;
        }

        public MapRoom room(int id) {
            return id >= 0 && id < rooms.size() ? rooms.get(id) : null;
        }
    }

    public RunRecord {
        splits = splits == null ? List.of() : List.copyOf(splits);
        devices = devices == null ? List.of() : List.copyOf(devices);
        party = party == null ? List.of() : List.copyOf(party);
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
        if (!party.isEmpty()) {
            JsonArray partyArray = new JsonArray();
            for (PartyMember p : party) {
                JsonObject e = new JsonObject();
                if (p.uuid() != null) {
                    e.addProperty("uuid", p.uuid());
                }
                e.addProperty("name", p.name());
                if (p.dungeonClass() != null) {
                    e.addProperty("class", p.dungeonClass());
                }
                e.addProperty("classLevel", p.classLevel());
                e.addProperty("soloRooms", p.soloRooms());
                e.addProperty("stackedRooms", p.stackedRooms());
                e.addProperty("secrets", p.secrets());
                e.addProperty("deaths", p.deaths());
                partyArray.add(e);
            }
            o.add("party", partyArray);
        }
        if (map != null && !map.isEmpty()) {
            JsonObject m = new JsonObject();
            // Comma-joined rather than 121-element JSON arrays: the history file is pretty-printed, and two
            // arrays per run would be 250 lines of single digits each.
            m.addProperty("roomCells", joinInts(map.roomOfCell()));
            m.addProperty("doorCells", joinInts(map.doorOfCell()));
            JsonArray roomArray = new JsonArray();
            for (MapRoom r : map.rooms()) {
                JsonObject e = new JsonObject();
                e.addProperty("name", r.name() == null ? "Unknown" : r.name());
                if (r.type() != null) {
                    e.addProperty("type", r.type());
                }
                roomArray.add(e);
            }
            m.add("rooms", roomArray);
            o.add("map", m);
        }
        return o;
    }

    private static String joinInts(int[] values) {
        StringBuilder sb = new StringBuilder(values.length * 3);
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i]);
        }
        return sb.toString();
    }

    private static int[] splitInts(String text, int expected) {
        int[] out = new int[expected];
        java.util.Arrays.fill(out, -1);
        if (text == null || text.isBlank()) {
            return out;
        }
        String[] parts = text.split(",");
        for (int i = 0; i < expected && i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException ignored) {
                out[i] = -1;
            }
        }
        return out;
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
        List<PartyMember> party = new ArrayList<>();
        JsonArray partyArray = ConfigJson.getArray(o, "party");
        if (partyArray != null) {
            for (JsonElement e : partyArray) {
                if (e != null && e.isJsonObject()) {
                    JsonObject p = e.getAsJsonObject();
                    party.add(new PartyMember(
                            ConfigJson.getString(p, "uuid", null),
                            ConfigJson.getString(p, "name", "?"),
                            ConfigJson.getString(p, "class", null),
                            ConfigJson.getInt(p, "classLevel", UNKNOWN_INT),
                            ConfigJson.getInt(p, "soloRooms", UNKNOWN_INT),
                            ConfigJson.getInt(p, "stackedRooms", UNKNOWN_INT),
                            ConfigJson.getInt(p, "secrets", UNKNOWN_INT),
                            ConfigJson.getInt(p, "deaths", UNKNOWN_INT)));
                }
            }
        }
        MapSnapshot map = null;
        JsonObject mapObject = ConfigJson.getObject(o, "map");
        if (mapObject != null) {
            List<MapRoom> rooms = new ArrayList<>();
            JsonArray roomArray = ConfigJson.getArray(mapObject, "rooms");
            if (roomArray != null) {
                for (JsonElement e : roomArray) {
                    if (e != null && e.isJsonObject()) {
                        JsonObject r = e.getAsJsonObject();
                        rooms.add(new MapRoom(ConfigJson.getString(r, "name", "Unknown"),
                                ConfigJson.getString(r, "type", null)));
                    }
                }
            }
            if (!rooms.isEmpty()) {
                int cells = MapSnapshot.GRID * MapSnapshot.GRID;
                map = new MapSnapshot(splitInts(ConfigJson.getString(mapObject, "roomCells", null), cells),
                        splitInts(ConfigJson.getString(mapObject, "doorCells", null), cells), rooms);
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
                ConfigJson.getBool(o, "bestScore", false),
                party,
                map);
    }
}
