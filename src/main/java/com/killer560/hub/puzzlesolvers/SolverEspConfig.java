package com.killer560.hub.puzzlesolvers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One shared setting for every puzzle/boss solver's world highlight - killer560, 2026-09-20: "make all
 * solvers ESP based for the waypoints". Rather than eleven identical "Through Walls" toggles this is a
 * single switch read by {@link SolverEspRender}, so flipping it anywhere flips it for Boulder, Quiz,
 * Ice Fill, Ice Path, Weirdos, Water Board, Creeper Beams, Blaze, Tic Tac Toe, Teleport Maze, Livid and
 * the P4 platform box at once. Its own tab is "Solver Highlights" in the New tab.
 * <p>
 * Deliberately NOT cheat-gated, unlike Dungeon ESP's / Thorn ESP's / P3 Nav's "Through Walls": those
 * reveal mobs and devices you cannot see. A solver highlight only ever redraws an answer the solver has
 * already worked out from data you were given, and both reference mods draw exactly these highlights
 * through blocks on their normal builds (NoammAddons' {@code BoulderSolver.renderBox(..., phase = true)};
 * QUOI's solvers ship with its "Depth check" switch off). See the staging notes for the full argument.
 */
public final class SolverEspConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-solveresp.json");

    private static SolverEspConfig instance;

    // Default ON: this IS the behaviour killer560 asked for, and every solver that uses it is itself
    // disabled by default, so a fresh install still draws nothing until a solver is turned on.
    private boolean throughWalls = true;
    /** How waypoint-style solver highlights are drawn (killer560, 2026-09-21: "for anything using waypoints in
     *  puzzle solvers add an option for full block and an option for outline"). */
    private WaypointStyle waypointStyle = WaypointStyle.OUTLINE;

    public enum WaypointStyle {
        OUTLINE("Outline"), FULL("Full Block");

        public final String label;

        WaypointStyle(String label) {
            this.label = label;
        }

        public WaypointStyle next() {
            return this == OUTLINE ? FULL : OUTLINE;
        }
    }

    private SolverEspConfig() {
    }

    public static SolverEspConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new SolverEspConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            SolverEspConfig cfg = new SolverEspConfig();
            cfg.throughWalls = ConfigJson.getBool(obj, "throughWalls", true);
            cfg.waypointStyle = ConfigJson.getEnum(obj, "waypointStyle", WaypointStyle.class, WaypointStyle.OUTLINE);
            instance = cfg;
        } catch (Exception e) {
            instance = new SolverEspConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("throughWalls", throughWalls);
            obj.addProperty("waypointStyle", waypointStyle.name());
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public WaypointStyle getWaypointStyle() {
        return waypointStyle;
    }

    public void setWaypointStyle(WaypointStyle s) {
        waypointStyle = s == null ? WaypointStyle.OUTLINE : s;
    }

    public boolean isThroughWalls() {
        return throughWalls;
    }

    public void setThroughWalls(boolean throughWalls) {
        this.throughWalls = throughWalls;
    }
}
