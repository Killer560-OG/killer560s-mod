package com.killer560.hub.carnival;



/** Auto Catch a Fish settings (see {@link AutoCatchAFish}). Cheat build only; off by default. */
public final class AutoCatchAFishConfig extends CarnivalSettings {

    public static final int MIN_DELAY_MS = 100;
    public static final int MAX_DELAY_MS = 2000;
    private static final AutoCatchAFishConfig INSTANCE = new AutoCatchAFishConfig();

    private AutoCatchAFishConfig() {
        super("autocatch");
        declare("enabled", false);
        declare("castDelayMs", 300);
        declare("freeCamera", false);
    }

    public static AutoCatchAFishConfig getInstance() {
        INSTANCE.ensureLoaded();
        return INSTANCE;
    }

    public static void load() {
        INSTANCE.reload();
    }

    @Override
    protected void clampAll() {
        setCastDelayMs(getCastDelayMs());
    }

    public int getCastDelayMs() {
        return integer("castDelayMs");
    }

    public void setCastDelayMs(int v) {
        put("castDelayMs", clamp(v, MIN_DELAY_MS, MAX_DELAY_MS));
    }

    public boolean isFreeCamera() {
        return bool("freeCamera");
    }

    public void setFreeCamera(boolean v) {
        put("freeCamera", v);
    }
}
