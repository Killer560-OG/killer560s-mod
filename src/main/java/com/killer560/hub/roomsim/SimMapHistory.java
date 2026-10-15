package com.killer560.hub.roomsim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every floor he PLAYED from Design a Map, newest first, with the date it was made - the designer's Load list.
 *
 * <p>killer560 (2026-10-15): "Remove the save and load buttons in exchange for one load button. Any map generated and
 * played will show there with a date of when it was created. This is only for generated maps not saved maps from
 * runs." So nothing is named or saved by hand any more: pressing Play on the designer records the floor. A generated
 * floor is kept as its map code (rotations and doors included, so it rebuilds exactly); a floor he drew is kept as its
 * cells, since that is all a drawing is. Floors from real runs stay in the saved-runs screen ({@link SimRunHistory})
 * and never come here.
 *
 * <p>Playing a floor that is already listed (one he loaded from here) does not list it twice: the entry keeps the date
 * it was first made. History, not setup: {@code ProfileData.EXCLUDED_NAMES} keeps it out of profiles.
 */
public final class SimMapHistory {

    private static final Logger LOGGER = ModLog.get("killer560smod-simmaps");

    private static final Path FILE = ModPaths.config("killer560smod-sim-map-history.json");

    /** Oldest entries fall off past this, so the list (and the file) cannot grow without end. */
    public static final int MAX_ENTRIES = 200;

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withLocale(Locale.US);

    /**
     * One played floor. {@code code} is set for a generated floor and {@code cells} (slot {@code z * 6 + x} to room)
     * for a drawn one; the other is null.
     */
    public record Entry(long createdMs, String floorCode, String floorLabel, String code, Map<Integer, String> cells,
                        int rooms, int puzzles) {

        public boolean generated() {
            return code != null;
        }

        public String dateText() {
            return DATE.format(Instant.ofEpochMilli(createdMs).atZone(ZoneId.systemDefault()));
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static boolean loaded;

    private SimMapHistory() {
    }

    /** Newest first. */
    public static synchronized List<Entry> entries() {
        load();
        return List.copyOf(ENTRIES);
    }

    /** Records a generated floor on Play. Returns the entry it is listed under (an existing one if already there). */
    public static synchronized Entry recordGenerated(String floorCode, String floorLabel, String code, int rooms,
                                                     int puzzles) {
        load();
        if (code == null || code.isBlank()) {
            return null;
        }
        for (Entry e : ENTRIES) {
            if (code.equals(e.code())) {
                return e;
            }
        }
        return add(new Entry(System.currentTimeMillis(), floorCode, floorLabel, code, null, rooms, puzzles));
    }

    /** Records a drawn floor on Play. */
    public static synchronized Entry recordDrawn(String floorCode, String floorLabel, Map<Integer, String> cells,
                                                 int puzzles) {
        load();
        if (cells == null || cells.isEmpty()) {
            return null;
        }
        for (Entry e : ENTRIES) {
            if (e.cells() != null && e.cells().equals(cells)) {
                return e;
            }
        }
        return add(new Entry(System.currentTimeMillis(), floorCode, floorLabel, null,
                new LinkedHashMap<>(cells), cells.size(), puzzles));
    }

    private static Entry add(Entry e) {
        ENTRIES.add(0, e);
        while (ENTRIES.size() > MAX_ENTRIES) {
            ENTRIES.remove(ENTRIES.size() - 1);
        }
        save();
        return e;
    }

    public static synchronized void remove(Entry e) {
        load();
        if (ENTRIES.remove(e)) {
            save();
        }
    }

    /** Forget what was read, so the next call reads the file again (a profile load, the testkit's restart check). */
    public static synchronized void reload() {
        loaded = false;
        ENTRIES.clear();
        load();
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.exists(FILE)) {
            return;
        }
        try {
            JsonArray arr = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement el : arr) {
                try {
                    JsonObject o = el.getAsJsonObject();
                    String code = o.has("code") ? o.get("code").getAsString() : null;
                    Map<Integer, String> cells = null;
                    if (o.has("cells") && o.get("cells").isJsonObject()) {
                        cells = new LinkedHashMap<>();
                        JsonObject c = o.getAsJsonObject("cells");
                        for (String k : c.keySet()) {
                            cells.put(Integer.parseInt(k), c.get(k).getAsString());
                        }
                    }
                    if (code == null && (cells == null || cells.isEmpty())) {
                        continue;
                    }
                    ENTRIES.add(new Entry(o.get("created").getAsLong(),
                            o.has("floor") ? o.get("floor").getAsString() : "F7",
                            o.has("floorLabel") ? o.get("floorLabel").getAsString() : "Floor 7",
                            code, cells,
                            o.has("rooms") ? o.get("rooms").getAsInt() : 0,
                            o.has("puzzles") ? o.get("puzzles").getAsInt() : 0));
                } catch (Exception ignored) {
                    // One unreadable entry loses that floor, not the list.
                }
            }
            ENTRIES.sort((a, b) -> Long.compare(b.createdMs(), a.createdMs()));
            LOGGER.info("Sim map history: {} played floor(s)", ENTRIES.size());
        } catch (Exception e) {
            ENTRIES.clear();
            LOGGER.warn("Could not read the sim map history ({}) - starting with none", e.getClass().getSimpleName());
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            JsonArray arr = new JsonArray();
            for (Entry e : ENTRIES) {
                JsonObject o = new JsonObject();
                o.addProperty("created", e.createdMs());
                o.addProperty("floor", e.floorCode());
                o.addProperty("floorLabel", e.floorLabel());
                if (e.code() != null) {
                    o.addProperty("code", e.code());
                }
                if (e.cells() != null) {
                    JsonObject c = new JsonObject();
                    for (Map.Entry<Integer, String> cell : e.cells().entrySet()) {
                        c.addProperty(String.valueOf(cell.getKey()), cell.getValue());
                    }
                    o.add("cells", c);
                }
                o.addProperty("rooms", e.rooms());
                o.addProperty("puzzles", e.puzzles());
                arr.add(o);
            }
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            Files.writeString(tmp, arr.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOGGER.error("Could not save the sim map history", e);
        }
    }
}
