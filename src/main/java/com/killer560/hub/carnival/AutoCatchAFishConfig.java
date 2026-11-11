package com.killer560.hub.carnival;



/** Auto Catch a Fish settings (see {@link AutoCatchAFish}). Cheat build only; off by default. */
public final class AutoCatchAFishConfig extends CarnivalSettings {

    public static final int MIN_DELAY_MS = 100;
    public static final int MAX_DELAY_MS = 2000;
    public static final int MIN_WAIT_S = 1;
    public static final int MAX_WAIT_S = 10;
    private static final AutoCatchAFishConfig INSTANCE = new AutoCatchAFishConfig();

    private AutoCatchAFishConfig() {
        super("autocatch");
        declare("enabled", false);
        declare("castDelayMs", 300);
        declare("hookWaitS", 3);
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
        setHookWaitS(getHookWaitS());
    }

    public int getCastDelayMs() {
        return integer("castDelayMs");
    }

    public void setCastDelayMs(int v) {
        put("castDelayMs", clamp(v, MIN_DELAY_MS, MAX_DELAY_MS));
    }

    /** How long a cast may sit without hooking a fish before it is reeled back and cast again. */
    public int getHookWaitS() {
        return integer("hookWaitS");
    }

    public void setHookWaitS(int v) {
        put("hookWaitS", clamp(v, MIN_WAIT_S, MAX_WAIT_S));
    }

    public boolean isFreeCamera() {
        return bool("freeCamera");
    }

    public void setFreeCamera(boolean v) {
        put("freeCamera", v);
    }
}
