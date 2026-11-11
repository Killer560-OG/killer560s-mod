package com.killer560.hub.carnival;



/** Catch a Fish Helper settings (see {@link CatchAFish}). Display only; off by default. */
public final class CatchAFishConfig extends CarnivalSettings {

    public static final int DEFAULT_YELLOW_COLOR = 0xFFFFFF55;
    public static final int DEFAULT_OTHER_COLOR = 0xFF55FFFF;
    private static final CatchAFishConfig INSTANCE = new CatchAFishConfig();

    private CatchAFishConfig() {
        super("catchafish");
        declare("enabled", false);
        declare("outlines", true);
        declare("yellowOnly", false);
        declare("best", true);
        declare("line", true);
        declare("hud", true);
        declare("yellowColor", DEFAULT_YELLOW_COLOR);
        declare("otherColor", DEFAULT_OTHER_COLOR);
    }

    public static CatchAFishConfig getInstance() {
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

    public boolean isYellowOnly() {
        return bool("yellowOnly");
    }

    public void setYellowOnly(boolean v) {
        put("yellowOnly", v);
    }

    public boolean isBest() {
        return bool("best");
    }

    public void setBest(boolean v) {
        put("best", v);
    }

    public boolean isLine() {
        return bool("line");
    }

    public void setLine(boolean v) {
        put("line", v);
    }

    public boolean isHud() {
        return bool("hud");
    }

    public void setHud(boolean v) {
        put("hud", v);
    }

    public int getYellowColor() {
        return integer("yellowColor");
    }

    public void setYellowColor(int v) {
        put("yellowColor", v);
    }

    public int getOtherColor() {
        return integer("otherColor");
    }

    public void setOtherColor(int v) {
        put("otherColor", v);
    }
}
