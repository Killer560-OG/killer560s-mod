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

/** Persisted Blink settings - see {@link BlinkFeature}. Off and the key unbound by default, like every new feature. */
public final class BlinkConfig {

    /** How the Blink key drives the hold. */
    public enum Mode {
        /** Each press starts a hold; the next press (or the tick limit) releases it. */
        TOGGLE("Toggle"),
        /** The hold lasts while the key is down (or until the tick limit). */
        HOLD("Hold");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    /** What happens when a hold reaches Max Ticks. */
    public enum LimitAction {
        /** Release everything and stop blinking until the key is pressed again. */
        RELEASE("Release and stop"),
        /** Release everything and start a new hold straight away, for as long as the key says so. */
        PULSE("Release and keep going");

        public final String label;

        LimitAction(String label) {
            this.label = label;
        }
    }

    /** Smallest and largest Max Ticks the slider offers. */
    public static final int MIN_TICKS = 1;
    public static final int MAX_TICKS = 200;
    /**
     * The longest hold GrimAC accepts, measured (testkit 1025-blink-sweep-walk/-still/-latency, 2026-10-25: 22 clean every
     * time walking, standing and at 100 ms; 23 draws one TimerLimit every time). Grim's TimerLimit lets the timer balance
     * fall at most ping-abuse-limit-threshold (1000 ms) + TimerA drift (120 ms) behind real time, and each held tick
     * the burst brings back is 50 ms of it: 1120 / 50 = 22.4.
     */
    public static final int GRIM_MAX_TICKS = 22;
    public static final int DEFAULT_TICKS = GRIM_MAX_TICKS;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-blink.json");

    private static BlinkConfig instance;

    private boolean enabled = false;
    private Mode mode = Mode.TOGGLE;
    private LimitAction limitAction = LimitAction.RELEASE;
    /** A GLFW key or a {@link KeyUtil} mouse code; {@link KeyUtil#NONE} when unbound. */
    private int keyCode = KeyUtil.NONE;
    private int maxTicks = DEFAULT_TICKS;
    private boolean showHud = true;
    private boolean showServerBox = true;
    private boolean chatMessages = false;
    /** Release before an attack or entity click and let that tick go out live (see BlinkFeature): GrimAC-clean. */
    private boolean releaseOnHit = true;
    /** Release before a dig starts and let that tick go out live (see BlinkFeature): GrimAC-clean. */
    private boolean releaseOnDig = true;

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
                cfg.mode = ConfigJson.getEnum(obj, "mode", Mode.class, cfg.mode);
                cfg.limitAction = ConfigJson.getEnum(obj, "limitAction", LimitAction.class, cfg.limitAction);
                cfg.keyCode = KeyUtil.sanitizeBind(ConfigJson.getInt(obj, "keyCode", cfg.keyCode));
                cfg.setMaxTicks(ConfigJson.getInt(obj, "maxTicks", cfg.maxTicks));
                cfg.showHud = ConfigJson.getBool(obj, "showHud", cfg.showHud);
                cfg.showServerBox = ConfigJson.getBool(obj, "showServerBox", cfg.showServerBox);
                cfg.chatMessages = ConfigJson.getBool(obj, "chatMessages", cfg.chatMessages);
                cfg.releaseOnHit = ConfigJson.getBool(obj, "releaseOnHit", cfg.releaseOnHit);
                cfg.releaseOnDig = ConfigJson.getBool(obj, "releaseOnDig", cfg.releaseOnDig);
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
            obj.addProperty("mode", mode.name());
            obj.addProperty("limitAction", limitAction.name());
            obj.addProperty("keyCode", keyCode);
            obj.addProperty("maxTicks", maxTicks);
            obj.addProperty("showHud", showHud);
            obj.addProperty("showServerBox", showServerBox);
            obj.addProperty("chatMessages", chatMessages);
            obj.addProperty("releaseOnHit", releaseOnHit);
            obj.addProperty("releaseOnDig", releaseOnDig);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.TOGGLE : mode;
    }

    public LimitAction getLimitAction() {
        return limitAction;
    }

    public void setLimitAction(LimitAction limitAction) {
        this.limitAction = limitAction == null ? LimitAction.RELEASE : limitAction;
    }

    public int getKeyCode() {
        return keyCode;
    }

    public void setKeyCode(int keyCode) {
        this.keyCode = KeyUtil.sanitizeBind(keyCode);
    }

    public int getMaxTicks() {
        return maxTicks;
    }

    public void setMaxTicks(int maxTicks) {
        this.maxTicks = Math.max(MIN_TICKS, Math.min(MAX_TICKS, maxTicks));
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

    public boolean isReleaseOnHit() {
        return releaseOnHit;
    }

    public void setReleaseOnHit(boolean releaseOnHit) {
        this.releaseOnHit = releaseOnHit;
    }

    public boolean isReleaseOnDig() {
        return releaseOnDig;
    }

    public void setReleaseOnDig(boolean releaseOnDig) {
        this.releaseOnDig = releaseOnDig;
    }
}
