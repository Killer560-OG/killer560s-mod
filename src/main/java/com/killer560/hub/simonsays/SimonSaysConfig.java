package com.killer560.hub.simonsays;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Simon Says settings - see {@link SimonSaysFeature}. Ships disabled by default, same as
 *  every other new feature in this mod. */
public final class SimonSaysConfig {

    // Real default colors (green/orange/red) - exposed as public constants so the color-picker's own
    // "Set Default" button can reset to exactly these without duplicating the literals.
    public static final int DEFAULT_FIRST_COLOR = 0xFF55FF55;
    public static final int DEFAULT_SECOND_COLOR = 0xFFFFAA00;
    public static final int DEFAULT_THIRD_COLOR = 0xFFFF5555;

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
    private float numberScale = 1.0f;
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
    // Placeholder for a future feature killer560 described (2026-09-14): recording his own real manual
    // solves over many attempts to learn realistic camera movement, then replaying THAT instead of a
    // no-rotate synthetic click, for a much more "legit looking" auto solve. No recording/learning system
    // exists yet - this toggle is wired into the UI now (so the setting persists and has a home) but
    // "Rotate" currently behaves identically to "No Rotate" until that real system is actually built.
    private boolean autoSolveRotate = false;
    // Auto-solve's own pacing: land the WHOLE device attempt (all 5 rounds, 15 real clicks total) within
    // Target ± Variance overall (jittered), rather than a flat per-click delay. Moved here (2026-09-14)
    // from what used to be Auto Start's timer-with-variance model - killer560's own call, since Auto
    // Start's real trigger/pacing (see below, ported from NoammAddons) doesn't need a target window, but
    // Auto Solve pacing its own click-through speed is exactly "the spot where it will be used." Real bug
    // found and fixed the same day: this used to re-arm a fresh window every ROUND instead of once per
    // attempt, so the full target duration got spent on each round's handful of clicks alone (round 1 has
    // just ONE click) - killer560's own correct diagnosis: "extremely slow... thinking that is per stage
    // of it not overall."
    // Range narrowed to 11-13s (2026-09-14, killer560's own call after the Rotate Mode pacing fix): 11s is
    // about the fastest Rotate Mode can physically land a whole device, and 13s is the slowest Auto Solve
    // should ever be used at. See MIN_/MAX_CLICK_TIMER_TARGET_MS - the slider and the setter share them.
    public static final int MIN_CLICK_TIMER_TARGET_MS = 11_000;
    public static final int MAX_CLICK_TIMER_TARGET_MS = 13_000;
    private int clickTimerTargetMs = 12_800;
    // Max 250ms (2026-09-14, killer560's own call) - with the 11-13s target range, more jitter than that
    // just throws the result outside the window being aimed for.
    public static final int MAX_CLICK_TIMER_VARIANCE_MS = 250;
    private int clickTimerVarianceMs = 100;
    // Alternative pacing mode (2026-09-14, killer560's own request after seeing real log data showing
    // the Target/Variance model landing at a consistent but slow-feeling ~850ms/click): a flat, directly
    // controllable "ms between clicks" delay instead of an abstract overall-duration target. Off by
    // default so existing configs keep the Target/Variance behavior they already had.
    private boolean autoSolveFixedDelayMode = false;
    private int autoSolveFixedDelayMs = 150;

    private boolean autoStartEnabled = false;
    // Defaults ported from NoammAddons' own SimonSays.kt ("Start Clicks" default 3, "Start Click Delay"
    // in real TICKS default 3), and since CONFIRMED as real, correct values (2026-09-14) - killer560 did
    // several manual real skip attempts with the new onRealBlockInteractAttempt click logger running; the
    // two that actually got a real skip both showed the exact same real pattern: 3 total clicks on the
    // start button, ~150ms (~3 real ticks) apart. These are no longer just a ported guess. Range widened
    // to 0-20 (killer560's own explicit ask, both settings shown as drag sliders) - NoammAddons' own real
    // ranges were narrower (1-10 clicks, 1-25 ticks) but this mod's slider UI needed one shared 0-20 range.
    private int autoStartClicks = 3;
    private int autoStartClickDelayTicks = 3;
    // Auto Start's click mode is no longer its own setting (2026-09-14, killer560's own call) - it follows
    // Auto Solve's Mode instead: Rotate = turns the camera to the start button and only clicks once the
    // real crosshair is on it (look only), No Rotate = aura. See SimonSaysFeature#tickAutoStart. The old
    // "autoStartLookOnlyMode" JSON key is simply ignored on load and dropped on the next save.

    // Reset / announce
    // Renamed from "reset key" (2026-09-14) - killer560's own correction: this key no longer resets any
    // solve state itself (that's now fully automatic - see SimonSaysFeature's tickStartButton/
    // detectGridChanges, which already restart tracking on every real device reset without any manual
    // step). Pressing this key now ONLY sends the announce/reset chat line on demand, same text Auto
    // Message sends automatically. Kept the same "resetKeyCode" JSON property name so an existing
    // keybind carries over rather than silently resetting to "Not Set".
    private int announceKeyCode = -1;
    // killer560 (2026-09-14): "add a setting titled auto restart ss where if it detects ss failed then itll restart it
    // (follow the same aura vs looking logic for auto start) also add an option for manual restart where I set a
    // keybind and upon press itll restart it". Both cheat-only (they click the start button).
    private boolean autoRestartEnabled = false;
    private int restartKeyCode = -1;
    private boolean autoSendResetMessage = false;
    private String resetMessageText = "Resetting Simon Says";

    // Diagnostic chat-line/event logging still used by a handful of targeted LOGGER.info calls in
    // SimonSaysFeature (recorded steps, reveal-flash-settled, grid-reset-detected, auto-start clicks).
    // The separate full-radius "Log Block Changes" scanner that used to also gate off this same flag was
    // removed (2026-09-14, killer560's call - "no longer needed for our testing" now that the real
    // firstPhase/grid-reset bugs it was used to diagnose are both fixed). No UI toggle exposes this flag
    // anymore; it stays available to flip by hand in the config file for any future diagnosis.
    private boolean diagnosticLoggingEnabled = false;

    private SimonSaysConfig() {
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
            cfg.numberScale = obj.has("numberScale") ? obj.get("numberScale").getAsFloat() : 1.0f;
            cfg.firstColor = getInt(obj, "firstColor", 0xFF55FF55);
            cfg.secondColor = getInt(obj, "secondColor", 0xFFFFAA00);
            cfg.thirdColor = getInt(obj, "thirdColor", 0xFFFF5555);
            cfg.preventMisclicksEnabled = getBool(obj, "preventMisclicksEnabled", false);
            cfg.announceProgress = getBool(obj, "announceProgress", false);
            cfg.partyProgressTrackerEnabled = getBool(obj, "partyProgressTrackerEnabled", true);
            cfg.triggerBotEnabled = getBool(obj, "triggerBotEnabled", false);
            cfg.autoSolveEnabled = getBool(obj, "autoSolveEnabled", false);
            cfg.autoSolveRotate = getBool(obj, "autoSolveRotate", false);
            // Through the setter's clamp so a value saved under the old 1-60s range lands inside 11-13s.
            cfg.setClickTimerTargetMs(getInt(obj, "clickTimerTargetMs", 12_800));
            cfg.setClickTimerVarianceMs(getInt(obj, "clickTimerVarianceMs", 100));
            cfg.autoSolveFixedDelayMode = getBool(obj, "autoSolveFixedDelayMode", false);
            cfg.autoSolveFixedDelayMs = getInt(obj, "autoSolveFixedDelayMs", 150);
            cfg.autoStartEnabled = getBool(obj, "autoStartEnabled", false);
            cfg.autoStartClicks = getInt(obj, "autoStartClicks", 3);
            // Routed through the setter's own clamp (not a direct field assignment like the rest of this
            // method) so an old saved "0" from before the 0-tick option was removed (2026-09-14) gets
            // corrected to the new 1-20 range on load, instead of silently staying at 0 forever.
            cfg.setAutoStartClickDelayTicks(getInt(obj, "autoStartClickDelayTicks", 3));
            cfg.announceKeyCode = getInt(obj, "resetKeyCode", -1);
            cfg.autoRestartEnabled = getBool(obj, "autoRestartEnabled", false);
            cfg.restartKeyCode = getInt(obj, "restartKeyCode", -1);
            cfg.autoSendResetMessage = getBool(obj, "autoSendResetMessage", false);
            cfg.resetMessageText = obj.has("resetMessageText") ? obj.get("resetMessageText").getAsString() : "Resetting Simon Says";
            cfg.diagnosticLoggingEnabled = getBool(obj, "diagnosticLoggingEnabled", false);
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
            obj.addProperty("numberScale", numberScale);
            obj.addProperty("firstColor", firstColor);
            obj.addProperty("secondColor", secondColor);
            obj.addProperty("thirdColor", thirdColor);
            obj.addProperty("preventMisclicksEnabled", preventMisclicksEnabled);
            obj.addProperty("announceProgress", announceProgress);
            obj.addProperty("partyProgressTrackerEnabled", partyProgressTrackerEnabled);
            obj.addProperty("triggerBotEnabled", triggerBotEnabled);
            obj.addProperty("autoSolveEnabled", autoSolveEnabled);
            obj.addProperty("autoSolveRotate", autoSolveRotate);
            obj.addProperty("clickTimerTargetMs", clickTimerTargetMs);
            obj.addProperty("clickTimerVarianceMs", clickTimerVarianceMs);
            obj.addProperty("autoSolveFixedDelayMode", autoSolveFixedDelayMode);
            obj.addProperty("autoSolveFixedDelayMs", autoSolveFixedDelayMs);
            obj.addProperty("autoStartEnabled", autoStartEnabled);
            obj.addProperty("autoStartClicks", autoStartClicks);
            obj.addProperty("autoStartClickDelayTicks", autoStartClickDelayTicks);
            obj.addProperty("resetKeyCode", announceKeyCode);
            obj.addProperty("autoRestartEnabled", autoRestartEnabled);
            obj.addProperty("restartKeyCode", restartKeyCode);
            obj.addProperty("autoSendResetMessage", autoSendResetMessage);
            obj.addProperty("resetMessageText", resetMessageText);
            obj.addProperty("diagnosticLoggingEnabled", diagnosticLoggingEnabled);
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

    public float getNumberScale() {
        return numberScale;
    }

    public void setNumberScale(float numberScale) {
        this.numberScale = Math.max(0.25f, Math.min(3.0f, numberScale));
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

    public boolean isAutoSolveRotate() {
        return autoSolveRotate;
    }

    public void setAutoSolveRotate(boolean autoSolveRotate) {
        this.autoSolveRotate = autoSolveRotate;
    }

    public int getClickTimerTargetMs() {
        return clickTimerTargetMs;
    }

    public void setClickTimerTargetMs(int clickTimerTargetMs) {
        this.clickTimerTargetMs = Math.max(MIN_CLICK_TIMER_TARGET_MS, Math.min(MAX_CLICK_TIMER_TARGET_MS, clickTimerTargetMs));
    }

    public int getClickTimerVarianceMs() {
        return clickTimerVarianceMs;
    }

    public void setClickTimerVarianceMs(int clickTimerVarianceMs) {
        this.clickTimerVarianceMs = Math.max(0, Math.min(MAX_CLICK_TIMER_VARIANCE_MS, clickTimerVarianceMs));
    }

    public boolean isAutoSolveFixedDelayMode() {
        return autoSolveFixedDelayMode;
    }

    public void setAutoSolveFixedDelayMode(boolean autoSolveFixedDelayMode) {
        this.autoSolveFixedDelayMode = autoSolveFixedDelayMode;
    }

    public int getAutoSolveFixedDelayMs() {
        return autoSolveFixedDelayMs;
    }

    public void setAutoSolveFixedDelayMs(int autoSolveFixedDelayMs) {
        this.autoSolveFixedDelayMs = Math.max(0, Math.min(3000, autoSolveFixedDelayMs));
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - auto-clicking the start
     *  button to set up a "skip", a real macro. Real trigger: SimonSaysFeature fires this itself the
     *  moment the real Goldor phase-start line is seen (ported from NoammAddons), not from a GUI button. */
    public boolean isAutoStartEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoStartEnabled;
    }

    public void setAutoStartEnabled(boolean autoStartEnabled) {
        this.autoStartEnabled = autoStartEnabled;
    }

    public int getAutoStartClicks() {
        return autoStartClicks;
    }

    public void setAutoStartClicks(int autoStartClicks) {
        this.autoStartClicks = Math.max(0, Math.min(20, autoStartClicks));
    }

    public int getAutoStartClickDelayTicks() {
        return autoStartClickDelayTicks;
    }

    public void setAutoStartClickDelayTicks(int autoStartClickDelayTicks) {
        // Min 1, not 0 (2026-09-14, killer560's own call) - 0 ticks between clicks means every click
        // fires on the same tick, which isn't a real "delay" option at all.
        this.autoStartClickDelayTicks = Math.max(1, Math.min(20, autoStartClickDelayTicks));
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - clicks the start button. */
    public boolean isAutoRestartEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoRestartEnabled;
    }

    public boolean getAutoRestartRaw() {
        return autoRestartEnabled;
    }

    public void setAutoRestartEnabled(boolean autoRestartEnabled) {
        this.autoRestartEnabled = autoRestartEnabled;
    }

    /** -1 when unset, or on the legit build (cheat-gated like the other start-button clicks). */
    public int getRestartKeyCode() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED ? restartKeyCode : -1;
    }

    public void setRestartKeyCode(int restartKeyCode) {
        this.restartKeyCode = restartKeyCode;
    }

    public int getAnnounceKeyCode() {
        return announceKeyCode;
    }

    public void setAnnounceKeyCode(int announceKeyCode) {
        this.announceKeyCode = announceKeyCode;
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
}
