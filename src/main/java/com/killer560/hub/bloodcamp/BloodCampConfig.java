package com.killer560.hub.bloodcamp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Blood Camp settings - see {@link BloodCampFeature}'s class doc for the real Noamm-ported
 *  mechanic this is built on. Ships disabled by default, same as every other new feature in this mod.
 *  <p>
 *  Legit getters are {@code raw && SkyblockGate.allows()}; cheat getters additionally require
 *  {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED}. Tab labels read the {@code ...Raw()}
 *  getters so a toggle never flips from a gated value (2026-09-21: {@code BloodCampTab} read the gated
 *  getters, so outside Skyblock every row read OFF and clicking it could only ever write true).
 *  <p>
 *  Scope note (2026-09-14): real per-color customization (box/line/timer colors, decimal places) that
 *  Noamm's own real reference exposes is deliberately NOT included here yet, to keep this first version
 *  manageable - fixed real defaults (green=ready, red=already broken, matching Noamm's own real choices)
 *  are used instead. Can be added later if killer560 wants it. */
public final class BloodCampConfig {

    /** Ticks before the Watcher's predicted move that the kill popup flips to "KILL" (SkyHanni's own lead
     *  is 150 ms = 3 ticks). */
    public static final int DEFAULT_KILL_POPUP_LEAD_TICKS = 3;
    public static final int MAX_KILL_POPUP_LEAD_TICKS = 20;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-bloodcamp.json");

    private static BloodCampConfig instance;

    private boolean enabled = false;
    private boolean showOverlay = true;
    private boolean triggerBotEnabled = false;
    private boolean auraEnabled = false;
    private boolean autoDetectLag = true;
    private int manualTickOffset = 0;
    private boolean killPopup = false;
    private int killPopupLeadTicks = DEFAULT_KILL_POPUP_LEAD_TICKS;

    private BloodCampConfig() {
    }

    public static BloodCampConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new BloodCampConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            BloodCampConfig cfg = new BloodCampConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showOverlay = ConfigJson.getBool(obj, "showOverlay", true);
            cfg.triggerBotEnabled = ConfigJson.getBool(obj, "triggerBotEnabled", false);
            cfg.auraEnabled = ConfigJson.getBool(obj, "auraEnabled", false);
            cfg.autoDetectLag = ConfigJson.getBool(obj, "autoDetectLag", true);
            cfg.setManualTickOffset(ConfigJson.getInt(obj, "manualTickOffset", 0));
            cfg.killPopup = ConfigJson.getBool(obj, "killPopup", false);
            cfg.setKillPopupLeadTicks(ConfigJson.getInt(obj, "killPopupLeadTicks", DEFAULT_KILL_POPUP_LEAD_TICKS));
            instance = cfg;
        } catch (Exception e) {
            instance = new BloodCampConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showOverlay", showOverlay);
            obj.addProperty("triggerBotEnabled", triggerBotEnabled);
            obj.addProperty("auraEnabled", auraEnabled);
            obj.addProperty("autoDetectLag", autoDetectLag);
            obj.addProperty("manualTickOffset", manualTickOffset);
            obj.addProperty("killPopup", killPopup);
            obj.addProperty("killPopupLeadTicks", killPopupLeadTicks);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    /** The saved value with no gate applied - for the settings GUI. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isShowOverlay() {
        return showOverlay;
    }

    public void setShowOverlay(boolean showOverlay) {
        this.showOverlay = showOverlay;
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - a real click-assist
     *  macro, same pattern as Simon Says' Trigger Bot. */
    public boolean isTriggerBotEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && triggerBotEnabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean getTriggerBotRaw() {
        return triggerBotEnabled;
    }

    public void setTriggerBotEnabled(boolean triggerBotEnabled) {
        this.triggerBotEnabled = triggerBotEnabled;
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - real simulated camera
     *  rotation, a real macro. */
    public boolean isAuraEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && auraEnabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean getAuraRaw() {
        return auraEnabled;
    }

    public void setAuraEnabled(boolean auraEnabled) {
        this.auraEnabled = auraEnabled;
    }

    /**
     * "Kill Popup" (killer560, 2026-09-21: "make it so it gives a hud popup saying kill as an option for time
     * to kill to skip dialogue") - the movable HUD element in {@link BloodCampMoveTimer}. Legit: it only reads
     * chat and draws text, so it ships in both jars.
     */
    public boolean isKillPopup() {
        return killPopup && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean getKillPopupRaw() {
        return killPopup;
    }

    public void setKillPopup(boolean killPopup) {
        this.killPopup = killPopup;
    }

    /** Ticks before the predicted Watcher move that the popup flips from a countdown to "KILL". */
    public int getKillPopupLeadTicks() {
        return killPopupLeadTicks;
    }

    public void setKillPopupLeadTicks(int ticks) {
        this.killPopupLeadTicks = Math.max(0, Math.min(MAX_KILL_POPUP_LEAD_TICKS, ticks));
    }

    public boolean isAutoDetectLag() {
        return autoDetectLag;
    }

    public boolean getShowOverlayRaw() {
        return showOverlay;
    }

    public void setAutoDetectLag(boolean autoDetectLag) {
        this.autoDetectLag = autoDetectLag;
    }

    public int getManualTickOffset() {
        return manualTickOffset;
    }

    public void setManualTickOffset(int manualTickOffset) {
        this.manualTickOffset = Math.max(-20, Math.min(20, manualTickOffset));
    }
}
