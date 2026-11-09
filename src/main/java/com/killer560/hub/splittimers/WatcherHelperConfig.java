package com.killer560.hub.splittimers;

import com.google.gson.JsonObject;
import com.killer560.hub.util.BoolFields;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.file.Path;

/** Watcher Helper settings, {@code killer560smod-watcherhelper.json}. The switch ships OFF; saved on every GUI change, reloaded by ProfileManager. */
public final class WatcherHelperConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-watcherhelper.json");
    private static WatcherHelperConfig instance;

    private boolean enabled = false;
    private boolean speedTitle = true;
    private boolean breakdownChat = true;

    private static final BoolFields<WatcherHelperConfig> BOOLS = new BoolFields<WatcherHelperConfig>()
            .add("enabled", false, c -> c.enabled, (c, v) -> c.enabled = v)
            .add("speedTitle", true, c -> c.speedTitle, (c, v) -> c.speedTitle = v)
            .add("breakdownChat", true, c -> c.breakdownChat, (c, v) -> c.breakdownChat = v);

    private WatcherHelperConfig() {
    }

    public static WatcherHelperConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        WatcherHelperConfig cfg = new WatcherHelperConfig();
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

    public boolean isSpeedTitle() { return speedTitle; }

    public void setSpeedTitle(boolean v) { speedTitle = v; }

    public boolean isBreakdownChat() { return breakdownChat; }

    public void setBreakdownChat(boolean v) { breakdownChat = v; }
}
