package com.killer560.hub.p3status;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * P3 Status settings (2026-10-25, see {@link P3StatusFeature}). The feature switch is OFF by default; the parts under
 * it default ON, so switching it on gives the whole thing. Every gated getter ANDs the switch and Skyblock Only.
 */
public final class P3StatusConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-p3status.json");

    private static volatile P3StatusConfig instance;

    private boolean enabled = false;
    private boolean showHud = true;
    private boolean showWhoIsAt = true;
    private boolean rewriteTitles = true;
    private boolean deviceDoneTitle = true;
    private boolean sectionCompleteTitle = true;
    private boolean hideLaserSubtitle = true;
    private boolean titleSound = true;

    private P3StatusConfig() {
    }

    public static P3StatusConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static synchronized void load() {
        P3StatusConfig cfg = new P3StatusConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
                cfg.showHud = ConfigJson.getBool(obj, "showHud", true);
                cfg.showWhoIsAt = ConfigJson.getBool(obj, "showWhoIsAt", true);
                cfg.rewriteTitles = ConfigJson.getBool(obj, "rewriteTitles", true);
                cfg.deviceDoneTitle = ConfigJson.getBool(obj, "deviceDoneTitle", true);
                cfg.sectionCompleteTitle = ConfigJson.getBool(obj, "sectionCompleteTitle", true);
                cfg.hideLaserSubtitle = ConfigJson.getBool(obj, "hideLaserSubtitle", true);
                cfg.titleSound = ConfigJson.getBool(obj, "titleSound", true);
            } catch (Exception e) {
                cfg = new P3StatusConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showHud", showHud);
            obj.addProperty("showWhoIsAt", showWhoIsAt);
            obj.addProperty("rewriteTitles", rewriteTitles);
            obj.addProperty("deviceDoneTitle", deviceDoneTitle);
            obj.addProperty("sectionCompleteTitle", sectionCompleteTitle);
            obj.addProperty("hideLaserSubtitle", hideLaserSubtitle);
            obj.addProperty("titleSound", titleSound);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private boolean on(boolean part) {
        return enabled && part && SkyblockGate.allows();
    }

    // ---- gated (what the feature reads) ----

    public boolean isEnabled() {
        return on(true);
    }

    public boolean isShowHud() {
        return on(showHud);
    }

    public boolean isShowWhoIsAt() {
        return on(showWhoIsAt);
    }

    public boolean isRewriteTitles() {
        return on(rewriteTitles);
    }

    public boolean isDeviceDoneTitle() {
        return on(deviceDoneTitle);
    }

    public boolean isSectionCompleteTitle() {
        return on(sectionCompleteTitle);
    }

    public boolean isHideLaserSubtitle() {
        return on(hideLaserSubtitle);
    }

    public boolean isTitleSound() {
        return on(titleSound);
    }

    // ---- raw (what the menu shows) ----

    public boolean getEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public boolean getShowHudRaw() {
        return showHud;
    }

    public void setShowHud(boolean v) {
        showHud = v;
    }

    public boolean getShowWhoIsAtRaw() {
        return showWhoIsAt;
    }

    public void setShowWhoIsAt(boolean v) {
        showWhoIsAt = v;
    }

    public boolean getRewriteTitlesRaw() {
        return rewriteTitles;
    }

    public void setRewriteTitles(boolean v) {
        rewriteTitles = v;
    }

    public boolean getDeviceDoneTitleRaw() {
        return deviceDoneTitle;
    }

    public void setDeviceDoneTitle(boolean v) {
        deviceDoneTitle = v;
    }

    public boolean getSectionCompleteTitleRaw() {
        return sectionCompleteTitle;
    }

    public void setSectionCompleteTitle(boolean v) {
        sectionCompleteTitle = v;
    }

    public boolean getHideLaserSubtitleRaw() {
        return hideLaserSubtitle;
    }

    public void setHideLaserSubtitle(boolean v) {
        hideLaserSubtitle = v;
    }

    public boolean getTitleSoundRaw() {
        return titleSound;
    }

    public void setTitleSound(boolean v) {
        titleSound = v;
    }
}
