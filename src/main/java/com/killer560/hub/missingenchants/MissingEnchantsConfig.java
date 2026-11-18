package com.killer560.hub.missingenchants;

import com.google.gson.JsonObject;
import com.killer560.hub.util.BoolFields;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.file.Path;

/** Missing Enchants settings, {@code killer560smod-missingenchants.json}: the switch (ships OFF), Show Enchants Below Max Level (OFF) and the Shift hint (ON). */
public final class MissingEnchantsConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-missingenchants.json");
    private static MissingEnchantsConfig instance;

    private boolean enabled = false;
    private boolean belowMax = false;
    private boolean hint = true;

    private static final BoolFields<MissingEnchantsConfig> BOOLS = new BoolFields<MissingEnchantsConfig>()
            .add("enabled", false, c -> c.enabled, (c, v) -> c.enabled = v)
            .add("belowMax", false, c -> c.belowMax, (c, v) -> c.belowMax = v)
            .add("hint", true, c -> c.hint, (c, v) -> c.hint = v);

    private MissingEnchantsConfig() {
    }

    public static MissingEnchantsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        MissingEnchantsConfig cfg = new MissingEnchantsConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            BOOLS.load(cfg, obj);
        }
        instance = cfg;
        MissingEnchantsFeature.clearCache();
    }

    public void save() {
        JsonObject obj = new JsonObject();
        BOOLS.save(this, obj);
        ConfigJson.write(CONFIG_PATH, obj);
        MissingEnchantsFeature.clearCache();
    }

    public boolean isEnabled() { return enabled && SkyblockGate.allows(); }

    public boolean getEnabledRaw() { return enabled; }

    public void setEnabled(boolean v) { enabled = v; }

    public boolean isBelowMax() { return belowMax; }

    public void setBelowMax(boolean v) { belowMax = v; }

    public boolean isHint() { return hint; }

    public void setHint(boolean v) { hint = v; }
}
