package com.killer560.hub.p3nav;

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
 * Persisted F7/M7 Phase 3 navigation aid settings - see {@link GateHighlightFeature} and
 * {@link TerminalEspFeature}. Both aids ship disabled by default, same as every other new feature in
 * this mod, and every getter the features read ANDs {@link com.killer560.hub.util.SkyblockGate#allows()}.
 * <p>
 * Through Walls (terminals only) is cheat-build only and gated the same single-source-of-truth way
 * {@code MobEspConfig#isThroughWalls} is, so a legit jar can never report true even from a copied
 * cheat-build config file.
 */
public final class P3NavConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-p3nav.json");

    /** Same three modes NoammAddons' {@code GateHighlight}/{@code TerminalESP} "Mode" dropdown offers. */
    public enum Style {
        OUTLINE("Outline"), FILLED("Fill"), FILLED_OUTLINE("Filled Outline");

        public final String label;

        Style(String label) {
            this.label = label;
        }
    }

    public static final int DEFAULT_GATE_COLOR = 0xFFFFAA00;
    public static final int DEFAULT_TERMINAL_COLOR = 0xFFFFAA00;
    public static final int DEFAULT_DEVICE_COLOR = 0xFFFF6600;
    public static final float MIN_LINE_WIDTH = 1.0f;
    public static final float MAX_LINE_WIDTH = 10.0f;

    private static P3NavConfig instance;

    // ---- Gate Highlight ----
    private boolean gateHighlight = false;
    private int gateColor = DEFAULT_GATE_COLOR;
    private Style gateStyle = Style.FILLED_OUTLINE;
    private float gateLineWidth = 2.5f;
    /** Noamm's own behaviour: the box only draws while the gate's probe block is still there. Off = always
     *  draw the box for the section you're in, even after the gate is gone. */
    private boolean gateHideDestroyed = true;

    // ---- Terminal / Device ESP ----
    private boolean terminalEsp = false;
    private int terminalColor = DEFAULT_TERMINAL_COLOR;
    private Style terminalStyle = Style.FILLED;
    private float terminalLineWidth = 2.0f;
    /** Noamm only ever draws the section you're standing in; off draws every not-yet-done terminal in P3. */
    private boolean terminalCurrentSectionOnly = true;
    /** Devices/levers ("Not Activated" stands) - Noamm's TerminalESP does terminals only; this is the extra half. */
    private boolean deviceEsp = false;
    private int deviceColor = DEFAULT_DEVICE_COLOR;
    /** Cheat build only - see {@link #isTerminalThroughWalls()}. */
    private boolean terminalThroughWalls = false;

    private P3NavConfig() {
    }

    public static P3NavConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new P3NavConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            P3NavConfig cfg = new P3NavConfig();
            cfg.gateHighlight = ConfigJson.getBool(obj, "gateHighlight", false);
            cfg.gateColor = ConfigJson.getInt(obj, "gateColor", DEFAULT_GATE_COLOR);
            cfg.gateStyle = ConfigJson.getEnum(obj, "gateStyle", Style.class, Style.FILLED_OUTLINE);
            cfg.setGateLineWidth(ConfigJson.getFloat(obj, "gateLineWidth", 2.5f));
            cfg.gateHideDestroyed = ConfigJson.getBool(obj, "gateHideDestroyed", true);

            cfg.terminalEsp = ConfigJson.getBool(obj, "terminalEsp", false);
            cfg.terminalColor = ConfigJson.getInt(obj, "terminalColor", DEFAULT_TERMINAL_COLOR);
            cfg.terminalStyle = ConfigJson.getEnum(obj, "terminalStyle", Style.class, Style.FILLED);
            cfg.setTerminalLineWidth(ConfigJson.getFloat(obj, "terminalLineWidth", 2.0f));
            cfg.terminalCurrentSectionOnly = ConfigJson.getBool(obj, "terminalCurrentSectionOnly", true);
            cfg.deviceEsp = ConfigJson.getBool(obj, "deviceEsp", false);
            cfg.deviceColor = ConfigJson.getInt(obj, "deviceColor", DEFAULT_DEVICE_COLOR);
            cfg.terminalThroughWalls = ConfigJson.getBool(obj, "terminalThroughWalls", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new P3NavConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("gateHighlight", gateHighlight);
            obj.addProperty("gateColor", gateColor);
            obj.addProperty("gateStyle", gateStyle.name());
            obj.addProperty("gateLineWidth", gateLineWidth);
            obj.addProperty("gateHideDestroyed", gateHideDestroyed);
            obj.addProperty("terminalEsp", terminalEsp);
            obj.addProperty("terminalColor", terminalColor);
            obj.addProperty("terminalStyle", terminalStyle.name());
            obj.addProperty("terminalLineWidth", terminalLineWidth);
            obj.addProperty("terminalCurrentSectionOnly", terminalCurrentSectionOnly);
            obj.addProperty("deviceEsp", deviceEsp);
            obj.addProperty("deviceColor", deviceColor);
            obj.addProperty("terminalThroughWalls", terminalThroughWalls);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static boolean cheat() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED;
    }

    // ---- Gate Highlight ----
    public boolean isGateHighlightEnabled() {
        return gateHighlight && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean getGateHighlightRaw() { return gateHighlight; }
    public void setGateHighlight(boolean v) { gateHighlight = v; }

    public int getGateColor() { return gateColor; }
    public void setGateColor(int v) { gateColor = v; }

    public Style getGateStyle() { return gateStyle; }
    public void cycleGateStyle() {
        Style[] all = Style.values();
        gateStyle = all[(gateStyle.ordinal() + 1) % all.length];
    }

    public float getGateLineWidth() { return gateLineWidth; }
    public void setGateLineWidth(float v) { gateLineWidth = clampWidth(v); }

    public boolean getGateHideDestroyedRaw() { return gateHideDestroyed; }
    public void setGateHideDestroyed(boolean v) { gateHideDestroyed = v; }

    // ---- Terminal / Device ESP ----
    public boolean isTerminalEspEnabled() {
        return terminalEsp && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean getTerminalEspRaw() { return terminalEsp; }
    public void setTerminalEsp(boolean v) { terminalEsp = v; }

    public int getTerminalColor() { return terminalColor; }
    public void setTerminalColor(int v) { terminalColor = v; }

    public Style getTerminalStyle() { return terminalStyle; }
    public void cycleTerminalStyle() {
        Style[] all = Style.values();
        terminalStyle = all[(terminalStyle.ordinal() + 1) % all.length];
    }

    public float getTerminalLineWidth() { return terminalLineWidth; }
    public void setTerminalLineWidth(float v) { terminalLineWidth = clampWidth(v); }

    public boolean getTerminalCurrentSectionOnlyRaw() { return terminalCurrentSectionOnly; }
    public void setTerminalCurrentSectionOnly(boolean v) { terminalCurrentSectionOnly = v; }

    public boolean isDeviceEspEnabled() {
        return deviceEsp && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean getDeviceEspRaw() { return deviceEsp; }
    public void setDeviceEsp(boolean v) { deviceEsp = v; }

    public int getDeviceColor() { return deviceColor; }
    public void setDeviceColor(int v) { deviceColor = v; }

    /** Cheat build only - a legit jar never reports true. */
    public boolean isTerminalThroughWalls() { return cheat() && terminalThroughWalls; }
    public boolean getTerminalThroughWallsRaw() { return terminalThroughWalls; }
    public void setTerminalThroughWalls(boolean v) { terminalThroughWalls = v; }

    private static float clampWidth(float v) {
        return Math.max(MIN_LINE_WIDTH, Math.min(MAX_LINE_WIDTH, Math.round(v * 2.0f) / 2.0f));
    }
}
