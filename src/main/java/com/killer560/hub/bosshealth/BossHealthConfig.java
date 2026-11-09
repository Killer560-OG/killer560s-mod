package com.killer560.hub.bosshealth;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.file.Path;

/**
 * Persisted Boss Health settings - {@code killer560smod-bosshealth.json}. The feature ships OFF; its options default to
 * what the source mods show (current/max on, percent off, minions on). Reloaded by {@code ProfileManager.reloadAllConfigs()}.
 */
public final class BossHealthConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-bosshealth.json");

    private static BossHealthConfig instance;

    private boolean enabled = false;
    private boolean showMax = true;
    private boolean showPercent = false;
    private boolean showMinions = true;

    private BossHealthConfig() {
    }

    public static BossHealthConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        BossHealthConfig cfg = new BossHealthConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showMax = ConfigJson.getBool(obj, "showMax", true);
            cfg.showPercent = ConfigJson.getBool(obj, "showPercent", false);
            cfg.showMinions = ConfigJson.getBool(obj, "showMinions", true);
        }
        instance = cfg;
        com.killer560.hub.util.FeatureBits.changed();
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("showMax", showMax);
        obj.addProperty("showPercent", showPercent);
        obj.addProperty("showMinions", showMinions);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    /** The switch AND the Skyblock Only gate. */
    public boolean isEnabled() { return enabled && SkyblockGate.allows(); }
    public boolean isEnabledRaw() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; com.killer560.hub.util.FeatureBits.changed(); }

    public boolean isShowMax() { return showMax; }
    public void setShowMax(boolean v) { showMax = v; }

    public boolean isShowPercent() { return showPercent; }
    public void setShowPercent(boolean v) { showPercent = v; }

    public boolean isShowMinions() { return showMinions; }
    public void setShowMinions(boolean v) { showMinions = v; }
}
