package com.killer560.hub.autoclosechest;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Auto Close Chest settings - ships disabled by default; Auto is cheat build only. */
public final class AutoCloseChestConfig {

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-autoclosechest.json");

    private static AutoCloseChestConfig instance;

    private boolean enabled = false;
    private boolean anyKey = true;
    private boolean auto = false;

    private AutoCloseChestConfig() {
    }

    public static AutoCloseChestConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new AutoCloseChestConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            AutoCloseChestConfig cfg = new AutoCloseChestConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            // Files from before 2026-11-09 carry "mode": AUTO or CLICK; both keep Close On Any Key, AUTO is the cheat Auto.
            boolean oldAuto = obj.has("mode") && "AUTO".equals(obj.get("mode").getAsString());
            cfg.anyKey = ConfigJson.getBool(obj, "anyKey", true);
            cfg.auto = ConfigJson.getBool(obj, "auto", oldAuto && com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED);
            instance = cfg;
        } catch (Exception e) {
            instance = new AutoCloseChestConfig();
        }
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("anyKey", anyKey);
        obj.addProperty("auto", auto);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    /** The feature is on and Skyblock allows it. */
    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    /** Cheat Auto: closes the chest before it opens. Folds to false in a legit jar (killer560, 2026-10-07). */
    public boolean isAuto() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && auto;
    }

    public boolean isAutoRaw() {
        return auto;
    }

    public void setAuto(boolean auto) {
        this.auto = auto;
    }

    public boolean isAnyKey() {
        return anyKey;
    }

    public void setAnyKey(boolean anyKey) {
        this.anyKey = anyKey;
    }

    /** The saved value, ungated - for the tab's label, so the button never shows OFF just because the
     *  Skyblock gate is closed (the trap {@code BloodCampConfig}'s class doc describes). */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
