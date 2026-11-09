package com.killer560.hub.reparty;

import com.google.gson.JsonObject;
import com.killer560.hub.util.BoolFields;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.file.Path;

/** Reparty settings, {@code killer560smod-reparty.json}. The switch and Auto Reparty ship OFF; saved on every GUI change. */
public final class RepartyConfig {

    /** The delays Auto Reparty cycles through, in seconds after the run's end screen. */
    public static final int[] DELAYS = {3, 5, 10, 15, 30};

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-reparty.json");
    private static RepartyConfig instance;

    private boolean enabled = false;
    private boolean auto = false;
    private int delaySeconds = 5;

    private static final BoolFields<RepartyConfig> BOOLS = new BoolFields<RepartyConfig>()
            .add("enabled", false, c -> c.enabled, (c, v) -> c.enabled = v)
            .add("auto", false, c -> c.auto, (c, v) -> c.auto = v);

    private RepartyConfig() {
    }

    public static RepartyConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        RepartyConfig cfg = new RepartyConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            BOOLS.load(cfg, obj);
            cfg.setDelaySeconds(ConfigJson.getInt(obj, "delaySeconds", 5));
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        BOOLS.save(this, obj);
        obj.addProperty("delaySeconds", delaySeconds);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() { return enabled && SkyblockGate.allows(); }

    public boolean getEnabledRaw() { return enabled; }

    public void setEnabled(boolean v) { enabled = v; }

    public boolean isAuto() { return auto; }

    public void setAuto(boolean v) { auto = v; }

    public int getDelaySeconds() { return delaySeconds; }

    /** Any value is snapped to the nearest allowed delay. */
    public void setDelaySeconds(int v) {
        int best = DELAYS[0];
        for (int d : DELAYS) {
            if (Math.abs(d - v) < Math.abs(best - v)) {
                best = d;
            }
        }
        delaySeconds = best;
    }
}
