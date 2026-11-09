package com.killer560.hub.itemhighlight;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.file.Path;

/** Dungeon Item Highlighter settings ({@code killer560smod-itemhighlight.json}); the feature ships OFF. */
public final class DungeonItemHighlightConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-itemhighlight.json");

    /** The most a dungeon item may be worth (lowest BIN) and still be marked as safe to salvage or sell. */
    public enum Limit {
        K50(50_000), K100(100_000), K250(250_000), M1(1_000_000), M5(5_000_000);

        public final long coins;

        Limit(long coins) {
            this.coins = coins;
        }

        public String label() {
            return coins >= 1_000_000 ? coins / 1_000_000 + "M" : coins / 1000 + "k";
        }
    }

    private static DungeonItemHighlightConfig instance;

    private boolean enabled = false;
    private boolean salvage = true;
    private boolean sell = true;
    private Limit limit = Limit.K100;

    private DungeonItemHighlightConfig() {
    }

    public static DungeonItemHighlightConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        DungeonItemHighlightConfig cfg = new DungeonItemHighlightConfig();
        JsonObject o = ConfigJson.read(CONFIG_PATH);
        if (o != null) {
            cfg.enabled = ConfigJson.getBool(o, "enabled", false);
            cfg.salvage = ConfigJson.getBool(o, "salvage", true);
            cfg.sell = ConfigJson.getBool(o, "sell", true);
            String name = ConfigJson.getString(o, "limit", cfg.limit.name());
            for (Limit l : Limit.values()) {
                if (l.name().equals(name)) {
                    cfg.limit = l;
                }
            }
        }
        instance = cfg;
    }

    public void save() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("salvage", salvage);
        o.addProperty("sell", sell);
        o.addProperty("limit", limit.name());
        ConfigJson.write(CONFIG_PATH, o);
    }

    public boolean isEnabled() { return enabled && SkyblockGate.allows(); }
    public boolean isEnabledRaw() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public boolean isSalvage() { return salvage; }
    public void setSalvage(boolean v) { salvage = v; }
    public boolean isSell() { return sell; }
    public void setSell(boolean v) { sell = v; }
    public Limit getLimit() { return limit; }
    public void setLimit(Limit v) { limit = v; }
}
