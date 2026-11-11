package com.killer560.hub.carnival;



/** Zombie Shootout Helper settings (see {@link ZombieShootout}). Display only; off by default. */
public final class ZombieShootoutConfig extends CarnivalSettings {

    public static final int DEFAULT_BEST_COLOR = 0xFFFF55FF;
    private static final ZombieShootoutConfig INSTANCE = new ZombieShootoutConfig();

    private ZombieShootoutConfig() {
        super("zombieshootout");
        declare("enabled", false);
        declare("outlines", true);
        declare("highestOnly", false);
        declare("timers", true);
        declare("best", true);
        declare("lamps", true);
        declare("lampLine", true);
        declare("hud", true);
        declare("bestColor", DEFAULT_BEST_COLOR);
    }

    public static ZombieShootoutConfig getInstance() {
        INSTANCE.ensureLoaded();
        return INSTANCE;
    }

    public static void load() {
        INSTANCE.reload();
    }

    public boolean isOutlines() {
        return bool("outlines");
    }

    public void setOutlines(boolean v) {
        put("outlines", v);
    }

    public boolean isHighestOnly() {
        return bool("highestOnly");
    }

    public void setHighestOnly(boolean v) {
        put("highestOnly", v);
    }

    public boolean isTimers() {
        return bool("timers");
    }

    public void setTimers(boolean v) {
        put("timers", v);
    }

    public boolean isBest() {
        return bool("best");
    }

    public void setBest(boolean v) {
        put("best", v);
    }

    public boolean isLamps() {
        return bool("lamps");
    }

    public void setLamps(boolean v) {
        put("lamps", v);
    }

    public boolean isLampLine() {
        return bool("lampLine");
    }

    public void setLampLine(boolean v) {
        put("lampLine", v);
    }

    public boolean isHud() {
        return bool("hud");
    }

    public void setHud(boolean v) {
        put("hud", v);
    }

    public int getBestColor() {
        return integer("bestColor");
    }

    public void setBestColor(int v) {
        put("bestColor", v);
    }
}
