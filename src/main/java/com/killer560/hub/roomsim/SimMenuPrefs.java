package com.killer560.hub.roomsim;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;

import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What the Dungeon Sim menu remembers between opens: how each list is sorted. The page it was last on is session memory
 * only ({@link SimMenuNav}): a fresh game opens on the designer (killer560, 2026-10-15).
 *
 * <p>Read from disk every time the menu opens (never held across screens), so a profile that swaps this file is
 * simply what the next open shows - nothing to refresh in {@code ProfileManager}.
 */
public final class SimMenuPrefs {

    private static final Logger LOGGER = ModLog.get("killer560smod-roomsim");
    private static final Path FILE = ModPaths.config("killer560smod-sim-menu.json");

    /** The Load a Room list's sort keys. */
    public enum RoomSort {
        NAME("Name"), TYPE("Type"), SIZE("Size"), SECRETS("Secrets"), CRYPTS("Crypts"), ROUTES("Routes");

        public final String label;

        RoomSort(String label) {
            this.label = label;
        }
    }

    /** The saved runs' sort keys. */
    public enum RunSort {
        NEWEST("Newest"), OLDEST("Oldest"), SCORE("Best Score"), FASTEST("Fastest"), FLOOR("Floor");

        public final String label;

        RunSort(String label) {
            this.label = label;
        }
    }

    public RoomSort roomSort = RoomSort.NAME;
    public boolean roomDescending = false;
    public RunSort runSort = RunSort.NEWEST;
    /** "All" or a floor key ("F7", "M7"...). */
    public String runFloor = "All";

    private SimMenuPrefs() {
    }

    public static SimMenuPrefs load() {
        SimMenuPrefs p = new SimMenuPrefs();
        try {
            if (Files.exists(FILE)) {
                JsonObject o = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
                p.roomSort = ConfigJson.getEnum(o, "roomSort", RoomSort.class, p.roomSort);
                p.roomDescending = ConfigJson.getBool(o, "roomDescending", p.roomDescending);
                p.runSort = ConfigJson.getEnum(o, "runSort", RunSort.class, p.runSort);
                p.runFloor = ConfigJson.getString(o, "runFloor", p.runFloor);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read {}, using the defaults: {}", FILE.getFileName(), e.toString());
        }
        return p;
    }

    public void save() {
        JsonObject o = new JsonObject();
        o.addProperty("roomSort", roomSort.name());
        o.addProperty("roomDescending", roomDescending);
        o.addProperty("runSort", runSort.name());
        o.addProperty("runFloor", runFloor);
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, new GsonBuilder().setPrettyPrinting().create().toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("Could not save {}: {}", FILE.getFileName(), e.toString());
        }
    }
}
