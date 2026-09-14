package com.killer560.hub.simonsays;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/** Persisted Simon Says settings - see {@link SimonSaysFeature}. Ships disabled by default, same as
 *  every other new feature in this mod. */
public final class SimonSaysConfig {

    /** How the "skip" trick is set up - a party-size dependent number of clicks on the start button
     *  during the device's inactive window rather than actually solving the sequence.
     *  <p>
     *  <b>Honesty note:</b> the click counts below are a starting guess (1 click per skip level, +1 if a
     *  friend is also clicking) - killer560 asked for these six named presets but this session has no
     *  confirmed real data on the exact click count Hypixel expects per mode. Each preset's click count
     *  is user-editable in the GUI specifically so it can be corrected after a real test run rather than
     *  silently trusting a guess. */
    public enum SkipMode {
        SINGLE_SKIP("Single Skip", 1),
        DOUBLE_SKIP("Double Skip", 2),
        DOUBLE_SKIP_WITH_FRIEND("Double Skip (w/ Friend)", 2),
        TRIPLE_SKIP_WITH_FRIEND("Triple Skip (w/ Friend)", 3),
        QUAD_SKIP_WITH_FRIEND("Quad Skip (w/ Friend)", 4),
        QUAD_SKIP_WITH_TWO_FRIENDS("Quad Skip (w/ 2 Friends)", 4);

        public final String label;
        public final int defaultClicks;

        SkipMode(String label, int defaultClicks) {
            this.label = label;
            this.defaultClicks = defaultClicks;
        }
    }

    public enum Style {
        FILLED, OUTLINE, FILLED_OUTLINE
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-simonsays.json");

    private static SimonSaysConfig instance;

    // Master + solver display
    private boolean enabled = false;
    private boolean solverEnabled = true;
    private Style style = Style.FILLED_OUTLINE;
    private boolean numberOverlay = true;
    private int firstColor = 0xFF55FF55;
    private int secondColor = 0xFFFFAA00;
    private int thirdColor = 0xFFFF5555;
    // Not cheat-gated - blocks a real click, doesn't send one, same real-vs-automation distinction
    // Odin's own "Block Wrong Clicks" toggle makes (it isn't cheat-gated there either). Shift always
    // overrides it, matching Odin's own real behavior, so a manual override is never fully locked out.
    private boolean preventMisclicksEnabled = false;

    // Chat progress
    private boolean announceProgress = false;
    private boolean partyProgressTrackerEnabled = true;

    // Assist / automation (cheat-build gated, see isTriggerBotEnabled/isAutoSolveEnabled/isAutoStartEnabled)
    private boolean triggerBotEnabled = false;
    private boolean autoSolveEnabled = false;
    private boolean autoStartEnabled = false;
    private SkipMode autoStartMode = SkipMode.SINGLE_SKIP;
    private final Map<SkipMode, Integer> skipClickCounts = defaultSkipClicks();
    private int autoStartClickDelayMs = 150;
    private boolean skipCompatibility = true;

    // Timer-with-variance pacing for auto-start clicks, replacing a flat per-click delay.
    private int clickTimerTargetMs = 12_800;
    private int clickTimerVarianceMs = 100;

    // Reset
    private int resetKeyCode = -1;
    private boolean autoSendResetMessage = false;
    private String resetMessageText = "Resetting Simon Says";

    // Legacy diagnostic block-state logger (still useful as a fallback data source) - see
    // SimonSaysFeature's original "immense amount of loggers" doc comment. Off by default now that a
    // real solver exists, but kept available.
    private boolean diagnosticLoggingEnabled = false;
    private int horizontalRadius = 8;
    private int verticalRadius = 4;

    private SimonSaysConfig() {
    }

    private static Map<SkipMode, Integer> defaultSkipClicks() {
        Map<SkipMode, Integer> map = new EnumMap<>(SkipMode.class);
        for (SkipMode mode : SkipMode.values()) {
            map.put(mode, mode.defaultClicks);
        }
        return map;
    }

    public static SimonSaysConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new SimonSaysConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            SimonSaysConfig cfg = new SimonSaysConfig();
            cfg.enabled = getBool(obj, "enabled", false);
            cfg.solverEnabled = getBool(obj, "solverEnabled", true);
            cfg.style = getEnum(obj, "style", Style.class, Style.FILLED_OUTLINE);
            cfg.numberOverlay = getBool(obj, "numberOverlay", true);
            cfg.firstColor = getInt(obj, "firstColor", 0xFF55FF55);
            cfg.secondColor = getInt(obj, "secondColor", 0xFFFFAA00);
            cfg.thirdColor = getInt(obj, "thirdColor", 0xFFFF5555);
            cfg.preventMisclicksEnabled = getBool(obj, "preventMisclicksEnabled", false);
            cfg.announceProgress = getBool(obj, "announceProgress", false);
            cfg.partyProgressTrackerEnabled = getBool(obj, "partyProgressTrackerEnabled", true);
            cfg.triggerBotEnabled = getBool(obj, "triggerBotEnabled", false);
            cfg.autoSolveEnabled = getBool(obj, "autoSolveEnabled", false);
            cfg.autoStartEnabled = getBool(obj, "autoStartEnabled", false);
            cfg.autoStartMode = getEnum(obj, "autoStartMode", SkipMode.class, SkipMode.SINGLE_SKIP);
            cfg.autoStartClickDelayMs = getInt(obj, "autoStartClickDelayMs", 150);
            cfg.skipCompatibility = getBool(obj, "skipCompatibility", true);
            cfg.clickTimerTargetMs = getInt(obj, "clickTimerTargetMs", 12_800);
            cfg.clickTimerVarianceMs = getInt(obj, "clickTimerVarianceMs", 100);
            cfg.resetKeyCode = getInt(obj, "resetKeyCode", -1);
            cfg.autoSendResetMessage = getBool(obj, "autoSendResetMessage", false);
            cfg.resetMessageText = obj.has("resetMessageText") ? obj.get("resetMessageText").getAsString() : "Resetting Simon Says";
            cfg.diagnosticLoggingEnabled = getBool(obj, "diagnosticLoggingEnabled", false);
            cfg.horizontalRadius = getInt(obj, "horizontalRadius", 8);
            cfg.verticalRadius = getInt(obj, "verticalRadius", 4);
            if (obj.has("skipClickCounts")) {
                JsonObject clicks = obj.getAsJsonObject("skipClickCounts");
                for (SkipMode mode : SkipMode.values()) {
                    if (clicks.has(mode.name())) {
                        cfg.skipClickCounts.put(mode, clicks.get(mode.name()).getAsInt());
                    }
                }
            }
            instance = cfg;
        } catch (Exception e) {
            instance = new SimonSaysConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("solverEnabled", solverEnabled);
            obj.addProperty("style", style.name());
            obj.addProperty("numberOverlay", numberOverlay);
            obj.addProperty("firstColor", firstColor);
            obj.addProperty("secondColor", secondColor);
            obj.addProperty("thirdColor", thirdColor);
            obj.addProperty("preventMisclicksEnabled", preventMisclicksEnabled);
            obj.addProperty("announceProgress", announceProgress);
            obj.addProperty("partyProgressTrackerEnabled", partyProgressTrackerEnabled);
            obj.addProperty("triggerBotEnabled", triggerBotEnabled);
            obj.addProperty("autoSolveEnabled", autoSolveEnabled);
            obj.addProperty("autoStartEnabled", autoStartEnabled);
            obj.addProperty("autoStartMode", autoStartMode.name());
            obj.addProperty("autoStartClickDelayMs", autoStartClickDelayMs);
            obj.addProperty("skipCompatibility", skipCompatibility);
            obj.addProperty("clickTimerTargetMs", clickTimerTargetMs);
            obj.addProperty("clickTimerVarianceMs", clickTimerVarianceMs);
            obj.addProperty("resetKeyCode", resetKeyCode);
            obj.addProperty("autoSendResetMessage", autoSendResetMessage);
            obj.addProperty("resetMessageText", resetMessageText);
            obj.addProperty("diagnosticLoggingEnabled", diagnosticLoggingEnabled);
            obj.addProperty("horizontalRadius", horizontalRadius);
            obj.addProperty("verticalRadius", verticalRadius);
            JsonObject clicks = new JsonObject();
            for (Map.Entry<SkipMode, Integer> e : skipClickCounts.entrySet()) {
                clicks.addProperty(e.getKey().name(), e.getValue());
            }
            obj.add("skipClickCounts", clicks);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static boolean getBool(JsonObject obj, String key, boolean def) {
        return obj.has(key) ? obj.get(key).getAsBoolean() : def;
    }

    private static int getInt(JsonObject obj, String key, int def) {
        return obj.has(key) ? obj.get(key).getAsInt() : def;
    }

    private static <E extends Enum<E>> E getEnum(JsonObject obj, String key, Class<E> type, E def) {
        if (!obj.has(key)) {
            return def;
        }
        try {
            return Enum.valueOf(type, obj.get(key).getAsString());
        } catch (Exception e) {
            return def;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isSolverEnabled() {
        return solverEnabled;
    }

    public void setSolverEnabled(boolean solverEnabled) {
        this.solverEnabled = solverEnabled;
    }

    public Style getStyle() {
        return style;
    }

    public void setStyle(Style style) {
        this.style = style;
    }

    public boolean isNumberOverlay() {
        return numberOverlay;
    }

    public void setNumberOverlay(boolean numberOverlay) {
        this.numberOverlay = numberOverlay;
    }

    public int getFirstColor() {
        return firstColor;
    }

    public void setFirstColor(int firstColor) {
        this.firstColor = firstColor;
    }

    public int getSecondColor() {
        return secondColor;
    }

    public void setSecondColor(int secondColor) {
        this.secondColor = secondColor;
    }

    public int getThirdColor() {
        return thirdColor;
    }

    public void setThirdColor(int thirdColor) {
        this.thirdColor = thirdColor;
    }

    public boolean isPreventMisclicksEnabled() {
        return preventMisclicksEnabled;
    }

    public void setPreventMisclicksEnabled(boolean preventMisclicksEnabled) {
        this.preventMisclicksEnabled = preventMisclicksEnabled;
    }

    public boolean isAnnounceProgress() {
        return announceProgress;
    }

    public void setAnnounceProgress(boolean announceProgress) {
        this.announceProgress = announceProgress;
    }

    public boolean isPartyProgressTrackerEnabled() {
        return partyProgressTrackerEnabled;
    }

    public void setPartyProgressTrackerEnabled(boolean partyProgressTrackerEnabled) {
        this.partyProgressTrackerEnabled = partyProgressTrackerEnabled;
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - a real click-assist
     *  macro, same pattern as Auto Terminals. */
    public boolean isTriggerBotEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && triggerBotEnabled;
    }

    public void setTriggerBotEnabled(boolean triggerBotEnabled) {
        this.triggerBotEnabled = triggerBotEnabled;
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - full no-rotate
     *  auto-clicking through the whole sequence, a real macro. */
    public boolean isAutoSolveEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoSolveEnabled;
    }

    public void setAutoSolveEnabled(boolean autoSolveEnabled) {
        this.autoSolveEnabled = autoSolveEnabled;
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - auto-clicking the start
     *  button to set up a "skip", a real macro. */
    public boolean isAutoStartEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoStartEnabled;
    }

    public void setAutoStartEnabled(boolean autoStartEnabled) {
        this.autoStartEnabled = autoStartEnabled;
    }

    public SkipMode getAutoStartMode() {
        return autoStartMode;
    }

    public void setAutoStartMode(SkipMode autoStartMode) {
        this.autoStartMode = autoStartMode;
    }

    public int getSkipClicks(SkipMode mode) {
        return skipClickCounts.getOrDefault(mode, mode.defaultClicks);
    }

    public void setSkipClicks(SkipMode mode, int clicks) {
        skipClickCounts.put(mode, Math.max(1, Math.min(20, clicks)));
    }

    public int getAutoStartClickDelayMs() {
        return autoStartClickDelayMs;
    }

    public void setAutoStartClickDelayMs(int autoStartClickDelayMs) {
        this.autoStartClickDelayMs = Math.max(50, Math.min(2000, autoStartClickDelayMs));
    }

    public boolean isSkipCompatibility() {
        return skipCompatibility;
    }

    public void setSkipCompatibility(boolean skipCompatibility) {
        this.skipCompatibility = skipCompatibility;
    }

    public int getClickTimerTargetMs() {
        return clickTimerTargetMs;
    }

    public void setClickTimerTargetMs(int clickTimerTargetMs) {
        this.clickTimerTargetMs = Math.max(1000, Math.min(60_000, clickTimerTargetMs));
    }

    public int getClickTimerVarianceMs() {
        return clickTimerVarianceMs;
    }

    public void setClickTimerVarianceMs(int clickTimerVarianceMs) {
        this.clickTimerVarianceMs = Math.max(0, Math.min(5000, clickTimerVarianceMs));
    }

    public int getResetKeyCode() {
        return resetKeyCode;
    }

    public void setResetKeyCode(int resetKeyCode) {
        this.resetKeyCode = resetKeyCode;
    }

    public boolean isAutoSendResetMessage() {
        return autoSendResetMessage;
    }

    public void setAutoSendResetMessage(boolean autoSendResetMessage) {
        this.autoSendResetMessage = autoSendResetMessage;
    }

    public String getResetMessageText() {
        return resetMessageText;
    }

    public void setResetMessageText(String resetMessageText) {
        this.resetMessageText = resetMessageText;
    }

    public boolean isDiagnosticLoggingEnabled() {
        return diagnosticLoggingEnabled;
    }

    public void setDiagnosticLoggingEnabled(boolean diagnosticLoggingEnabled) {
        this.diagnosticLoggingEnabled = diagnosticLoggingEnabled;
    }

    public int getHorizontalRadius() {
        return horizontalRadius;
    }

    public void setHorizontalRadius(int horizontalRadius) {
        this.horizontalRadius = Math.max(2, Math.min(16, horizontalRadius));
    }

    public int getVerticalRadius() {
        return verticalRadius;
    }

    public void setVerticalRadius(int verticalRadius) {
        this.verticalRadius = Math.max(1, Math.min(10, verticalRadius));
    }
}
