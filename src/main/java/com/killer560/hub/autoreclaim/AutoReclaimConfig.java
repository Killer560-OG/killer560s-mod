package com.killer560.hub.autoreclaim;

import com.google.gson.JsonObject;
import com.killer560.hub.util.BoolFields;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/** Auto Reclaim settings: the one switch (default OFF). See {@link AutoReclaimFeature}. */
public final class AutoReclaimConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-autoreclaim.json");
    private static final BoolFields<AutoReclaimConfig> FIELDS = new BoolFields<AutoReclaimConfig>()
            .add("enabled", false, c -> c.enabled, (c, v) -> c.enabled = v)
            .add("pullAndReclaim", false, c -> c.pullAndReclaim, (c, v) -> c.pullAndReclaim = v);

    private static AutoReclaimConfig instance;

    private boolean enabled;
    /** Cheat build only: the command also pulls the items out of storage and reclaims them. */
    private boolean pullAndReclaim;

    private AutoReclaimConfig() {
    }

    public static AutoReclaimConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        AutoReclaimConfig cfg = new AutoReclaimConfig();
        FIELDS.load(cfg, ConfigJson.read(CONFIG_PATH));
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        FIELDS.save(this, obj);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isPullAndReclaim() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && pullAndReclaim;
    }

    public void setPullAndReclaim(boolean v) {
        pullAndReclaim = v;
    }
}
