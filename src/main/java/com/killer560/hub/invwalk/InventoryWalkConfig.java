package com.killer560.hub.invwalk;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/** Persisted Inventory Walk settings - see {@link InventoryWalkFeature}. Off by default, like every new feature. */
public final class InventoryWalkConfig {

    /** Which open screens keep the movement keys live. */
    public enum Screens {
        /** Inventories, chests and every other container (Skyblock menus, terminals). */
        CONTAINERS("Containers"),
        /** Any screen except chat and other text input. */
        ALL("All Screens");

        public final String label;

        Screens(String label) {
            this.label = label;
        }
    }

    /** How the Look Key works (invlook-1110). */
    public enum LookMode {
        /** Look while the key is held. */
        HOLD("Hold"),
        /** One press starts looking, the next stops; closing the menu stops it too. */
        TOGGLE("Toggle");

        public final String label;

        LookMode(String label) {
            this.label = label;
        }
    }

    public static final int MAX_PAUSE_TICKS = 20;

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-invwalk.json");

    private static InventoryWalkConfig instance;

    private boolean enabled = false;
    private Screens screens = Screens.CONTAINERS;
    private boolean jump = true;
    private boolean sprint = true;
    /** Ticks the movement keys are let go after a slot click (0 = never). */
    private int pauseAfterClickTicks = 0;
    /** Hold a click made while walking until a tick with no movement keys and no sprint has reached the server. */
    private boolean stopToClick = true;
    /** While held with a menu open, the mouse turns his view instead of moving the cursor (KeyUtil code, NONE = off). */
    private int lookKeyCode = com.killer560.hub.util.KeyUtil.NONE;
    /** With Stop To Click: a storage menu's pick-up stays unsent while he walks carrying it (iwbypass-1108). */
    private boolean carryInChests = false;
    private LookMode lookMode = LookMode.HOLD;

    private InventoryWalkConfig() {
    }

    public static InventoryWalkConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        InventoryWalkConfig cfg = new InventoryWalkConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
            cfg.screens = ConfigJson.getEnum(obj, "screens", Screens.class, cfg.screens);
            cfg.jump = ConfigJson.getBool(obj, "jump", cfg.jump);
            cfg.sprint = ConfigJson.getBool(obj, "sprint", cfg.sprint);
            cfg.setPauseAfterClickTicks(ConfigJson.getInt(obj, "pauseAfterClickTicks", cfg.pauseAfterClickTicks));
            cfg.stopToClick = ConfigJson.getBool(obj, "stopToClick", cfg.stopToClick);
            cfg.lookKeyCode = com.killer560.hub.util.KeyUtil.sanitizeBind(ConfigJson.getInt(obj, "lookKeyCode", cfg.lookKeyCode));
            cfg.carryInChests = ConfigJson.getBool(obj, "carryInChests", cfg.carryInChests);
            cfg.lookMode = ConfigJson.getEnum(obj, "lookMode", LookMode.class, cfg.lookMode);
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("screens", screens.name());
        obj.addProperty("jump", jump);
        obj.addProperty("sprint", sprint);
        obj.addProperty("pauseAfterClickTicks", pauseAfterClickTicks);
        obj.addProperty("stopToClick", stopToClick);
        obj.addProperty("lookKeyCode", lookKeyCode);
        obj.addProperty("carryInChests", carryInChests);
        obj.addProperty("lookMode", lookMode.name());
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Screens getScreens() {
        return screens;
    }

    public void setScreens(Screens screens) {
        this.screens = screens == null ? Screens.CONTAINERS : screens;
    }

    public boolean isJump() {
        return jump;
    }

    public void setJump(boolean jump) {
        this.jump = jump;
    }

    public boolean isSprint() {
        return sprint;
    }

    public void setSprint(boolean sprint) {
        this.sprint = sprint;
    }

    public int getPauseAfterClickTicks() {
        return pauseAfterClickTicks;
    }

    public void setPauseAfterClickTicks(int ticks) {
        this.pauseAfterClickTicks = Math.max(0, Math.min(MAX_PAUSE_TICKS, ticks));
    }

    public boolean isStopToClick() {
        return stopToClick;
    }

    public void setStopToClick(boolean stopToClick) {
        this.stopToClick = stopToClick;
    }

    public int getLookKeyCode() {
        return lookKeyCode;
    }

    public void setLookKeyCode(int code) {
        this.lookKeyCode = code;
    }

    public boolean isCarryInChests() {
        return carryInChests;
    }

    public void setCarryInChests(boolean on) {
        this.carryInChests = on;
    }

    public LookMode getLookMode() {
        return lookMode;
    }

    public void setLookMode(LookMode mode) {
        this.lookMode = mode == null ? LookMode.HOLD : mode;
    }

}
