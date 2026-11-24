package com.killer560.hub.kitcheck;

import com.google.gson.JsonObject;
import com.killer560.hub.util.BoolFields;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/** Kit Check settings: the switch, the lowest floor it checks, and the least you want of each item (0 = not checked). */
public final class KitCheckConfig {

    private static final Path PATH = ModPaths.config("killer560smod-kitcheck.json");
    /** The steps of every "at least" row. */
    public static final int[] STEPS = {0, 1, 2, 4, 8, 16, 32, 64, 128};
    private static final BoolFields<KitCheckConfig> BOOLS = new BoolFields<KitCheckConfig>()
            .add("enabled", false, c -> c.enabled, (c, v) -> c.enabled = v)
            .add("godPotion", true, c -> c.godPotion, (c, v) -> c.godPotion = v)
            .add("cookie", false, c -> c.cookie, (c, v) -> c.cookie = v);

    private static KitCheckConfig instance;

    private boolean enabled, godPotion = true, cookie;
    private int minFloor = 7, leaps = 4, boom = 0, decoys = 0, arrows = 0;

    public static KitCheckConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        KitCheckConfig c = new KitCheckConfig();
        JsonObject o = ConfigJson.read(PATH);
        if (o != null) {
            BOOLS.load(c, o);
            c.setMinFloor(ConfigJson.getInt(o, "minFloor", 7));
            c.leaps = Math.max(0, ConfigJson.getInt(o, "leaps", 4));
            c.boom = Math.max(0, ConfigJson.getInt(o, "boom", 0));
            c.decoys = Math.max(0, ConfigJson.getInt(o, "decoys", 0));
            c.arrows = Math.max(0, ConfigJson.getInt(o, "arrows", 0));
        }
        instance = c;
    }

    public void save() {
        JsonObject o = new JsonObject();
        BOOLS.save(this, o);
        o.addProperty("minFloor", minFloor);
        o.addProperty("leaps", leaps);
        o.addProperty("boom", boom);
        o.addProperty("decoys", decoys);
        o.addProperty("arrows", arrows);
        ConfigJson.write(PATH, o);
    }

    /** The next value of a count row after {@code cur}, one step in direction {@code dir}, wrapping. */
    public static int step(int cur, int dir) {
        int i = 0;
        for (int k = 0; k < STEPS.length; k++) {
            if (STEPS[k] <= cur) {
                i = k;
            }
        }
        return STEPS[Math.floorMod(i + dir, STEPS.length)];
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public boolean isGodPotion() { return godPotion; }
    public void setGodPotion(boolean v) { godPotion = v; }
    public boolean isCookie() { return cookie; }
    public void setCookie(boolean v) { cookie = v; }
    /** Floors below this number are not checked (0 = Entrance, 1 to 7). */
    public int getMinFloor() { return minFloor; }
    public void setMinFloor(int v) { minFloor = Math.floorMod(v, 8); }
    public int getLeaps() { return leaps; }
    public void setLeaps(int v) { leaps = Math.max(0, v); }
    public int getBoom() { return boom; }
    public void setBoom(int v) { boom = Math.max(0, v); }
    public int getDecoys() { return decoys; }
    public void setDecoys(int v) { decoys = Math.max(0, v); }
    public int getArrows() { return arrows; }
    public void setArrows(int v) { arrows = Math.max(0, v); }
}
