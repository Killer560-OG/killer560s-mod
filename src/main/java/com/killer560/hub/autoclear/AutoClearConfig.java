package com.killer560.hub.autoclear;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Auto Clear settings - see {@link AutoClearFeature}. Cheat build only and off by default: {@link #isEnabled()}
 * can never be true on the legit jar, even from a copied cheat config. Read by the shared "Auto Clear" tab (owned by
 * Auto Secret) through these getters and {@link AutoClearSettings#addSettings}.
 */
public final class AutoClearConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
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
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(o, "enabled", cfg.enabled);
                cfg.mode = ConfigJson.getEnum(o, "mode", Mode.class, cfg.mode);
                cfg.weapon = ConfigJson.getEnum(o, "weapon", Weapon.class, cfg.weapon);
                cfg.keybind = KeyUtil.sanitize(ConfigJson.getInt(o, "keybind", cfg.keybind));
                cfg.hyperionHops = ConfigJson.getBool(o, "hyperionHops", cfg.hyperionHops);
                cfg.setMobTimeoutTicks(ConfigJson.getInt(o, "mobTimeoutTicks", cfg.mobTimeoutTicks));
                cfg.pickUpKeys = ConfigJson.getBool(o, "pickUpKeys", cfg.pickUpKeys);
                cfg.walkWhenFaster = ConfigJson.getBool(o, "walkWhenFaster", cfg.walkWhenFaster);
                cfg.predictiveBats = ConfigJson.getBool(o, "predictiveBats", cfg.predictiveBats);
                cfg.oneTap = ConfigJson.getEnum(o, "oneTap", OneTap.class, cfg.oneTap);
                // "statusHud" (the old Clear Status HUD switch) is read no more: one Status HUD serves every auto clear
                // since 2026-10-15, switched in Auto Secret's config (AutoSecretConfig.statusHud).
            } catch (Exception e) {
                // unreadable file (not just a bad key) - keep defaults
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("mode", mode.name());
            o.addProperty("weapon", weapon.name());
            o.addProperty("keybind", keybind);
            o.addProperty("hyperionHops", hyperionHops);
            o.addProperty("mobTimeoutTicks", mobTimeoutTicks);
            o.addProperty("pickUpKeys", pickUpKeys);
            o.addProperty("walkWhenFaster", walkWhenFaster);
            o.addProperty("predictiveBats", predictiveBats);
            o.addProperty("oneTap", oneTap.name());
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** The feature switch the tab shows. The legit jar can never report true, even from a copied cheat config. */
    public boolean isEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && enabled
                && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isEnabledRaw() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }

    public Mode getMode() { return mode; }
    public void setMode(Mode v) { mode = v == null ? Mode.ANY_MOB_ROOM : v; }

    public Weapon getWeapon() { return weapon; }
    public void setWeapon(Weapon v) { weapon = v == null ? Weapon.HYPERION : v; }

    public int getKeybind() { return keybind; }
    public void setKeybind(int v) { keybind = KeyUtil.sanitize(v); }

    public boolean isHyperionHops() { return hyperionHops; }
    public void setHyperionHops(boolean v) { hyperionHops = v; }

    public boolean isPickUpKeys() { return pickUpKeys; }
    public void setPickUpKeys(boolean v) { pickUpKeys = v; }

    public boolean isWalkWhenFaster() { return walkWhenFaster; }
    public void setWalkWhenFaster(boolean v) { walkWhenFaster = v; }

    public boolean isPredictiveBats() { return predictiveBats; }
    public void setPredictiveBats(boolean v) { predictiveBats = v; }

    public OneTap getOneTap() { return oneTap; }
    public void setOneTap(OneTap v) { oneTap = v == null ? OneTap.AT_ONCE : v; }

    public int getMobTimeoutTicks() { return mobTimeoutTicks; }
    public void setMobTimeoutTicks(int v) {
        mobTimeoutTicks = Math.max(MIN_MOB_TIMEOUT_TICKS, Math.min(MAX_MOB_TIMEOUT_TICKS, v));
    }

}
