package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import com.killer560.hub.util.ModLog;

/**
 * The floors he has drawn, kept between sessions.
 *
 * <p>Ashfall's Dungeon Maker has Save and Load, and a map editor without them is a toy: every floor worth
 * practising has to be redrawn from nothing. This is the same shape as every other config in the mod - written
 * on change, read once at startup - so a drawn floor survives a restart like every other setting.
 *
 * <p>Stored as name to {@code cell: room}, with cells as the room-grid slot the editor uses. A room that has
 * since been recaptured at a different size still loads: the editor re-derives every footprint from the
 * library, so a preset holds intent (this room, this cell) rather than geometry that could go stale.
 */
public final class SimMapPresets {

    private static final Logger LOGGER = ModLog.get("killer560smod-simmaps");

    private static final Path FILE =
            ModPaths.config("killer560smod-simmaps.json");

    /** Name to (slot to room). A TreeMap so the list is stable and alphabetical without sorting at draw time. */
    private static final Map<String, Map<Integer, String>> MAPS =
            new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    private static boolean loaded;

    private SimMapPresets() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.exists(FILE)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(
                    Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String name : root.keySet()) {
                if (!root.get(name).isJsonObject()) {
                    continue;
                }
                Map<Integer, String> cells = new LinkedHashMap<>();
                JsonObject obj = root.getAsJsonObject(name);
                for (String cell : obj.keySet()) {
                    try {
                        cells.put(Integer.parseInt(cell), obj.get(cell).getAsString());
                    } catch (Exception ignored) {
                        // One unreadable cell loses that room, not the whole map.
                    }
                }
                if (!cells.isEmpty()) {
                    MAPS.put(name, cells);
                }
            }
            LOGGER.info("Sim maps: {} saved design(s)", MAPS.size());
        } catch (Exception e) {
            // Defaults wholesale rather than a half-read file, same as every other config here.
            MAPS.clear();
            LOGGER.warn("Could not read the saved sim maps ({}) - starting with none",
                    e.getClass().getSimpleName());
        }
    }

    public static synchronized List<String> names() {
        load();
        return new ArrayList<>(MAPS.keySet());
    }

    /** @return a COPY, so the editor cannot mutate a saved design by accident. */
    public static synchronized Map<Integer, String> get(String name) {
        load();
        Map<Integer, String> found = MAPS.get(name);
        return found == null ? null : new LinkedHashMap<>(found);
    }

    public static synchronized void put(String name, Map<Integer, String> cells) {
        load();
        if (name == null || name.isBlank() || cells == null || cells.isEmpty()) {
            return;
        }
        MAPS.put(name.trim(), new LinkedHashMap<>(cells));
        save();
    }

    public static synchronized void remove(String name) {
        load();
        if (MAPS.remove(name) != null) {
            save();
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject root = new JsonObject();
            for (Map.Entry<String, Map<Integer, String>> e : MAPS.entrySet()) {
                JsonObject obj = new JsonObject();
                for (Map.Entry<Integer, String> c : e.getValue().entrySet()) {
                    obj.addProperty(String.valueOf(c.getKey()), c.getValue());
                }
                root.add(e.getKey(), obj);
            }
            Files.writeString(FILE, root.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("Could not save the sim maps", e);
        }
    }
}
