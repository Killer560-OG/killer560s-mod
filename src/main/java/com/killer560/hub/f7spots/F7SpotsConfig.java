package com.killer560.hub.f7spots;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Persisted F7 Spots settings - {@code killer560smod-f7spots.json}. Everything ships OFF and both coordinate lists
 * ship EMPTY. Every feature getter ANDs {@link SkyblockGate#allows()}; the tab reads the {@code *Raw} getters.
 * Saved on every GUI change, reloaded by {@code ProfileManager.reloadAllConfigs()}.
 *
 * <h2>Hand-editing the file</h2>
 * <b>Walk-to waypoints</b> - {@code "walkWaypointList"}:
 * <pre>
 * "walkWaypointList": [
 *   { "x": 56, "y": 12, "z": 125, "label": "Purple stand", "phase": "P5", "floor": "M7", "color": 0 }
 * ]
 * </pre>
 * {@code x/y/z} are block coordinates (the box is drawn on the block at floor(x), floor(y), floor(z)).
 * {@code phase} is "ANY" or "P1".."P5". {@code floor} is "F7", "M7" or "BOTH". {@code color} is 0xAARRGGBB as a
 * decimal or negative int - use {@code 0} to follow the tab's "Waypoint Color". Missing keys fall back
 * (label "", phase "ANY", floor "BOTH", color 0); an entry without a usable x/y/z is skipped on its own and the
 * rest of the list still loads.
 * <p>
 * <b>Aim spots</b> - {@code "aimSpotList"}:
 * <pre>
 * "aimSpotList": [
 *   { "x": 56.5, "y": 17.5, "z": 125.5, "label": "LB purple", "situation": "P5_PURPLE", "class": "ARCHER", "color": 0 }
 * ]
 * </pre>
 * {@code x/y/z} here are exact positions, not blocks (a crosshair is drawn on the point). {@code situation} is one
 * of {@link AimSituation}'s names: ANY, P2_STORM, P3_TERMS, P5_RED, P5_ORANGE, P5_GREEN, P5_BLUE, P5_PURPLE.
 * {@code class} is ANY / MAGE / ARCHER / HEALER / BERSERKER / TANK.
 * <p>
 * Hand-edited files load on the next game start or when a settings profile is applied.
 */
public final class F7SpotsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("killer560smod-f7spots.json");

    public static final int DEFAULT_WALK_COLOR = 0xFFFFA040;
    public static final int DEFAULT_AIM_COLOR = 0xFF55FFFF;
    /** Storm's purple pad, matching the mod's existing P2 pad box (QUOI {@code AutoLeap.kt} via FastLeapFeature). */
    public static final int DEFAULT_PAD_COLOR = 0xFFAA00AA;

    public static final float MIN_BEAM_HEIGHT = 0f;
    public static final float MAX_BEAM_HEIGHT = 40f;
    public static final float MIN_AIM_SIZE = 0.1f;
    public static final float MAX_AIM_SIZE = 2.0f;
    public static final float MAX_CRUSH_INTERVAL = 120f;
    public static final float MAX_CRUSH_WARN = 15f;

    private static F7SpotsConfig instance;

    // ---- walk-to waypoints ----
    private boolean walkWaypoints = false;
    private boolean walkLabels = true;
    private boolean walkDistance = true;
    private boolean walkCurrentPhaseOnly = true;
    private float walkBeamHeight = 8f;
    private int walkColor = DEFAULT_WALK_COLOR;
    private final List<WalkWaypoint> walkWaypointList = new ArrayList<>();

    // ---- crush timer (F7/M7 P2 Storm) ----
    private boolean crushTimer = false;
    private boolean crushTitle = false;
    private boolean crushPadHighlight = false;
    private boolean crushAllPads = false;
    private int crushPadColor = DEFAULT_PAD_COLOR;
    /** 0 = unknown/off: the HUD then only counts UP since the last crush. See {@link CrushTimer}. */
    private float crushIntervalSeconds = 0f;
    private boolean padCycleTimer = false;
    private float crushWarnSeconds = 3f;
    /** Extra chat line (substring, case-insensitive) that also restarts the countdown. Blank = built-ins only. */
    private String crushExtraTrigger = "";

    // ---- aim spots ----
    private boolean aimSpots = false;
    private boolean aimLabels = true;
    private boolean aimDistance = false;
    private boolean aimAllClasses = false;
    private boolean aimAllSituations = false;
    private boolean aimArrowStack = false;
    private boolean aimDevonianLb = false;
    private float aimSize = 0.5f;
    private int aimColor = DEFAULT_AIM_COLOR;
    private final List<AimSpot> aimSpotList = new ArrayList<>();

    private F7SpotsConfig() {
    }

    public static F7SpotsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        F7SpotsConfig cfg = new F7SpotsConfig();
        JsonObject obj = null;
        if (Files.exists(CONFIG_PATH)) {
            try {
                obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                obj = null;
            }
        }
        if (obj != null) {
            cfg.walkWaypoints = ConfigJson.getBool(obj, "walkWaypoints", false);
            cfg.walkLabels = ConfigJson.getBool(obj, "walkLabels", true);
            cfg.walkDistance = ConfigJson.getBool(obj, "walkDistance", true);
            cfg.walkCurrentPhaseOnly = ConfigJson.getBool(obj, "walkCurrentPhaseOnly", true);
            cfg.setWalkBeamHeight(ConfigJson.getFloat(obj, "walkBeamHeight", 8f));
            cfg.walkColor = ConfigJson.getInt(obj, "walkColor", DEFAULT_WALK_COLOR);

            cfg.crushTimer = ConfigJson.getBool(obj, "crushTimer", false);
            cfg.crushTitle = ConfigJson.getBool(obj, "crushTitle", false);
            cfg.crushPadHighlight = ConfigJson.getBool(obj, "crushPadHighlight", false);
            cfg.crushAllPads = ConfigJson.getBool(obj, "crushAllPads", false);
            cfg.crushPadColor = ConfigJson.getInt(obj, "crushPadColor", DEFAULT_PAD_COLOR);
            cfg.setCrushIntervalSeconds(ConfigJson.getFloat(obj, "crushIntervalSeconds", 0f));
            cfg.padCycleTimer = ConfigJson.getBool(obj, "padCycleTimer", cfg.padCycleTimer);
            cfg.setCrushWarnSeconds(ConfigJson.getFloat(obj, "crushWarnSeconds", 3f));
            cfg.crushExtraTrigger = ConfigJson.getString(obj, "crushExtraTrigger", "");

            cfg.aimSpots = ConfigJson.getBool(obj, "aimSpots", false);
            cfg.aimLabels = ConfigJson.getBool(obj, "aimLabels", true);
            cfg.aimDistance = ConfigJson.getBool(obj, "aimDistance", false);
            cfg.aimAllClasses = ConfigJson.getBool(obj, "aimAllClasses", false);
            cfg.aimAllSituations = ConfigJson.getBool(obj, "aimAllSituations", false);
            cfg.aimArrowStack = ConfigJson.getBool(obj, "aimArrowStack", false);
            cfg.aimDevonianLb = ConfigJson.getBool(obj, "aimDevonianLb", false);
            cfg.setAimSize(ConfigJson.getFloat(obj, "aimSize", 0.5f));
            cfg.aimColor = ConfigJson.getInt(obj, "aimColor", DEFAULT_AIM_COLOR);

            JsonArray walk = ConfigJson.getArray(obj, "walkWaypointList");
            if (walk != null) {
                for (JsonElement el : walk) {
                    // One malformed entry is skipped on its own; the rest still load.
                    if (el == null || !el.isJsonObject()) {
                        continue;
                    }
                    JsonObject o = el.getAsJsonObject();
                    double x = ConfigJson.getDouble(o, "x", Double.NaN);
                    double y = ConfigJson.getDouble(o, "y", Double.NaN);
                    double z = ConfigJson.getDouble(o, "z", Double.NaN);
                    if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
                        continue;
                    }
                    cfg.walkWaypointList.add(new WalkWaypoint(x, y, z,
                            ConfigJson.getString(o, "label", ""),
                            ConfigJson.getInt(o, "color", 0),
                            ConfigJson.getString(o, "phase", WalkWaypoint.ANY_PHASE).toUpperCase(Locale.ROOT),
                            ConfigJson.getString(o, "floor", WalkWaypoint.BOTH_FLOORS).toUpperCase(Locale.ROOT)));
                }
            }
            JsonArray aim = ConfigJson.getArray(obj, "aimSpotList");
            if (aim != null) {
                for (JsonElement el : aim) {
                    if (el == null || !el.isJsonObject()) {
                        continue;
                    }
                    JsonObject o = el.getAsJsonObject();
                    double x = ConfigJson.getDouble(o, "x", Double.NaN);
                    double y = ConfigJson.getDouble(o, "y", Double.NaN);
                    double z = ConfigJson.getDouble(o, "z", Double.NaN);
                    if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
                        continue;
                    }
                    cfg.aimSpotList.add(new AimSpot(x, y, z,
                            ConfigJson.getString(o, "label", ""),
                            AimSituation.parse(ConfigJson.getString(o, "situation", AimSituation.ANY.name())),
                            ConfigJson.getString(o, "class", AimSpot.ANY_CLASS).toUpperCase(Locale.ROOT),
                            ConfigJson.getInt(o, "color", 0)));
                }
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("walkWaypoints", walkWaypoints);
            obj.addProperty("walkLabels", walkLabels);
            obj.addProperty("walkDistance", walkDistance);
            obj.addProperty("walkCurrentPhaseOnly", walkCurrentPhaseOnly);
            obj.addProperty("walkBeamHeight", walkBeamHeight);
            obj.addProperty("walkColor", walkColor);

            obj.addProperty("crushTimer", crushTimer);
            obj.addProperty("crushTitle", crushTitle);
            obj.addProperty("crushPadHighlight", crushPadHighlight);
            obj.addProperty("crushAllPads", crushAllPads);
            obj.addProperty("crushPadColor", crushPadColor);
            obj.addProperty("crushIntervalSeconds", crushIntervalSeconds);
            obj.addProperty("padCycleTimer", padCycleTimer);
            obj.addProperty("crushWarnSeconds", crushWarnSeconds);
            obj.addProperty("crushExtraTrigger", crushExtraTrigger == null ? "" : crushExtraTrigger);

            obj.addProperty("aimSpots", aimSpots);
            obj.addProperty("aimLabels", aimLabels);
            obj.addProperty("aimDistance", aimDistance);
            obj.addProperty("aimAllClasses", aimAllClasses);
            obj.addProperty("aimAllSituations", aimAllSituations);
            obj.addProperty("aimArrowStack", aimArrowStack);
            obj.addProperty("aimDevonianLb", aimDevonianLb);
            obj.addProperty("aimSize", aimSize);
            obj.addProperty("aimColor", aimColor);

            JsonArray walk = new JsonArray();
            for (WalkWaypoint w : walkWaypointList) {
                JsonObject o = new JsonObject();
                o.addProperty("x", w.x());
                o.addProperty("y", w.y());
                o.addProperty("z", w.z());
                o.addProperty("label", w.label() == null ? "" : w.label());
                o.addProperty("phase", w.phaseOrAny());
                o.addProperty("floor", w.floorOrBoth());
                o.addProperty("color", w.color());
                walk.add(o);
            }
            obj.add("walkWaypointList", walk);

            JsonArray aim = new JsonArray();
            for (AimSpot s : aimSpotList) {
                JsonObject o = new JsonObject();
                o.addProperty("x", s.x());
                o.addProperty("y", s.y());
                o.addProperty("z", s.z());
                o.addProperty("label", s.label() == null ? "" : s.label());
                o.addProperty("situation", s.situation() == null ? AimSituation.ANY.name() : s.situation().name());
                o.addProperty("class", s.classOrAny());
                o.addProperty("color", s.color());
                aim.add(o);
            }
            obj.add("aimSpotList", aim);

            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    // ---- walk-to waypoints ----
    public boolean isWalkWaypointsEnabled() { return walkWaypoints && SkyblockGate.allows(); }
    public boolean getWalkWaypointsRaw() { return walkWaypoints; }
    public void setWalkWaypoints(boolean v) { walkWaypoints = v; }

    public boolean isWalkLabels() { return walkLabels; }
    public void setWalkLabels(boolean v) { walkLabels = v; }

    public boolean isWalkDistance() { return walkDistance; }
    public void setWalkDistance(boolean v) { walkDistance = v; }

    public boolean isWalkCurrentPhaseOnly() { return walkCurrentPhaseOnly; }
    public void setWalkCurrentPhaseOnly(boolean v) { walkCurrentPhaseOnly = v; }

    public float getWalkBeamHeight() { return walkBeamHeight; }
    public void setWalkBeamHeight(float v) {
        walkBeamHeight = Math.max(MIN_BEAM_HEIGHT, Math.min(MAX_BEAM_HEIGHT, Math.round(v * 2f) / 2f));
    }

    public int getWalkColor() { return walkColor; }
    public void setWalkColor(int v) { walkColor = v; }

    /** Read-only view; mutate through {@link #addWalkWaypoint} / {@link #removeLastWalkWaypoint} (then {@link #save()}). */
    public List<WalkWaypoint> getWalkWaypoints() { return List.copyOf(walkWaypointList); }

    public void addWalkWaypoint(WalkWaypoint w) {
        if (w != null) {
            walkWaypointList.add(w);
        }
    }

    public boolean removeLastWalkWaypoint() {
        if (walkWaypointList.isEmpty()) {
            return false;
        }
        walkWaypointList.remove(walkWaypointList.size() - 1);
        return true;
    }

    // ---- crush timer ----
    public boolean isCrushTimerEnabled() { return crushTimer && SkyblockGate.allows(); }
    public boolean getCrushTimerRaw() { return crushTimer; }
    public void setCrushTimer(boolean v) { crushTimer = v; }

    public boolean isCrushTitleEnabled() { return crushTitle && SkyblockGate.allows(); }
    public boolean getCrushTitleRaw() { return crushTitle; }
    public void setCrushTitle(boolean v) { crushTitle = v; }

    public boolean isCrushPadHighlightEnabled() { return crushPadHighlight && SkyblockGate.allows(); }
    public boolean getCrushPadHighlightRaw() { return crushPadHighlight; }
    public void setCrushPadHighlight(boolean v) { crushPadHighlight = v; }

    public boolean isCrushAllPads() { return crushAllPads; }
    public void setCrushAllPads(boolean v) { crushAllPads = v; }

    public int getCrushPadColor() { return crushPadColor; }
    public void setCrushPadColor(int v) { crushPadColor = v; }

    /** NoammAddons' repeating 20-server-tick Storm pad cycle. */
    public boolean isPadCycleTimer() { return SkyblockGate.allows() && padCycleTimer; }

    public boolean isPadCycleTimerRaw() { return padCycleTimer; }

    public void setPadCycleTimer(boolean v) { padCycleTimer = v; }

    public float getCrushIntervalSeconds() { return crushIntervalSeconds; }
    public void setCrushIntervalSeconds(float v) {
        crushIntervalSeconds = Math.max(0f, Math.min(MAX_CRUSH_INTERVAL, Math.round(v * 2f) / 2f));
    }

    public float getCrushWarnSeconds() { return crushWarnSeconds; }
    public void setCrushWarnSeconds(float v) {
        crushWarnSeconds = Math.max(0f, Math.min(MAX_CRUSH_WARN, Math.round(v * 2f) / 2f));
    }

    public String getCrushExtraTrigger() { return crushExtraTrigger == null ? "" : crushExtraTrigger; }
    public void setCrushExtraTrigger(String v) { crushExtraTrigger = v == null ? "" : v.trim(); }

    // ---- aim spots ----
    public boolean isAimSpotsEnabled() { return aimSpots && SkyblockGate.allows(); }
    public boolean getAimSpotsRaw() { return aimSpots; }
    public void setAimSpots(boolean v) { aimSpots = v; }

    public boolean isAimLabels() { return aimLabels; }
    public void setAimLabels(boolean v) { aimLabels = v; }

    public boolean isAimDistance() { return aimDistance; }
    public void setAimDistance(boolean v) { aimDistance = v; }

    public boolean isAimAllClasses() { return aimAllClasses; }
    public void setAimAllClasses(boolean v) { aimAllClasses = v; }

    public boolean isAimAllSituations() { return aimAllSituations; }
    public void setAimAllSituations(boolean v) { aimAllSituations = v; }

    /** NoammAddons' arrow-stack aim points, see {@link AimSpots}. */
    public boolean isAimArrowStack() { return aimArrowStack; }
    public void setAimArrowStack(boolean v) { aimArrowStack = v; }

    /** Devonian's Last Breath waypoints for the five P5 dragons, see {@link AimSpots#DEVONIAN_LB}. */
    public boolean isAimDevonianLb() { return aimDevonianLb; }
    public void setAimDevonianLb(boolean v) { aimDevonianLb = v; }

    public float getAimSize() { return aimSize; }
    public void setAimSize(float v) {
        aimSize = Math.max(MIN_AIM_SIZE, Math.min(MAX_AIM_SIZE, Math.round(v * 20f) / 20f));
    }

    public int getAimColor() { return aimColor; }
    public void setAimColor(int v) { aimColor = v; }

    /** Read-only view; mutate through {@link #addAimSpot} / {@link #removeLastAimSpot} (then {@link #save()}). */
    public List<AimSpot> getAimSpots() { return List.copyOf(aimSpotList); }

    public void addAimSpot(AimSpot spot) {
        if (spot != null) {
            aimSpotList.add(spot);
        }
    }

    public boolean removeLastAimSpot() {
        if (aimSpotList.isEmpty()) {
            return false;
        }
        aimSpotList.remove(aimSpotList.size() - 1);
        return true;
    }
}
