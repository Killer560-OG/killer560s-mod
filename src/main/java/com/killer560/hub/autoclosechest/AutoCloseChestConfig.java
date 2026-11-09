package com.killer560.hub.autoclosechest;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Auto Close Chest setting - ships disabled by default, same as every other new feature in
 *  this mod. Cheat build only since 2026-10-07: see {@link #isEnabled()}. */
public final class AutoCloseChestConfig {

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-autoclosechest.json");

    private static AutoCloseChestConfig instance;

    private boolean enabled = false;
    private Mode mode = Mode.AUTO;

    /** Auto closes the chest before it opens (cheat build only); Click leaves it open until any key or mouse press. */
    public enum Mode { AUTO, CLICK }

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
            cfg.mode = ConfigJson.getEnum(obj, "mode", Mode.class, Mode.AUTO);
            instance = cfg;
        } catch (Exception e) {
            instance = new AutoCloseChestConfig();
        }
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("mode", mode.name());
        ConfigJson.write(CONFIG_PATH, obj);
    }

    /** The feature is on and Skyblock allows it. Auto mode is cheat build only (killer560, 2026-10-07: "auto close chest
     *  should be in a cheat only version"): {@link #isAuto()} folds to false in a legit jar, where only Click exists. */
    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isAuto() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && mode == Mode.AUTO;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
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
