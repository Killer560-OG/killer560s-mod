package com.killer560.hub.chestvalue;

import com.google.gson.JsonObject;
import com.killer560.hub.util.BoolFields;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/** Persisted "Chest Value" settings - see {@link ChestValue}. Ships disabled. Visual only. */
public final class ChestValueConfig {

    public enum Sort {
        VALUE("Value"),
        NAME("Name");

        public final String label;

        Sort(String label) {
            this.label = label;
        }
    }

    public static final int MIN_LINES = 3;
    public static final int MAX_LINES = 30;

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-chestvalue.json");

    private static final BoolFields<ChestValueConfig> SWITCHES = new BoolFields<ChestValueConfig>()
            .add("enabled", false, c -> c.enabled, (c, v) -> c.enabled = v)
            .add("chests", true, c -> c.chests, (c, v) -> c.chests = v)
            .add("enderChest", true, c -> c.enderChest, (c, v) -> c.enderChest = v)
            .add("backpacks", true, c -> c.backpacks, (c, v) -> c.backpacks = v)
            .add("minions", true, c -> c.minions, (c, v) -> c.minions = v)
            .add("other", false, c -> c.other, (c, v) -> c.other = v);

    private static ChestValueConfig instance;
    private static int version;

    private boolean enabled = false;
    private boolean chests = true;
    private boolean enderChest = true;
    private boolean backpacks = true;
    private boolean minions = true;
    private boolean other = false;
    private Sort sort = Sort.VALUE;
    private int maxLines = 10;

    private ChestValueConfig() {
    }

    public static ChestValueConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        ChestValueConfig cfg = new ChestValueConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            SWITCHES.load(cfg, obj);
            cfg.sort = ConfigJson.getEnum(obj, "sort", Sort.class, Sort.VALUE);
            cfg.maxLines = clampLines(ConfigJson.getInt(obj, "maxLines", 10));
        }
        instance = cfg;
        version++;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        SWITCHES.save(this, obj);
        obj.addProperty("sort", sort.name());
        obj.addProperty("maxLines", maxLines);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    private static int clampLines(int n) {
        return Math.max(MIN_LINES, Math.min(MAX_LINES, n));
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

    public boolean isChests() {
        return chests;
    }

    public void setChests(boolean v) {
        chests = v;
        version++;
    }

    public boolean isEnderChest() {
        return enderChest;
    }

    public void setEnderChest(boolean v) {
        enderChest = v;
        version++;
    }

    public boolean isBackpacks() {
        return backpacks;
    }

    public void setBackpacks(boolean v) {
        backpacks = v;
        version++;
    }

    public boolean isMinions() {
        return minions;
    }

    public void setMinions(boolean v) {
        minions = v;
        version++;
    }

    public boolean isOther() {
        return other;
    }

    public void setOther(boolean v) {
        other = v;
        version++;
    }

    public Sort getSort() {
        return sort;
    }

    public void setSort(Sort s) {
        sort = s == null ? Sort.VALUE : s;
        version++;
    }

    public int getMaxLines() {
        return maxLines;
    }

    public void setMaxLines(int n) {
        maxLines = clampLines(n);
        version++;
    }
}
