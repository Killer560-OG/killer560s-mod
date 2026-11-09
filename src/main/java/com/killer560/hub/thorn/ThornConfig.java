package com.killer560.hub.thorn;

import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.BoolFields;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;

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

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-thorn.json");

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

    private boolean bearHud = false;
    private boolean showOverkill = false;
    private boolean overkillChat = false;
    /** killer560: "For overkill chat make an option to send it to party chat as well." Off by default -
     *  unlike Overkill Chat (client-side only), this is a real message in Party Chat everyone can see. */
    private boolean overkillToParty = false;

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
    /** Cheat build only: every non-bat mob in the Thorn fight, through walls (killer560, 2026-10-15). */
    private boolean allMobsThroughWalls = false;
    /** Cheat build only: the bats of the Thorn fight, through walls (killer560, 2026-10-15). */
    private boolean batsThroughWalls = false;

    private boolean stunSpots = false;
    private boolean stunSpotLabels = true;

    // ---- Thorn Triggerbot (cheat build, killer560 2026-11-01) ----
    private boolean triggerbot = false;
    private boolean triggerbotAimed = true;
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

    /** The plain on/off settings: key, default, and how to read and write the field. */
    private static final BoolFields<ThornConfig> BOOLS = new BoolFields<ThornConfig>()
            .add("bearHud", false, c -> c.bearHud, (c, v) -> c.bearHud = v)
            .add("showOverkill", false, c -> c.showOverkill, (c, v) -> c.showOverkill = v)
            .add("overkillChat", false, c -> c.overkillChat, (c, v) -> c.overkillChat = v)
            .add("overkillToParty", false, c -> c.overkillToParty, (c, v) -> c.overkillToParty = v)
            .add("bearEsp", false, c -> c.bearEsp, (c, v) -> c.bearEsp = v)
            .add("thornEsp", false, c -> c.thornEsp, (c, v) -> c.thornEsp = v)
            .add("mobEsp", false, c -> c.mobEsp, (c, v) -> c.mobEsp = v)
            .add("bowEsp", false, c -> c.bowEsp, (c, v) -> c.bowEsp = v)
            .add("throughWalls", false, c -> c.throughWalls, (c, v) -> c.throughWalls = v)
            .add("allMobsThroughWalls", false, c -> c.allMobsThroughWalls, (c, v) -> c.allMobsThroughWalls = v)
            .add("batsThroughWalls", false, c -> c.batsThroughWalls, (c, v) -> c.batsThroughWalls = v)
            .add("stunSpots", false, c -> c.stunSpots, (c, v) -> c.stunSpots = v)
            .add("stunSpotLabels", true, c -> c.stunSpotLabels, (c, v) -> c.stunSpotLabels = v)
            .add("triggerbot", false, c -> c.triggerbot, (c, v) -> c.triggerbot = v)
            .add("triggerbotAimed", true, c -> c.triggerbotAimed, (c, v) -> c.triggerbotAimed = v);

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
            // A "Thorn as a whole" switch (2026-10-13) sat over the counter, the highlights and the stun spots and is gone
            // (2026-10-15): they are independent. A file saved with it OFF had them all standing down, so they load OFF
            // (see below); the key is not written back.
            boolean legacyWholeOff = obj.has("thornEnabled") && !ConfigJson.getBool(obj, "thornEnabled", true);
            BOOLS.load(cfg, obj);
            cfg.thornColor = ConfigJson.getInt(obj, "thornColor", DEFAULT_THORN_COLOR);
            cfg.bearColor = ConfigJson.getInt(obj, "bearColor", DEFAULT_BEAR_COLOR);
            cfg.mobColor = ConfigJson.getInt(obj, "mobColor", DEFAULT_MOB_COLOR);
            cfg.bowColor = ConfigJson.getInt(obj, "bowColor", DEFAULT_BOW_COLOR);
            cfg.style = ConfigJson.getEnum(obj, "style", Style.class, Style.OUTLINE);
            cfg.setLineWidth(ConfigJson.getFloat(obj, "lineWidth", 2.0f));
            cfg.stunSpotColor = ConfigJson.getInt(obj, "stunSpotColor", DEFAULT_STUN_SPOT_COLOR);
            if (legacyWholeOff) {
                cfg.bearHud = false;
                cfg.showOverkill = false;
                cfg.overkillChat = false;
                cfg.overkillToParty = false;
                cfg.bearEsp = false;
                cfg.thornEsp = false;
                cfg.mobEsp = false;
                cfg.bowEsp = false;
                cfg.stunSpots = false;
            }
            JsonArray spots = ConfigJson.getArray(obj, "stunSpotList");
            if (spots != null) {
                cfg.stunSpotList.clear();
                for (JsonElement el : spots) {
                    // One malformed entry is skipped on its own; the rest still load.
                    if (el == null || !el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    double x = ConfigJson.getDouble(o, "x", Double.NaN);
                    double y = ConfigJson.getDouble(o, "y", Double.NaN);
                    double z = ConfigJson.getDouble(o, "z", Double.NaN);
                    if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) continue;
                    cfg.stunSpotList.add(new StunSpot(x, y, z, ConfigJson.getString(o, "label", ""),
                            ConfigJson.getString(o, "floor", "ANY").toUpperCase(Locale.ROOT)));
                }
            }
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        BOOLS.save(this, obj);
        obj.addProperty("thornColor", thornColor);
        obj.addProperty("bearColor", bearColor);
        obj.addProperty("mobColor", mobColor);
        obj.addProperty("bowColor", bowColor);
        obj.addProperty("style", style.name());
        obj.addProperty("lineWidth", lineWidth);
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
        ConfigJson.write(CONFIG_PATH, obj);
    }

    private static boolean cheat() { return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED; }

    /** The Skyblock gate, which every effective getter below ANDs in. */
    private boolean live() { return SkyblockGate.allows(); }

    public boolean isBearHudEnabled() { return bearHud && live(); }
    public boolean getBearHudRaw() { return bearHud; }
    public void setBearHud(boolean v) { bearHud = v; }

    public boolean isShowOverkill() { return showOverkill && live(); }
    public boolean getShowOverkillRaw() { return showOverkill; }
    public void setShowOverkill(boolean v) { showOverkill = v; }

    public boolean isOverkillChatEnabled() { return overkillChat && live(); }
    public boolean getOverkillChatRaw() { return overkillChat; }
    public void setOverkillChat(boolean v) { overkillChat = v; }

    public boolean isOverkillToPartyEnabled() { return overkillToParty && live(); }
    public boolean getOverkillToPartyRaw() { return overkillToParty; }
    public void setOverkillToParty(boolean v) { overkillToParty = v; }

    public boolean isBearEspEnabled() { return bearEsp && live(); }
    public boolean isThornEspEnabled() { return thornEsp && live(); }
    public boolean getThornEspRaw() { return thornEsp; }
    public void setThornEsp(boolean v) { thornEsp = v; }
    public int getThornColor() { return thornColor; }
    public void setThornColor(int v) { thornColor = v; }
    public boolean getBearEspRaw() { return bearEsp; }
    public void setBearEsp(boolean v) { bearEsp = v; }

    public boolean isMobEspEnabled() { return mobEsp && live(); }
    public boolean getMobEspRaw() { return mobEsp; }
    public void setMobEsp(boolean v) { mobEsp = v; }

    public boolean isBowEspEnabled() { return bowEsp && live(); }
    public boolean getBowEspRaw() { return bowEsp; }
    public void setBowEsp(boolean v) { bowEsp = v; }

    public boolean isAnyEspEnabled() {
        return isBearEspEnabled() || isMobEspEnabled() || isBowEspEnabled() || isThornEspEnabled()
                || isAllMobsThroughWalls() || isBatsThroughWalls();
    }

    public int getBearColor() { return bearColor; }
    public void setBearColor(int v) { bearColor = v; }
    public int getMobColor() { return mobColor; }
    public void setMobColor(int v) { mobColor = v; }
    public int getBowColor() { return bowColor; }
    public void setBowColor(int v) { bowColor = v; }

    public Style getStyle() { return style; }
    public void cycleStyle() { cycleStyle(1); }

    public void cycleStyle(int dir) {
        Style[] all = Style.values();
        style = all[Math.floorMod(style.ordinal() + dir, all.length)];
    }

    public float getLineWidth() { return lineWidth; }
    public void setLineWidth(float v) {
        lineWidth = Math.max(MIN_LINE_WIDTH, Math.min(MAX_LINE_WIDTH, Math.round(v * 2.0f) / 2.0f));
    }

    /** Same rule as Dungeon ESP: the legit jar can never report true, even from a copied cheat-build config. */
    public boolean isThroughWalls() { return cheat() && throughWalls; }
    public boolean getThroughWallsRaw() { return throughWalls; }
    public void setThroughWalls(boolean v) { throughWalls = v; }

    /** Every non-bat mob drawn through walls: its own switch, independent of the highlights above. Never true in the legit jar. */
    public boolean isAllMobsThroughWalls() { return cheat() && allMobsThroughWalls && live(); }
    public boolean getAllMobsThroughWallsRaw() { return allMobsThroughWalls; }
    public void setAllMobsThroughWalls(boolean v) { allMobsThroughWalls = v; }

    /** The bats drawn through walls. Never true in the legit jar. */
    public boolean isBatsThroughWalls() { return cheat() && batsThroughWalls && live(); }
    public boolean getBatsThroughWallsRaw() { return batsThroughWalls; }
    public void setBatsThroughWalls(boolean v) { batsThroughWalls = v; }

    public boolean isStunSpotsEnabled() { return stunSpots && live(); }
    public boolean getStunSpotsRaw() { return stunSpots; }
    public void setStunSpots(boolean v) { stunSpots = v; }

    /** Thorn Triggerbot: hold right click while holding the Tribal Spear in the Thorn fight. Cheat build only. */
    public boolean isTriggerbotEnabled() { return cheat() && triggerbot && live(); }
    public boolean isTriggerbotRaw() { return triggerbot; }
    public void setTriggerbot(boolean v) { triggerbot = v; }
    /** Only while the crosshair line meets Thorn with no BLOCK in the way (bats and other mobs do not count). */
    public boolean isTriggerbotAimed() { return triggerbotAimed; }
    public void setTriggerbotAimed(boolean v) { triggerbotAimed = v; }

    public boolean isStunSpotLabels() { return stunSpotLabels; }
    public void setStunSpotLabels(boolean v) { stunSpotLabels = v; }

    public int getStunSpotColor() { return stunSpotColor; }
    public void setStunSpotColor(int v) { stunSpotColor = v; }

    /** Read-only view; mutate through {@link #addStunSpot} / {@link #removeLastStunSpot} (then {@link #save()}). */
    public List<StunSpot> getStunSpots() { return java.util.Collections.unmodifiableList(stunSpotList); }

    public void addStunSpot(StunSpot spot) {
        if (spot != null) {
            stunSpotList.add(spot);
        }
    }

    public boolean removeLastStunSpot() {
        if (stunSpotList.isEmpty()) return false;
        stunSpotList.remove(stunSpotList.size() - 1);
        return true;
    }
}
