package com.killer560.hub.itemalerts;

import com.google.gson.JsonObject;
import com.killer560.hub.util.BoolFields;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.file.Path;

/** Item Alerts settings ({@link ItemAlertsFeature}): per alert a switch (off), a sound (on), Persistent or Timed and the seconds a Timed alert stays. */
public final class ItemAlertsConfig {

    public static final int MIN_SECONDS = 1;
    public static final int MAX_SECONDS = 60;

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-itemalerts.json");

    private static final BoolFields<ItemAlertsConfig> BOOLS = new BoolFields<ItemAlertsConfig>()
            .add("bottleEnabled", false, c -> c.bottleEnabled, (c, v) -> c.bottleEnabled = v)
            .add("bottleSound", true, c -> c.bottleSound, (c, v) -> c.bottleSound = v)
            .add("bottlePersistent", true, c -> c.bottlePersistent, (c, v) -> c.bottlePersistent = v)
            .add("runebookEnabled", false, c -> c.runebookEnabled, (c, v) -> c.runebookEnabled = v)
            .add("runebookSound", true, c -> c.runebookSound, (c, v) -> c.runebookSound = v)
            .add("runebookPersistent", false, c -> c.runebookPersistent, (c, v) -> c.runebookPersistent = v);

    private static ItemAlertsConfig instance;

    private boolean bottleEnabled = false;
    private boolean bottleSound = true;
    private boolean bottlePersistent = true;
    private int bottleSeconds = 10;
    private boolean runebookEnabled = false;
    private boolean runebookSound = true;
    private boolean runebookPersistent = false;
    private int runebookSeconds = 10;

    private ItemAlertsConfig() {
    }

    public static ItemAlertsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        ItemAlertsConfig cfg = new ItemAlertsConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            BOOLS.load(cfg, obj);
            cfg.bottleSeconds = clamp(ConfigJson.getInt(obj, "bottleSeconds", cfg.bottleSeconds));
            cfg.runebookSeconds = clamp(ConfigJson.getInt(obj, "runebookSeconds", cfg.runebookSeconds));
        }
        instance = cfg;
        ItemAlertsFeature.refresh();
    }

    public void save() {
        JsonObject obj = new JsonObject();
        BOOLS.save(this, obj);
        obj.addProperty("bottleSeconds", bottleSeconds);
        obj.addProperty("runebookSeconds", runebookSeconds);
        ConfigJson.write(CONFIG_PATH, obj);
        ItemAlertsFeature.refresh();
    }

    private static int clamp(int v) {
        return Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, v));
    }

    public boolean isBottleEnabled() { return bottleEnabled && SkyblockGate.allows(); }
    public boolean isBottleEnabledRaw() { return bottleEnabled; }
    public void setBottleEnabled(boolean v) { bottleEnabled = v; }
    public boolean isBottleSound() { return bottleSound; }
    public void setBottleSound(boolean v) { bottleSound = v; }
    public boolean isBottlePersistent() { return bottlePersistent; }
    public void setBottlePersistent(boolean v) { bottlePersistent = v; }
    public int getBottleSeconds() { return bottleSeconds; }
    public void setBottleSeconds(int v) { bottleSeconds = clamp(v); }

    public boolean isRunebookEnabled() { return runebookEnabled && SkyblockGate.allows(); }
    public boolean isRunebookEnabledRaw() { return runebookEnabled; }
    public void setRunebookEnabled(boolean v) { runebookEnabled = v; }
    public boolean isRunebookSound() { return runebookSound; }
    public void setRunebookSound(boolean v) { runebookSound = v; }
    public boolean isRunebookPersistent() { return runebookPersistent; }
    public void setRunebookPersistent(boolean v) { runebookPersistent = v; }
    public int getRunebookSeconds() { return runebookSeconds; }
    public void setRunebookSeconds(int v) { runebookSeconds = clamp(v); }
}
