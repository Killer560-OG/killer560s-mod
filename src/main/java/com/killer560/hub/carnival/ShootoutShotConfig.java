package com.killer560.hub.carnival;



/**
 * Settings shared in shape by Zombie Shootout Triggerbot and Auto Zombie Shootout (cheat build only, off by default): one
 * file each, the same keys - which key shoots, the gap between shots, the aim delay and whether only the Carnival Dart
 * Tube may shoot - plus the auto's own Lamps First and Free Camera.
 */
public final class ShootoutShotConfig extends CarnivalSettings {

    public static final int MIN_SHOT_MS = 100;
    public static final int MAX_SHOT_MS = 1000;
    public static final int MAX_AIM_DELAY_MS = 500;
    public static final String[] SHOOT_WITH = {"Use", "Attack"};

    private static final ShootoutShotConfig TRIGGERBOT = new ShootoutShotConfig("triggerbot");
    private static final ShootoutShotConfig AUTO = new ShootoutShotConfig("autoshootout");

    private ShootoutShotConfig(String file) {
        super(file);
        declare("enabled", false);
        declare("shootWith", 0);
        declare("shotDelayMs", 250);
        declare("aimDelayMs", 50);
        declare("tubeOnly", true);
        declare("lampsFirst", true);
        declare("freeCamera", false);
    }

    /** Zombie Shootout Triggerbot's settings. */
    public static ShootoutShotConfig triggerbot() {
        TRIGGERBOT.ensureLoaded();
        return TRIGGERBOT;
    }

    /** Auto Zombie Shootout's settings. */
    public static ShootoutShotConfig auto() {
        AUTO.ensureLoaded();
        return AUTO;
    }

    public static void load() {
        TRIGGERBOT.reload();
        AUTO.reload();
    }

    @Override
    protected void clampAll() {
        setShotDelayMs(getShotDelayMs());
        setAimDelayMs(getAimDelayMs());
        setShootWith(getShootWith());
    }

    /** 0 = your Use key (right click), 1 = your Attack key (left click). */
    public int getShootWith() {
        return integer("shootWith");
    }

    public void setShootWith(int v) {
        put("shootWith", clamp(v, 0, SHOOT_WITH.length - 1));
    }

    public int getShotDelayMs() {
        return integer("shotDelayMs");
    }

    public void setShotDelayMs(int v) {
        put("shotDelayMs", clamp(v, MIN_SHOT_MS, MAX_SHOT_MS));
    }

    public int getAimDelayMs() {
        return integer("aimDelayMs");
    }

    public void setAimDelayMs(int v) {
        put("aimDelayMs", clamp(v, 0, MAX_AIM_DELAY_MS));
    }

    public boolean isTubeOnly() {
        return bool("tubeOnly");
    }

    public void setTubeOnly(boolean v) {
        put("tubeOnly", v);
    }

    public boolean isLampsFirst() {
        return bool("lampsFirst");
    }

    public void setLampsFirst(boolean v) {
        put("lampsFirst", v);
    }

    public boolean isFreeCamera() {
        return bool("freeCamera");
    }

    public void setFreeCamera(boolean v) {
        put("freeCamera", v);
    }
}
