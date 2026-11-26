package com.killer560.hub.craftcost;

import com.google.gson.JsonObject;
import com.killer560.hub.util.BoolFields;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/** Persisted "Craft Cost" settings - see {@link CraftCost}: only the switch (off by default). Visual only, both builds. */
public final class CraftCostConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-craftcost.json");
    private static final BoolFields<CraftCostConfig> SWITCHES = new BoolFields<CraftCostConfig>()
            .add("enabled", false, c -> c.enabled, (c, v) -> c.enabled = v);

    private static CraftCostConfig instance;
    private static int version;
    private boolean enabled = false;

    private CraftCostConfig() {
    }

    public static CraftCostConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        CraftCostConfig cfg = new CraftCostConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            SWITCHES.load(cfg, obj);
        }
        instance = cfg;
        version++;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        SWITCHES.save(this, obj);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    /** Moves on every change, so the panel rebuilds its lines only when a setting changed. */
    static int version() {
        return version;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
        version++;
    }
}
