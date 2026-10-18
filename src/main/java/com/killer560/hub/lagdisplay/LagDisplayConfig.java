package com.killer560.hub.lagdisplay;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Performance HUD settings ({@code killer560smod-lagdisplay.json} - filename kept from the old
 * "Lag Display" name so nobody's saved position/scale is lost by the rename). Ships OFF; reads go through
 * {@link ConfigJson} per key, and every GUI change calls {@link #save()} at the call site.
 *
 * <p>Renamed from "Lag Display" to "Performance HUD" (killer560, 2026-09-27) and its "server lag" ("zzz for
 * N.NNs") line replaced with a real TPS readout - see {@link com.killer560.hub.lagdisplay.LagDisplayFeature}
 * for the TPS math. The Lag Threshold slider that used to gate the old lag line is gone with it (nothing
 * left to threshold-gate).
 *
 * <p><b>Skyblock gate:</b> deliberately NOT applied here. Devonian gates its own {@code LagDisplay} on
 * {@code Location.stateInSkyblock}, but ping / TPS / FPS / CPS are plain client and network readouts that
 * are just as useful on p3sim, on a test server or in a lobby, and none of them reads or reacts to anything
 * Skyblock-specific. The drawn element is still covered by {@code HudInGameRenderer}'s own
 * {@code SkyblockGate.allows()} check, so with "Skyblock Only" on it stops drawing outside Skyblock/p3sim
 * anyway - the difference is only that the setting itself stays honest about what the player turned on.
 */
public final class LagDisplayConfig {

    private static final Logger LOGGER = ModLog.get("killer560smod-lagdisplay");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-lagdisplay.json");

    private static LagDisplayConfig instance;

    private boolean enabled = false;
    private boolean showTps = true;
    private boolean showPing = true;
    private boolean showFps = true;
    private boolean showCps = false;
    private boolean colorByValue = true;
    // Custom colours (killer560, 2026-10-18). Good/Ok/Bad/Awful colour a value when Color By Value is on; Text is
    // every line when it is off and the CPS line always. Opaque ARGB.
    public static final int DEFAULT_GOOD = 0xFF55FF55;
    public static final int DEFAULT_OK = 0xFFFFFF55;
    public static final int DEFAULT_BAD = 0xFFFFAA00;
    public static final int DEFAULT_AWFUL = 0xFFFF5555;
    public static final int DEFAULT_TEXT = 0xFFFFFFFF;
    private int colorGood = DEFAULT_GOOD;
    private int colorOk = DEFAULT_OK;
    private int colorBad = DEFAULT_BAD;
    private int colorAwful = DEFAULT_AWFUL;
    private int colorText = DEFAULT_TEXT;

    private LagDisplayConfig() {
    }

    public static LagDisplayConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        LagDisplayConfig cfg = new LagDisplayConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(root, "enabled", cfg.enabled);
                cfg.showTps = ConfigJson.getBool(root, "showTps", cfg.showTps);
                cfg.showPing = ConfigJson.getBool(root, "showPing", cfg.showPing);
                cfg.showFps = ConfigJson.getBool(root, "showFps", cfg.showFps);
                cfg.showCps = ConfigJson.getBool(root, "showCps", cfg.showCps);
                cfg.colorByValue = ConfigJson.getBool(root, "colorByValue", cfg.colorByValue);
                cfg.colorGood = ConfigJson.getInt(root, "colorGood", cfg.colorGood);
                cfg.colorOk = ConfigJson.getInt(root, "colorOk", cfg.colorOk);
                cfg.colorBad = ConfigJson.getInt(root, "colorBad", cfg.colorBad);
                cfg.colorAwful = ConfigJson.getInt(root, "colorAwful", cfg.colorAwful);
                cfg.colorText = ConfigJson.getInt(root, "colorText", cfg.colorText);
            } catch (Exception e) {
                LOGGER.warn("[LagDisplay] Couldn't read {} - keeping the file, using defaults this session: {}",
                        CONFIG_PATH.getFileName(), e.toString());
                instance = new LagDisplayConfig();
                return;
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);
            root.addProperty("showTps", showTps);
            root.addProperty("showPing", showPing);
            root.addProperty("showFps", showFps);
            root.addProperty("showCps", showCps);
            root.addProperty("colorByValue", colorByValue);
            root.addProperty("colorGood", colorGood);
            root.addProperty("colorOk", colorOk);
            root.addProperty("colorBad", colorBad);
            root.addProperty("colorAwful", colorAwful);
            root.addProperty("colorText", colorText);
            Files.writeString(CONFIG_PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[LagDisplay] Couldn't save {}: {}", CONFIG_PATH.getFileName(), e.toString());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public boolean isShowTps() {
        return showTps;
    }

    public void setShowTps(boolean v) {
        showTps = v;
    }

    public boolean isShowPing() {
        return showPing;
    }

    public void setShowPing(boolean v) {
        showPing = v;
    }

    public boolean isShowFps() {
        return showFps;
    }

    public void setShowFps(boolean v) {
        showFps = v;
    }

    public boolean isShowCps() {
        return showCps;
    }

    public void setShowCps(boolean v) {
        showCps = v;
    }

    public boolean isColorByValue() {
        return colorByValue;
    }

    public void setColorByValue(boolean v) {
        colorByValue = v;
    }

    public int getColorGood() {
        return colorGood;
    }

    public void setColorGood(int argb) {
        colorGood = argb | 0xFF000000;
    }

    public int getColorOk() {
        return colorOk;
    }

    public void setColorOk(int argb) {
        colorOk = argb | 0xFF000000;
    }

    public int getColorBad() {
        return colorBad;
    }

    public void setColorBad(int argb) {
        colorBad = argb | 0xFF000000;
    }

    public int getColorAwful() {
        return colorAwful;
    }

    public void setColorAwful(int argb) {
        colorAwful = argb | 0xFF000000;
    }

    public int getColorText() {
        return colorText;
    }

    public void setColorText(int argb) {
        colorText = argb | 0xFF000000;
    }
}
