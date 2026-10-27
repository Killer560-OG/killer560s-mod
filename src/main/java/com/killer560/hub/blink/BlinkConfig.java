package com.killer560.hub.blink;

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
 * Persisted Blink settings - see {@link BlinkFeature} (Hold) and {@link BlinkCharge} (Charge). Off and both keys unbound
 * by default, like every new feature.
 *
 * <p>Since 2026-10-25 (killer560's rework): Charge and Hold are two sections with a key and a tick slider each, instead
 * of one key with a Style. Both sliders run 1-20: past 20 ticks Hypixel sets the blink back (his test, and his log:
 * 21 and 22 tick holds were set back part way). Gone at his request: Style, Shoot When You Move, Blink Again After Shot,
 * Menu Clicks Go Through (always on now), At Max Ticks, Release On Hit/Dig/Bow-Eat (a blink is released only when he
 * releases it) and Keep Holding Through Teleports (always on now). An old file is migrated on load.
 */
public final class BlinkConfig {

    /** How the Charge key drives charging. */
    public enum Mode {
        /** A press starts charging; the next press fires the bank (and charging goes on). */
        TOGGLE("Toggle"),
        /** Charging lasts while the key is down; letting go fires the bank. */
        HOLD("Hold");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    /** What the HUD shows: a bar, the "Charge 12/20" text, or both (killer560, 2026-10-27: "a growing bar"). */
    public enum HudStyle {
        BAR("Bar"),
        TEXT("Text"),
        BOTH("Both");

        public final String label;

        HudStyle(String label) {
            this.label = label;
        }
    }

    /** Smallest and largest tick count either slider offers. More than 20 does not go off on Hypixel. */
    public static final int MIN_TICKS = 1;
    public static final int MAX_TICKS = 20;
    public static final int DEFAULT_TICKS = MAX_TICKS;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-blink.json");

    private static BlinkConfig instance;

    private boolean enabled = false;
    /** A GLFW key or a {@link KeyUtil} mouse code; {@link KeyUtil#NONE} when unbound. */
    private int chargeKeyCode = KeyUtil.NONE;
    private Mode chargeMode = Mode.TOGGLE;
    private int chargeTicks = DEFAULT_TICKS;
    /** The Hold key: packets are held while it is down and released when it comes up. */
    private int holdKeyCode = KeyUtil.NONE;
    private int holdTicks = DEFAULT_TICKS;
    private boolean showHud = true;
    private boolean showServerBox = true;
    private boolean chatMessages = false;
    /** A release jar writes Blink's log lines only while this is on (dev and cheat jars always do). */
    private boolean debugLog = false;
    private HudStyle hudStyle = HudStyle.BOTH;

    private BlinkConfig() {
    }

    public static BlinkConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        BlinkConfig cfg = new BlinkConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
                if (obj.has("chargeKeyCode") || obj.has("holdKeyCode")) {
                    cfg.chargeKeyCode = KeyUtil.sanitizeBind(ConfigJson.getInt(obj, "chargeKeyCode", cfg.chargeKeyCode));
                    cfg.holdKeyCode = KeyUtil.sanitizeBind(ConfigJson.getInt(obj, "holdKeyCode", cfg.holdKeyCode));
                    cfg.chargeMode = ConfigJson.getEnum(obj, "chargeMode", Mode.class, cfg.chargeMode);
                    cfg.setChargeTicks(ConfigJson.getInt(obj, "chargeTicks", cfg.chargeTicks));
                    cfg.setHoldTicks(ConfigJson.getInt(obj, "holdTicks", cfg.holdTicks));
                } else {
                    // The one-key file from before the rework: its key goes to the section its Style was.
                    int key = KeyUtil.sanitizeBind(ConfigJson.getInt(obj, "keyCode", KeyUtil.NONE));
                    boolean charge = "CHARGE".equals(ConfigJson.getString(obj, "style", "HOLD_PACKETS"));
                    int ticks = ConfigJson.getInt(obj, "maxTicks", DEFAULT_TICKS);
                    if (charge) {
                        cfg.chargeKeyCode = key;
                        cfg.chargeMode = ConfigJson.getEnum(obj, "mode", Mode.class, cfg.chargeMode);
                    } else {
                        cfg.holdKeyCode = key;
                    }
                    cfg.setChargeTicks(ticks);
                    cfg.setHoldTicks(ticks);
                }
                cfg.showHud = ConfigJson.getBool(obj, "showHud", cfg.showHud);
                cfg.showServerBox = ConfigJson.getBool(obj, "showServerBox", cfg.showServerBox);
                cfg.chatMessages = ConfigJson.getBool(obj, "chatMessages", cfg.chatMessages);
                cfg.debugLog = ConfigJson.getBool(obj, "debugLog", cfg.debugLog);
                cfg.hudStyle = ConfigJson.getEnum(obj, "hudStyle", HudStyle.class, cfg.hudStyle);
            } catch (Exception e) {
                cfg = new BlinkConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("chargeKeyCode", chargeKeyCode);
            obj.addProperty("chargeMode", chargeMode.name());
            obj.addProperty("chargeTicks", chargeTicks);
            obj.addProperty("holdKeyCode", holdKeyCode);
            obj.addProperty("holdTicks", holdTicks);
            obj.addProperty("showHud", showHud);
            obj.addProperty("showServerBox", showServerBox);
            obj.addProperty("chatMessages", chatMessages);
            obj.addProperty("debugLog", debugLog);
            obj.addProperty("hudStyle", hudStyle.name());
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static int clampTicks(int t) {
        return Math.max(MIN_TICKS, Math.min(MAX_TICKS, t));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getChargeKeyCode() {
        return chargeKeyCode;
    }

    public void setChargeKeyCode(int keyCode) {
        this.chargeKeyCode = KeyUtil.sanitizeBind(keyCode);
    }

    public Mode getChargeMode() {
        return chargeMode;
    }

    public void setChargeMode(Mode mode) {
        this.chargeMode = mode == null ? Mode.TOGGLE : mode;
    }

    public int getChargeTicks() {
        return chargeTicks;
    }

    public void setChargeTicks(int ticks) {
        this.chargeTicks = clampTicks(ticks);
    }

    public int getHoldKeyCode() {
        return holdKeyCode;
    }

    public void setHoldKeyCode(int keyCode) {
        this.holdKeyCode = KeyUtil.sanitizeBind(keyCode);
    }

    public int getHoldTicks() {
        return holdTicks;
    }

    public void setHoldTicks(int ticks) {
        this.holdTicks = clampTicks(ticks);
    }

    public boolean isShowHud() {
        return showHud;
    }

    public void setShowHud(boolean showHud) {
        this.showHud = showHud;
    }

    public boolean isShowServerBox() {
        return showServerBox;
    }

    public void setShowServerBox(boolean showServerBox) {
        this.showServerBox = showServerBox;
    }

    public boolean isChatMessages() {
        return chatMessages;
    }

    public void setChatMessages(boolean chatMessages) {
        this.chatMessages = chatMessages;
    }

    public HudStyle getHudStyle() {
        return hudStyle;
    }

    public void setHudStyle(HudStyle style) {
        this.hudStyle = style == null ? HudStyle.BOTH : style;
    }

    public boolean isDebugLog() {
        return debugLog;
    }

    public void setDebugLog(boolean debugLog) {
        this.debugLog = debugLog;
    }
}
