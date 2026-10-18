package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * The world highlight settings of every puzzle/boss solver - one set PER SOLVER since 2026-10-18 (killer560: "remove solver
 * highlights and put each of those as an individual option in each of the solvers"). Before that a single shared switch,
 * Through Walls and Waypoint Style (the "Solver Highlights" tab, 2026-09-20: "make all solvers ESP based for the
 * waypoints") ruled all of them; those three values are now each solver's own, read by {@link SolverEspRender}, and each
 * solver's tab shows its rows ({@code gui/tab/SolverEspRows}).
 * <p>
 * A file written before the split holds the three shared values at its top level: every solver starts from them, so nobody's
 * highlights change until a solver is edited. Saved in {@code killer560smod-solveresp.json} (the name is kept).
 * <p>
 * Deliberately NOT cheat-gated, unlike Dungeon ESP's / Thorn ESP's / P3 Nav's "Through Walls": those reveal mobs and devices
 * you cannot see. A solver highlight only ever redraws an answer the solver has already worked out from data you were given,
 * and both reference mods draw exactly these highlights through blocks on their normal builds (NoammAddons'
 * {@code BoulderSolver.renderBox(..., phase = true)}; QUOI's solvers ship with its "Depth check" switch off).
 */
public final class SolverEspConfig {

    /** Every solver that draws a world highlight through {@link SolverEspRender}. */
    public enum Solver {
        BOULDER("Boulder Solver"), QUIZ("Quiz Solver"), ICE_FILL("Ice Fill Solver"), ICE_PATH("Ice Path Solver"),
        WEIRDOS("Weirdos Solver"), WATER("Water Board Solver"), BEAMS("Creeper Beams Solver"), BLAZE("Blaze Solver"),
        TIC_TAC_TOE("Tic Tac Toe Solver"), TELEPORT_MAZE("Teleport Maze Solver"), LIVID("Livid Solver"),
        P4_PLATFORM("P4 Platform Highlight");

        public final String label;

        Solver(String label) {
            this.label = label;
        }
    }

    public enum WaypointStyle {
        OUTLINE("Outline"), FULL("Full Block");

        public final String label;

        WaypointStyle(String label) {
            this.label = label;
        }

        public WaypointStyle next() {
            return this == OUTLINE ? FULL : OUTLINE;
        }

        public WaypointStyle previous() {
            return this == OUTLINE ? FULL : OUTLINE;
        }
    }

    /** One solver's highlight settings. Default ON for all three, as the shared ones were: every solver is itself off by
     *  default, so a fresh install still draws nothing until a solver is turned on. */
    private static final class Entry {
        boolean enabled = true;
        boolean throughWalls = true;
        /** How waypoint-style highlights are drawn (killer560, 2026-09-21: "for anything using waypoints in puzzle solvers
         *  add an option for full block and an option for outline"). */
        WaypointStyle waypointStyle = WaypointStyle.OUTLINE;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-solveresp.json");

    private static SolverEspConfig instance;

    private final Map<Solver, Entry> entries = new EnumMap<>(Solver.class);

    private SolverEspConfig() {
        for (Solver s : Solver.values()) {
            entries.put(s, new Entry());
        }
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
            // The shared values of a file from before the split are every solver's starting point.
            boolean sharedThroughWalls = ConfigJson.getBool(obj, "throughWalls", true);
            boolean sharedEnabled = ConfigJson.getBool(obj, "enabled", true);
            WaypointStyle sharedStyle = ConfigJson.getEnum(obj, "waypointStyle", WaypointStyle.class, WaypointStyle.OUTLINE);
            JsonObject solvers = obj.has("solvers") && obj.get("solvers").isJsonObject()
                    ? obj.getAsJsonObject("solvers") : new JsonObject();
            for (Solver s : Solver.values()) {
                Entry e = cfg.entries.get(s);
                JsonElement own = solvers.get(s.name());
                if (own != null && own.isJsonObject()) {
                    JsonObject o = own.getAsJsonObject();
                    e.enabled = ConfigJson.getBool(o, "enabled", sharedEnabled);
                    e.throughWalls = ConfigJson.getBool(o, "throughWalls", sharedThroughWalls);
                    e.waypointStyle = ConfigJson.getEnum(o, "waypointStyle", WaypointStyle.class, sharedStyle);
                } else {
                    e.enabled = sharedEnabled;
                    e.throughWalls = sharedThroughWalls;
                    e.waypointStyle = sharedStyle;
                }
            }
            instance = cfg;
        } catch (Exception e) {
            instance = new SolverEspConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            JsonObject solvers = new JsonObject();
            for (Solver s : Solver.values()) {
                Entry e = entries.get(s);
                JsonObject o = new JsonObject();
                o.addProperty("enabled", e.enabled);
                o.addProperty("throughWalls", e.throughWalls);
                o.addProperty("waypointStyle", e.waypointStyle.name());
                solvers.add(s.name(), o);
            }
            obj.add("solvers", solvers);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled(Solver solver) {
        return entries.get(solver).enabled;
    }

    public void setEnabled(Solver solver, boolean enabled) {
        entries.get(solver).enabled = enabled;
    }

    public boolean isThroughWalls(Solver solver) {
        return entries.get(solver).throughWalls;
    }

    public void setThroughWalls(Solver solver, boolean throughWalls) {
        entries.get(solver).throughWalls = throughWalls;
    }

    public WaypointStyle getWaypointStyle(Solver solver) {
        return entries.get(solver).waypointStyle;
    }

    public void setWaypointStyle(Solver solver, WaypointStyle s) {
        entries.get(solver).waypointStyle = s == null ? WaypointStyle.OUTLINE : s;
    }
}
