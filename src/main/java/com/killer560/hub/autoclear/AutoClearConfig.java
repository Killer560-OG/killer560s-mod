package com.killer560.hub.autoclear;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/**
 * Persisted Auto Clear settings - see {@link AutoClearFeature}. Cheat build only and off by default: {@link #isEnabled()}
 * can never be true on the legit jar, even from a copied cheat config. Its rows are on the Dungeons Autopilot tab
 * (gui/tab/AutoClearTab), which starts and stops it.
 */
public final class AutoClearConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-autoclear.json");

    /** Which rooms a toggle-started clear works through. */
    public enum Mode {
        /** Every uncleared mob room on the floor, nearest first. */
        ANY_MOB_ROOM,
        /**
         * Only the blood rush: the uncleared mob rooms on the shortest room-graph path from the Entrance to the Blood
         * door (the rooms a blood-rush path walks through). killer560's own words were "only the rooms on the blood
         * rush"; the exact rule is this agent's reading of it and is written into the setting's tooltip so he can
         * correct it.
         */
        BLOOD_RUSH_SPLIT;

        public String label() {
            return this == ANY_MOB_ROOM ? "Any Mob Room" : "Blood Rush Split";
        }
    }

    /** What attacks the starred mobs - the Auto Routes crypt node's Crypt Weapon pattern. */
    public enum Weapon {
        HYPERION, SPIRIT_SCEPTRE;

        public String label() {
            return this == HYPERION ? "Hyperion" : "Spirit Sceptre";
        }

        /** Skyblock id for {@code ItemIdentity.findHotbarSlot}; any wither blade matches HYPERION (its family). */
        public String itemId() {
            return this == SPIRIT_SCEPTRE ? "BAT_WAND" : "HYPERION";
        }
    }

    /**
     * When a Spirit Sceptre bat is enough to kill its mob in one hit (killer560, 2026-10-20), what Auto Clear does after
     * sending it: keep on it as before, move on to the next mob when the bat is due to land, or move on the tick it leaves
     * (the next etherwarp overlaps the bat's flight).
     */
    public enum OneTap {
        OFF, AFTER_IMPACT, AT_ONCE;

        public String label() {
            return switch (this) {
                case OFF -> "Off";
                case AFTER_IMPACT -> "After Impact";
                case AT_ONCE -> "At Once";
            };
        }
    }

    public static final int MIN_MOB_TIMEOUT_TICKS = 40;
    public static final int MAX_MOB_TIMEOUT_TICKS = 400;

    private static AutoClearConfig instance;

    private boolean enabled = false;
    private Mode mode = Mode.ANY_MOB_ROOM;
    private Weapon weapon = Weapon.HYPERION;
    /** Toggle key, raw-polled like every Auto Routes key; unbound by default. */
    private int keybind = KeyUtil.NONE;
    /** Allow Hyperion hops (Wither Impact toward the mob) for short open trips instead of etherwarping. */
    private boolean hyperionHops = true;
    /** How long one mob is attacked without dying before it is skipped. Ticks. */
    private int mobTimeoutTicks = 160;
    /** Go for a Wither Key that drops during a clear before going on (killer560, 2026-10-19). */
    private boolean pickUpKeys = true;
    /** Walk (sprint, jump) to a mob or a room spot when that is estimated faster than etherwarping (killer560, 2026-10-19). */
    private boolean walkWhenFaster = true;
    /** Aim each bat where a moving mob will be when it gets there (killer560, 2026-10-20). */
    private boolean predictiveBats = true;
    /** What follows a bat that kills its mob in one hit (killer560, 2026-10-20). */
    private OneTap oneTap = OneTap.AT_ONCE;
    /** Only wither doors toward Blood: no rooms, mobs or secrets; stops once the Blood door is open (killer560, 2026-11-15). */
    private boolean doorOnly = false;
    /** Plan and time clears from how long each room really took before (killer560, 2026-11-23). */
    private boolean learnedRoomCosts = true;

    private AutoClearConfig() {
    }

    public static AutoClearConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        AutoClearConfig cfg = new AutoClearConfig();
        JsonObject o = ConfigJson.read(CONFIG_PATH);
        if (o != null) {
            cfg.mode = ConfigJson.getEnum(o, "mode", Mode.class, cfg.mode);
            cfg.weapon = ConfigJson.getEnum(o, "weapon", Weapon.class, cfg.weapon);
            cfg.keybind = KeyUtil.sanitize(ConfigJson.getInt(o, "keybind", cfg.keybind));
            cfg.hyperionHops = ConfigJson.getBool(o, "hyperionHops", cfg.hyperionHops);
            cfg.setMobTimeoutTicks(ConfigJson.getInt(o, "mobTimeoutTicks", cfg.mobTimeoutTicks));
            cfg.pickUpKeys = ConfigJson.getBool(o, "pickUpKeys", cfg.pickUpKeys);
            cfg.walkWhenFaster = ConfigJson.getBool(o, "walkWhenFaster", cfg.walkWhenFaster);
            cfg.predictiveBats = ConfigJson.getBool(o, "predictiveBats", cfg.predictiveBats);
            cfg.oneTap = ConfigJson.getEnum(o, "oneTap", OneTap.class, cfg.oneTap);
            cfg.doorOnly = ConfigJson.getBool(o, "doorOnly", cfg.doorOnly);
            cfg.learnedRoomCosts = ConfigJson.getBool(o, "learnedRoomCosts", cfg.learnedRoomCosts);
            // "statusHud" (the old Clear Status HUD switch) is read no more: one Status HUD serves every auto clear
            // since 2026-10-15, switched in Auto Secret's config (AutoSecretConfig.statusHud).
        }
        instance = cfg;
    }

    public void save() {
        JsonObject o = new JsonObject();
        o.addProperty("mode", mode.name());
        o.addProperty("weapon", weapon.name());
        o.addProperty("keybind", keybind);
        o.addProperty("hyperionHops", hyperionHops);
        o.addProperty("mobTimeoutTicks", mobTimeoutTicks);
        o.addProperty("pickUpKeys", pickUpKeys);
        o.addProperty("walkWhenFaster", walkWhenFaster);
        o.addProperty("predictiveBats", predictiveBats);
        o.addProperty("oneTap", oneTap.name());
        o.addProperty("doorOnly", doorOnly);
        o.addProperty("learnedRoomCosts", learnedRoomCosts);
        ConfigJson.write(CONFIG_PATH, o);
    }

    /** On while the Dungeons Autopilot switch is (apmenu-1121), or {@link #setEnabled} (testkit). The legit jar can never
     *  report true. */
    public boolean isEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && isEnabledRaw()
                && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isEnabledRaw() { return enabled || com.killer560.hub.autosecret.DungeonsAutopilot.isOn(); }
    /** Not saved and not in the menu since apmenu-1121: the one switch is Dungeons Autopilot's. Tests turn Auto Clear on here. */
    public void setEnabled(boolean v) { enabled = v; }

    /** What one Dungeons Autopilot step runs ({@code AutoClearFeature.startRun}); null = the saved values. */
    private Mode runMode;
    private Boolean runDoorOnly;

    public void setRunOverride(Mode m, Boolean doorOnlyRun) {
        runMode = m;
        runDoorOnly = doorOnlyRun;
    }

    public Mode getMode() { return runMode != null ? runMode : mode; }
    public void setMode(Mode v) { mode = v == null ? Mode.ANY_MOB_ROOM : v; }

    public Weapon getWeapon() { return weapon; }
    public void setWeapon(Weapon v) { weapon = v == null ? Weapon.HYPERION : v; }

    /** The old Clear Toggle Key, only read to migrate it into the Dungeons Autopilot bind; nothing polls it. */
    public int getKeybindRaw() { return keybind; }
    public int getKeybind() { return KeyUtil.NONE; }
    public void setKeybind(int v) { keybind = KeyUtil.sanitize(v); }

    public boolean isHyperionHops() { return hyperionHops; }
    public void setHyperionHops(boolean v) { hyperionHops = v; }

    public boolean isPickUpKeys() { return pickUpKeys; }
    public void setPickUpKeys(boolean v) { pickUpKeys = v; }

    public boolean isWalkWhenFaster() { return walkWhenFaster; }
    public boolean isLearnedRoomCosts() { return learnedRoomCosts; }
    public void setLearnedRoomCosts(boolean v) { learnedRoomCosts = v; }
    public void setWalkWhenFaster(boolean v) { walkWhenFaster = v; }

    public boolean isPredictiveBats() { return predictiveBats; }
    public void setPredictiveBats(boolean v) { predictiveBats = v; }

    public boolean isDoorOnly() { return runDoorOnly != null ? runDoorOnly : doorOnly; }
    public void setDoorOnly(boolean v) { doorOnly = v; }

    public OneTap getOneTap() { return oneTap; }
    public void setOneTap(OneTap v) { oneTap = v == null ? OneTap.AT_ONCE : v; }

    public int getMobTimeoutTicks() { return mobTimeoutTicks; }
    public void setMobTimeoutTicks(int v) {
        mobTimeoutTicks = Math.max(MIN_MOB_TIMEOUT_TICKS, Math.min(MAX_MOB_TIMEOUT_TICKS, v));
    }

}
