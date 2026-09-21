package com.killer560.hub.thorn;

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
 * Persisted Thorn (F4/M4 boss) settings - {@code killer560smod-thorn.json}. Everything ships OFF. Every feature getter
 * ANDs {@link SkyblockGate#allows()}; the tab reads the {@code *Raw} getters.
 * <p>
 * <b>Stun spots</b> ({@link #getStunSpots()}) are the extension point for killer560's stun-spot coordinates: a JSON
 * array {@code "stunSpotList": [{"x": 0.0, "y": 0.0, "z": 0.0, "label": "Spot 1", "floor": "ANY"}]} in this file, his three built-in spots by
 * default. {@code floor} is "F4", "M4" or "ANY" (missing = ANY). Coordinates are block coordinates (a waypoint box is
 * drawn on the block at floor(x), floor(y), floor(z)). Hand-edited files load on the next restart or profile apply.
 */
public final class ThornConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("killer560smod-thorn.json");

    public enum Style {
        OUTLINE("Outline Box"), FILLED("Filled Box"), GLOW("Glow");

        public final String label;

        Style(String label) {
            this.label = label;
        }
    }

    /** One stun-spot waypoint. {@code floor} is "F4", "M4" or "ANY". */
    public record StunSpot(double x, double y, double z, String label, String floor) {
        public boolean appliesTo(String currentFloor) {
            return floor == null || floor.isBlank() || "ANY".equalsIgnoreCase(floor)
                    || floor.equalsIgnoreCase(currentFloor);
        }
    }

    // Defaults: Spirit Bear magenta + Spirit Bow cyan are NoammAddons F4Features' own defaults.
    public static final int DEFAULT_BEAR_COLOR = 0xFFFF55FF;
    public static final int DEFAULT_THORN_COLOR = 0xFFFF5555;
    public static final int DEFAULT_MOB_COLOR = 0xFFFFAA00;
    public static final int DEFAULT_BOW_COLOR = 0xFF55FFFF;
    public static final int DEFAULT_STUN_SPOT_COLOR = 0xFFFFA040;
    public static final float MIN_LINE_WIDTH = 1.0f;
    public static final float MAX_LINE_WIDTH = 10.0f;

    private static ThornConfig instance;

    // ---- Spirit Bear counter ----
    private boolean bearHud = false;
    private boolean showOverkill = false;
    private boolean overkillChat = false;

    // ---- ESP ----
    private boolean bearEsp = false;
    /** Highlight on Thorn himself (the ghast boss) - added 2026-09-21 after killer560 looked for one there. */
    private boolean thornEsp = false;
    private int thornColor = DEFAULT_THORN_COLOR;
    private boolean mobEsp = false;
    private boolean bowEsp = false;
    private int bearColor = DEFAULT_BEAR_COLOR;
    private int mobColor = DEFAULT_MOB_COLOR;
    private int bowColor = DEFAULT_BOW_COLOR;
    private Style style = Style.OUTLINE;
    private float lineWidth = 2.0f;
    /** Cheat build only - see {@link #isThroughWalls()}. */
    private boolean throughWalls = false;

    // ---- Stun spots ----
    private boolean stunSpots = false;
    private boolean stunSpotLabels = true;
    private int stunSpotColor = DEFAULT_STUN_SPOT_COLOR;
    private final List<StunSpot> stunSpotList = new ArrayList<>(DEFAULT_STUN_SPOTS);

    /** killer560's own three F4/M4 stun spots (recorded 2026-09-21 in his test instance), each shifted one block down
     *  as he asked, so the box sits on the block you stand on. The Thorn room is the same on M4, hence ANY. Used
     *  whenever a config has no saved list of its own; a saved list (even an empty one) always wins. */
    public static final List<StunSpot> DEFAULT_STUN_SPOTS = List.of(
            new StunSpot(-5.0, 83.0, 27.0, "Stun 1", "ANY"),
            new StunSpot(27.0, 81.0, 18.0, "Stun 2", "ANY"),
            new StunSpot(6.0, 68.0, 4.0, "Stun 3", "ANY"));

    private ThornConfig() {
    }

    public static ThornConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        ThornConfig cfg = new ThornConfig();
        JsonObject obj = null;
        if (Files.exists(CONFIG_PATH)) {
            try {
                obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                obj = null;
            }
        }
        if (obj != null) {
            cfg.bearHud = ConfigJson.getBool(obj, "bearHud", false);
            cfg.showOverkill = ConfigJson.getBool(obj, "showOverkill", false);
            cfg.overkillChat = ConfigJson.getBool(obj, "overkillChat", false);
            cfg.bearEsp = ConfigJson.getBool(obj, "bearEsp", false);
            cfg.thornEsp = ConfigJson.getBool(obj, "thornEsp", false);
            cfg.thornColor = ConfigJson.getInt(obj, "thornColor", DEFAULT_THORN_COLOR);
            cfg.mobEsp = ConfigJson.getBool(obj, "mobEsp", false);
            cfg.bowEsp = ConfigJson.getBool(obj, "bowEsp", false);
            cfg.bearColor = ConfigJson.getInt(obj, "bearColor", DEFAULT_BEAR_COLOR);
            cfg.mobColor = ConfigJson.getInt(obj, "mobColor", DEFAULT_MOB_COLOR);
            cfg.bowColor = ConfigJson.getInt(obj, "bowColor", DEFAULT_BOW_COLOR);
            cfg.style = ConfigJson.getEnum(obj, "style", Style.class, Style.OUTLINE);
            cfg.setLineWidth(ConfigJson.getFloat(obj, "lineWidth", 2.0f));
            cfg.throughWalls = ConfigJson.getBool(obj, "throughWalls", false);
            cfg.stunSpots = ConfigJson.getBool(obj, "stunSpots", false);
            cfg.stunSpotLabels = ConfigJson.getBool(obj, "stunSpotLabels", true);
            cfg.stunSpotColor = ConfigJson.getInt(obj, "stunSpotColor", DEFAULT_STUN_SPOT_COLOR);
            JsonArray spots = ConfigJson.getArray(obj, "stunSpotList");
            if (spots != null) {
                cfg.stunSpotList.clear();
                for (JsonElement el : spots) {
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
                    cfg.stunSpotList.add(new StunSpot(x, y, z, ConfigJson.getString(o, "label", ""),
                            ConfigJson.getString(o, "floor", "ANY").toUpperCase(Locale.ROOT)));
                }
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("bearHud", bearHud);
            obj.addProperty("showOverkill", showOverkill);
            obj.addProperty("overkillChat", overkillChat);
            obj.addProperty("bearEsp", bearEsp);
            obj.addProperty("thornEsp", thornEsp);
            obj.addProperty("thornColor", thornColor);
            obj.addProperty("mobEsp", mobEsp);
            obj.addProperty("bowEsp", bowEsp);
            obj.addProperty("bearColor", bearColor);
            obj.addProperty("mobColor", mobColor);
            obj.addProperty("bowColor", bowColor);
            obj.addProperty("style", style.name());
            obj.addProperty("lineWidth", lineWidth);
            obj.addProperty("throughWalls", throughWalls);
            obj.addProperty("stunSpots", stunSpots);
            obj.addProperty("stunSpotLabels", stunSpotLabels);
            obj.addProperty("stunSpotColor", stunSpotColor);
            JsonArray spots = new JsonArray();
            for (StunSpot s : stunSpotList) {
                JsonObject o = new JsonObject();
                o.addProperty("x", s.x());
                o.addProperty("y", s.y());
                o.addProperty("z", s.z());
                o.addProperty("label", s.label() == null ? "" : s.label());
                o.addProperty("floor", s.floor() == null ? "ANY" : s.floor());
                spots.add(o);
            }
            obj.add("stunSpotList", spots);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static boolean cheat() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED;
    }

    // ---- Spirit Bear counter ----
    public boolean isBearHudEnabled() { return bearHud && SkyblockGate.allows(); }
    public boolean getBearHudRaw() { return bearHud; }
    public void setBearHud(boolean v) { bearHud = v; }

    public boolean isShowOverkill() { return showOverkill && SkyblockGate.allows(); }
    public boolean getShowOverkillRaw() { return showOverkill; }
    public void setShowOverkill(boolean v) { showOverkill = v; }

    public boolean isOverkillChatEnabled() { return overkillChat && SkyblockGate.allows(); }
    public boolean getOverkillChatRaw() { return overkillChat; }
    public void setOverkillChat(boolean v) { overkillChat = v; }

    // ---- ESP targets ----
    public boolean isBearEspEnabled() { return bearEsp && SkyblockGate.allows(); }
    public boolean isThornEspEnabled() { return thornEsp && SkyblockGate.allows(); }
    public boolean getThornEspRaw() { return thornEsp; }
    public void setThornEsp(boolean v) { thornEsp = v; }
    public int getThornColor() { return thornColor; }
    public void setThornColor(int v) { thornColor = v; }
    public boolean getBearEspRaw() { return bearEsp; }
    public void setBearEsp(boolean v) { bearEsp = v; }

    public boolean isMobEspEnabled() { return mobEsp && SkyblockGate.allows(); }
    public boolean getMobEspRaw() { return mobEsp; }
    public void setMobEsp(boolean v) { mobEsp = v; }

    public boolean isBowEspEnabled() { return bowEsp && SkyblockGate.allows(); }
    public boolean getBowEspRaw() { return bowEsp; }
    public void setBowEsp(boolean v) { bowEsp = v; }

    public boolean isAnyEspEnabled() {
        return isBearEspEnabled() || isMobEspEnabled() || isBowEspEnabled() || isThornEspEnabled();
    }

    public int getBearColor() { return bearColor; }
    public void setBearColor(int v) { bearColor = v; }
    public int getMobColor() { return mobColor; }
    public void setMobColor(int v) { mobColor = v; }
    public int getBowColor() { return bowColor; }
    public void setBowColor(int v) { bowColor = v; }

    public Style getStyle() { return style; }
    public void cycleStyle() {
        Style[] all = Style.values();
        style = all[(style.ordinal() + 1) % all.length];
    }

    public float getLineWidth() { return lineWidth; }
    public void setLineWidth(float v) {
        lineWidth = Math.max(MIN_LINE_WIDTH, Math.min(MAX_LINE_WIDTH, Math.round(v * 2.0f) / 2.0f));
    }

    /** Same rule as Dungeon ESP: the legit jar can never report true, even from a copied cheat-build config. */
    public boolean isThroughWalls() { return cheat() && throughWalls; }
    public boolean getThroughWallsRaw() { return throughWalls; }
    public void setThroughWalls(boolean v) { throughWalls = v; }

    // ---- Stun spots ----
    public boolean isStunSpotsEnabled() { return stunSpots && SkyblockGate.allows(); }
    public boolean getStunSpotsRaw() { return stunSpots; }
    public void setStunSpots(boolean v) { stunSpots = v; }

    public boolean isStunSpotLabels() { return stunSpotLabels; }
    public void setStunSpotLabels(boolean v) { stunSpotLabels = v; }

    public int getStunSpotColor() { return stunSpotColor; }
    public void setStunSpotColor(int v) { stunSpotColor = v; }

    /** Read-only view; mutate through {@link #addStunSpot} / {@link #removeLastStunSpot} (then {@link #save()}). */
    public List<StunSpot> getStunSpots() { return List.copyOf(stunSpotList); }

    public void addStunSpot(StunSpot spot) {
        if (spot != null) {
            stunSpotList.add(spot);
        }
    }

    public boolean removeLastStunSpot() {
        if (stunSpotList.isEmpty()) {
            return false;
        }
        stunSpotList.remove(stunSpotList.size() - 1);
        return true;
    }
}
