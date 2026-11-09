package com.killer560.hub.puzzlehud;

import com.google.gson.JsonObject;
import com.killer560.hub.util.BoolFields;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.file.Path;

/** Puzzle HUD settings, {@code killer560smod-puzzlehud.json}. The switch ships OFF; saved on every GUI change, reloaded by ProfileManager. */
public final class PuzzleHudConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-puzzlehud.json");
    private static PuzzleHudConfig instance;

    private boolean enabled = false;
    private boolean showTimes = true;
    private boolean showWho = true;

    private static final BoolFields<PuzzleHudConfig> BOOLS = new BoolFields<PuzzleHudConfig>()
            .add("enabled", false, c -> c.enabled, (c, v) -> c.enabled = v)
            .add("showTimes", true, c -> c.showTimes, (c, v) -> c.showTimes = v)
            .add("showWho", true, c -> c.showWho, (c, v) -> c.showWho = v);

    private PuzzleHudConfig() {
    }

    public static PuzzleHudConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        PuzzleHudConfig cfg = new PuzzleHudConfig();
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

    public boolean isShowTimes() { return showTimes; }

    public void setShowTimes(boolean v) { showTimes = v; }

    public boolean isShowWho() { return showWho; }

    public void setShowWho(boolean v) { showWho = v; }
}
