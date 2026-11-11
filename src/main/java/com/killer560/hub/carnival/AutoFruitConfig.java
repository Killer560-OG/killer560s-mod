package com.killer560.hub.carnival;



/**
 * Auto Fruit Digging's, Auto Shovel Swap's and Fruit Digging Triggerbot's settings (cheat build only, off by default): one file each, the same keys -
 * the switch, the least time between two of its actions, and (Auto Fruit Digging) Free Camera.
 */
public final class AutoFruitConfig extends CarnivalSettings {

    public static final int MIN_DELAY_MS = 100;
    public static final int MAX_DELAY_MS = 2000;

    private static final AutoFruitConfig DIG = new AutoFruitConfig("autodig", 500);
    private static final AutoFruitConfig SWAP = new AutoFruitConfig("shovelswap", 300);
    private static final AutoFruitConfig TRIGGER = new AutoFruitConfig("digtrigger", 150);

    private AutoFruitConfig(String file, int delay) {
        super(file);
        declare("enabled", false);
        declare("delayMs", delay);
        declare("freeCamera", false);
    }

    /** Auto Fruit Digging's settings. */
    public static AutoFruitConfig dig() {
        DIG.ensureLoaded();
        return DIG;
    }

    /** Auto Shovel Swap's settings. */
    public static AutoFruitConfig swap() {
        SWAP.ensureLoaded();
        return SWAP;
    }

    /** Fruit Digging Triggerbot's settings (delayMs = the reaction time after the crosshair reaches the tile). */
    public static AutoFruitConfig trigger() {
        TRIGGER.ensureLoaded();
        return TRIGGER;
    }

    public static void load() {
        DIG.reload();
        SWAP.reload();
        TRIGGER.reload();
    }

    /** Any is on, so the board must be read even with the solver's display off. */
    static boolean wantsBoard() {
        return dig().isEnabledRaw() || swap().isEnabledRaw() || trigger().isEnabledRaw();
    }

    @Override
    protected void clampAll() {
        setDelayMs(getDelayMs());
    }

    public int getDelayMs() {
        return integer("delayMs");
    }

    public void setDelayMs(int v) {
        put("delayMs", clamp(v, MIN_DELAY_MS, MAX_DELAY_MS));
    }

    public boolean isFreeCamera() {
        return bool("freeCamera");
    }

    public void setFreeCamera(boolean v) {
        put("freeCamera", v);
    }
}
