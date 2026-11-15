package com.killer560.hub.commissioncolors;

import com.google.gson.JsonObject;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/** Commission Block Colors settings ({@code killer560smod-mining-commcolors.json}). Legit, ships OFF. */
public final class CommissionColorsConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-mining-commcolors.json");

    /** Bumped by every change, so the tick sees a setting change without comparing fields. */
    private static volatile int generation = 0;
    private static CommissionColorsConfig instance;

    private boolean enabled = false;
    private boolean highlight = true;
    private boolean dimOthers = true;
    /** 0 = the menu theme's accent. */
    private int color = 0;

    private CommissionColorsConfig() {
    }

    public static CommissionColorsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        CommissionColorsConfig cfg = new CommissionColorsConfig();
        JsonObject o = ConfigJson.read(CONFIG_PATH);
        if (o != null) {
            cfg.enabled = ConfigJson.getBool(o, "enabled", cfg.enabled);
            cfg.highlight = ConfigJson.getBool(o, "highlight", cfg.highlight);
            cfg.dimOthers = ConfigJson.getBool(o, "dimOthers", cfg.dimOthers);
            cfg.color = ConfigJson.getInt(o, "color", cfg.color);
        }
        instance = cfg;
        generation++;
    }

    public void save() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("highlight", highlight);
        o.addProperty("dimOthers", dimOthers);
        o.addProperty("color", color);
        ConfigJson.write(CONFIG_PATH, o);
    }

    static int generation() { return generation; }

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean v) { enabled = v; generation++; }

    public boolean isHighlight() { return highlight; }

    public void setHighlight(boolean v) { highlight = v; generation++; }

    public boolean isDimOthers() { return dimOthers; }

    public void setDimOthers(boolean v) { dimOthers = v; generation++; }

    /** The picked color, else the menu theme's accent. */
    public int getColor() { return color != 0 ? color : defaultColor(); }

    public void setColor(int argb) { color = argb; generation++; }

    public static int defaultColor() { return ThemeAccent.accent(0xFFCC6600); }
}
