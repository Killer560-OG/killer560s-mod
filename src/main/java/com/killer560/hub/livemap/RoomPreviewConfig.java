package com.killer560.hub.livemap;

import com.google.gson.JsonObject;
import com.killer560.hub.util.BoolFields;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.file.Path;

/** Room Preview settings, {@code killer560smod-roompreview.json}: just the switch, which ships OFF. */
public final class RoomPreviewConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-roompreview.json");
    private static RoomPreviewConfig instance;

    private boolean enabled = false;

    private static final BoolFields<RoomPreviewConfig> BOOLS = new BoolFields<RoomPreviewConfig>()
            .add("enabled", false, c -> c.enabled, (c, v) -> c.enabled = v);

    private RoomPreviewConfig() {
    }

    public static RoomPreviewConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        RoomPreviewConfig cfg = new RoomPreviewConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            BOOLS.load(cfg, obj);
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        BOOLS.save(this, obj);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() { return enabled && SkyblockGate.allows(); }

    public boolean getEnabledRaw() { return enabled; }

    public void setEnabled(boolean v) { enabled = v; }
}
