package com.killer560.hub.roomsim;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;

import org.slf4j.Logger;

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
    /** The Map Designer's Mobs choice: a floor with mobs, Fels, wither doors and keys ({@link SimMobFloor}). Off. */
    public boolean mobs = false;
    /** Real Mob Looks (simblood-1110): with Spawn Starred Mobs, a starred mob on a recorded spot is spawned as the type and
     *  look recorded there on Hypixel ({@link SimMobLooks}). Off. */
    public boolean realMobs = false;
    /** Watcher Blood Camp (simblood-1110): the Watcher and his waves in the floor's Blood room ({@link SimBloodCamp}). Off. */
    public boolean bloodCamp = false;
    /** Sim Teammates (siminfleap-1116): standing teammates in a sim, 0-4 ({@link SimTeammates}). 0. */
    public int teammates = 0;
    /** Sim Class (simclasses-1116): his dungeon class in a sim ({@link SimClasses}). Mage. */
    public com.killer560.hub.dungeonclass.DungeonClass simClass = com.killer560.hub.dungeonclass.DungeonClass.MAGE;
    /** Sim Class Level (simclasses-1116): 1-50, shown in the tab and scaling the class numbers. 50. */
    public int classLevel = SimClasses.MAX_LEVEL;

    /** The last read or saved prefs, for readers on a tick or at GO (no file read there); {@link #reload} drops it. */
    private static volatile SimMenuPrefs cached;

    private SimMenuPrefs() {
    }

    public static SimMenuPrefs load() {
        SimMenuPrefs p = new SimMenuPrefs();
        JsonObject o = ConfigJson.read(FILE);
        try {
            if (o != null) {
                p.roomSort = ConfigJson.getEnum(o, "roomSort", RoomSort.class, p.roomSort);
                p.roomDescending = ConfigJson.getBool(o, "roomDescending", p.roomDescending);
                p.runSort = ConfigJson.getEnum(o, "runSort", RunSort.class, p.runSort);
                p.runFloor = ConfigJson.getString(o, "runFloor", p.runFloor);
                p.mobs = ConfigJson.getBool(o, "mobs", p.mobs);
                p.realMobs = ConfigJson.getBool(o, "realMobs", p.realMobs);
                p.bloodCamp = ConfigJson.getBool(o, "bloodCamp", p.bloodCamp);
                p.teammates = Math.max(0, Math.min(SimTeammates.MAX, ConfigJson.getInt(o, "teammates", p.teammates)));
                p.simClass = ConfigJson.getEnum(o, "simClass", com.killer560.hub.dungeonclass.DungeonClass.class, p.simClass);
                p.classLevel = Math.max(1, Math.min(SimClasses.MAX_LEVEL, ConfigJson.getInt(o, "classLevel", p.classLevel)));
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read {}, using the defaults: {}", FILE.getFileName(), e.toString());
        }
        cached = p;
        return p;
    }

    /** The prefs as last read or saved (read once if never). */
    public static SimMenuPrefs cached() {
        SimMenuPrefs p = cached;
        return p != null ? p : load();
    }

    /** A profile was applied: the next {@link #cached} reads the file again. */
    public static void reload() {
        cached = null;
    }

    public void save() {
        JsonObject o = new JsonObject();
        o.addProperty("roomSort", roomSort.name());
        o.addProperty("roomDescending", roomDescending);
        o.addProperty("runSort", runSort.name());
        o.addProperty("runFloor", runFloor);
        o.addProperty("mobs", mobs);
        o.addProperty("realMobs", realMobs);
        o.addProperty("bloodCamp", bloodCamp);
        o.addProperty("teammates", teammates);
        o.addProperty("simClass", simClass.name());
        o.addProperty("classLevel", classLevel);
        cached = this;
        ConfigJson.write(FILE, o);
    }
}
