package com.killer560.hub.carnival;

import com.killer560.hub.islands.IslandConfig;

/**
 * Typed getters over one {@link IslandConfig} file, {@code killer560smod-carnival-<name>.json} (folder skyblock/carnival).
 * {@code "enabled"} is the feature's switch and defaults OFF; profile apply re-reads it through
 * {@link IslandConfig#loadAll()}, and {@link #reload()} puts every setter's clamp back over a hand-edited file.
 */
abstract class CarnivalSettings {

    private final IslandConfig cfg;
    private boolean clamped;

    CarnivalSettings(String name) {
        cfg = IslandConfig.of("carnival", name);
    }

    protected final void declare(String key, Object def) {
        if (def instanceof Boolean b) {
            cfg.flag(key, b);
        } else {
            cfg.num(key, ((Number) def).intValue(), Integer.MIN_VALUE, Integer.MAX_VALUE);
        }
    }

    protected final void ensureLoaded() {
        if (!clamped) {
            clamped = true;
            clampAll();
        }
    }

    public final void reload() {
        cfg.load();
        clamped = true;
        clampAll();
    }

    /** Setters re-applied after a load, so a value out of range in the file is pulled back in. */
    protected void clampAll() {
    }

    public final void save() {
        cfg.save();
    }

    protected final boolean bool(String key) {
        return cfg.on(key);
    }

    protected final int integer(String key) {
        return cfg.num(key);
    }

    protected final void put(String key, Object value) {
        cfg.set(key, value);
    }

    protected static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public boolean isEnabledRaw() {
        return cfg.isEnabledRaw();
    }

    public boolean isEnabled() {
        return cfg.isEnabled();
    }

    public void setEnabled(boolean v) {
        cfg.setEnabled(v);
    }
}
