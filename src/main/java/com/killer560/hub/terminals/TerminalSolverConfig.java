package com.killer560.hub.terminals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Terminal Solver settings - see {@link TerminalSolverFeature}. Ships disabled by default,
 *  same as every other new feature added going forward per killer560's standing instruction. Each
 *  terminal type has its own toggle so a type that isn't reliable yet can be turned off without
 *  disabling the whole feature. */
public final class TerminalSolverConfig {

    public static final float MIN_SCALE = 0.5f;
    // Bumped 0.5-2.0 -> 0.5-5.0 per killer560's explicit "let me put the scale up to a max of 500%"
    // request (2026-09-09).
    public static final float MAX_SCALE = 5.0f;

    // Auto Terminals (2026-09-09) - a real macro (auto-clicking), gated to the cheat build the same way
    // ExperimentsConfig#isAutonomousMode already is - see this class's own isAutoTerminalsEnabled() doc.
    public static final int MIN_AUTO_CLICK_DELAY_MS = 80;
    public static final int MAX_AUTO_CLICK_DELAY_MS = 500;
    public static final int MIN_MELODY_LOOKAHEAD = 0;
    public static final int MAX_MELODY_LOOKAHEAD = 4;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-terminalsolver.json");

    private static TerminalSolverConfig instance;

    private boolean enabled = false;
    private float scale = 1.0f;
    private boolean panesEnabled = true;
    private boolean rubixEnabled = true;
    private boolean numbersEnabled = true;
    private boolean startsWithEnabled = true;
    private boolean selectEnabled = true;
    // Per killer560's "there is no melody toggle in the terminals to solve box" report (2026-09-09,
    // round 11) - Melody used to be detected unconditionally (see TerminalSolverFeature#isTypeEnabled's
    // old hardcoded `true`), with no way to turn it off like every other type. Defaults to true so
    // existing behavior doesn't change for anyone who never touches this toggle.
    private boolean melodyEnabled = true;
    private boolean customGuiEnabled = false;
    // Per killer560's "have a setting where I can make it show the one I need to click, the one after
    // that, then one after that as well" request (2026-09-09, round 11) - extends Numbers' existing
    // 2-tier reveal (current + next) with an optional 3rd tier (next-after-that), off by default since
    // the 2-tier reveal is the one already confirmed working.
    private boolean numbersThreeTierReveal = false;

    // Auto Terminals (2026-09-09) - ported from NoammAddons' own AutoTerminal, cross-checked against
    // its real click-sending/timing logic (decompiled 2026-09-09) rather than guessed. Master toggle
    // gates every type below it, same collapse pattern Full Block's own master toggle uses.
    private boolean autoTerminalsEnabled = false;
    private boolean autoPanesEnabled = false;
    private boolean autoRubixEnabled = false;
    private boolean autoNumbersEnabled = false;
    private boolean autoStartsWithEnabled = false;
    private boolean autoSelectEnabled = false;
    private boolean autoMelodyEnabled = false;
    // Matches NoammAddons' own default (150ms) and range (80-500ms) - proven safe/fast for a real
    // time-limited terminal room.
    private int autoClickDelayMs = 150;
    // Per killer560's explicit "add a toggle on by default" (2026-09-09) - a stray real click/keypress
    // while the bot is mid-click could otherwise fight it, same real risk ExperimentsConfig's own
    // Block Input toggle protects against (that one defaults off; this one defaults ON per killer560's
    // explicit request here specifically).
    private boolean blockInputWhileAutoClicking = true;
    // Per killer560's explicit request (2026-09-09): "a configurable amount of first row clicks... 0-4
    // max" - when Melody's real-time detection finds a correct match at some row, this many TOTAL
    // consecutive rows (starting from that one) get clicked in one burst instead of just the one that
    // actually matched - e.g. 2 means click the matched row AND immediately also the next one down,
    // gambling that the same column repeats. 0 (default, safest) means no lookahead at all - only ever
    // clicks the row that's actually confirmed correct right now. See TerminalSolverFeature's own
    // Melody auto-click doc for the real mechanic this is built on.
    private int melodyLookaheadClicks = 0;

    private TerminalSolverConfig() {
    }

    public static TerminalSolverConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new TerminalSolverConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            TerminalSolverConfig cfg = new TerminalSolverConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.scale = obj.has("scale") ? clampScale(obj.get("scale").getAsFloat()) : 1.0f;
            cfg.panesEnabled = !obj.has("panesEnabled") || obj.get("panesEnabled").getAsBoolean();
            cfg.rubixEnabled = !obj.has("rubixEnabled") || obj.get("rubixEnabled").getAsBoolean();
            cfg.numbersEnabled = !obj.has("numbersEnabled") || obj.get("numbersEnabled").getAsBoolean();
            cfg.startsWithEnabled = !obj.has("startsWithEnabled") || obj.get("startsWithEnabled").getAsBoolean();
            cfg.selectEnabled = !obj.has("selectEnabled") || obj.get("selectEnabled").getAsBoolean();
            cfg.melodyEnabled = !obj.has("melodyEnabled") || obj.get("melodyEnabled").getAsBoolean();
            cfg.customGuiEnabled = obj.has("customGuiEnabled") && obj.get("customGuiEnabled").getAsBoolean();
            cfg.numbersThreeTierReveal = obj.has("numbersThreeTierReveal") && obj.get("numbersThreeTierReveal").getAsBoolean();
            cfg.autoTerminalsEnabled = obj.has("autoTerminalsEnabled") && obj.get("autoTerminalsEnabled").getAsBoolean();
            cfg.autoPanesEnabled = obj.has("autoPanesEnabled") && obj.get("autoPanesEnabled").getAsBoolean();
            cfg.autoRubixEnabled = obj.has("autoRubixEnabled") && obj.get("autoRubixEnabled").getAsBoolean();
            cfg.autoNumbersEnabled = obj.has("autoNumbersEnabled") && obj.get("autoNumbersEnabled").getAsBoolean();
            cfg.autoStartsWithEnabled = obj.has("autoStartsWithEnabled") && obj.get("autoStartsWithEnabled").getAsBoolean();
            cfg.autoSelectEnabled = obj.has("autoSelectEnabled") && obj.get("autoSelectEnabled").getAsBoolean();
            cfg.autoMelodyEnabled = obj.has("autoMelodyEnabled") && obj.get("autoMelodyEnabled").getAsBoolean();
            cfg.autoClickDelayMs = obj.has("autoClickDelayMs")
                    ? clampAutoClickDelay(obj.get("autoClickDelayMs").getAsInt()) : 150;
            cfg.blockInputWhileAutoClicking = !obj.has("blockInputWhileAutoClicking")
                    || obj.get("blockInputWhileAutoClicking").getAsBoolean();
            cfg.melodyLookaheadClicks = obj.has("melodyLookaheadClicks")
                    ? clampMelodyLookahead(obj.get("melodyLookaheadClicks").getAsInt()) : 0;
            instance = cfg;
        } catch (Exception e) {
            instance = new TerminalSolverConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("scale", scale);
            obj.addProperty("panesEnabled", panesEnabled);
            obj.addProperty("rubixEnabled", rubixEnabled);
            obj.addProperty("numbersEnabled", numbersEnabled);
            obj.addProperty("startsWithEnabled", startsWithEnabled);
            obj.addProperty("selectEnabled", selectEnabled);
            obj.addProperty("melodyEnabled", melodyEnabled);
            obj.addProperty("customGuiEnabled", customGuiEnabled);
            obj.addProperty("numbersThreeTierReveal", numbersThreeTierReveal);
            obj.addProperty("autoTerminalsEnabled", autoTerminalsEnabled);
            obj.addProperty("autoPanesEnabled", autoPanesEnabled);
            obj.addProperty("autoRubixEnabled", autoRubixEnabled);
            obj.addProperty("autoNumbersEnabled", autoNumbersEnabled);
            obj.addProperty("autoStartsWithEnabled", autoStartsWithEnabled);
            obj.addProperty("autoSelectEnabled", autoSelectEnabled);
            obj.addProperty("autoMelodyEnabled", autoMelodyEnabled);
            obj.addProperty("autoClickDelayMs", autoClickDelayMs);
            obj.addProperty("blockInputWhileAutoClicking", blockInputWhileAutoClicking);
            obj.addProperty("melodyLookaheadClicks", melodyLookaheadClicks);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static float clampScale(float value) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
    }

    private static int clampAutoClickDelay(int value) {
        return Math.max(MIN_AUTO_CLICK_DELAY_MS, Math.min(MAX_AUTO_CLICK_DELAY_MS, value));
    }

    private static int clampMelodyLookahead(int value) {
        return Math.max(MIN_MELODY_LOOKAHEAD, Math.min(MAX_MELODY_LOOKAHEAD, value));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public float getScale() {
        return scale;
    }

    public void setScale(float scale) {
        this.scale = clampScale(scale);
    }

    public boolean isPanesEnabled() {
        return panesEnabled;
    }

    public void setPanesEnabled(boolean panesEnabled) {
        this.panesEnabled = panesEnabled;
    }

    public boolean isRubixEnabled() {
        return rubixEnabled;
    }

    public void setRubixEnabled(boolean rubixEnabled) {
        this.rubixEnabled = rubixEnabled;
    }

    public boolean isNumbersEnabled() {
        return numbersEnabled;
    }

    public void setNumbersEnabled(boolean numbersEnabled) {
        this.numbersEnabled = numbersEnabled;
    }

    public boolean isStartsWithEnabled() {
        return startsWithEnabled;
    }

    public void setStartsWithEnabled(boolean startsWithEnabled) {
        this.startsWithEnabled = startsWithEnabled;
    }

    public boolean isSelectEnabled() {
        return selectEnabled;
    }

    public void setSelectEnabled(boolean selectEnabled) {
        this.selectEnabled = selectEnabled;
    }

    public boolean isMelodyEnabled() {
        return melodyEnabled;
    }

    public void setMelodyEnabled(boolean melodyEnabled) {
        this.melodyEnabled = melodyEnabled;
    }

    public boolean isCustomGuiEnabled() {
        return customGuiEnabled;
    }

    public void setCustomGuiEnabled(boolean customGuiEnabled) {
        this.customGuiEnabled = customGuiEnabled;
    }

    public boolean isNumbersThreeTierReveal() {
        return numbersThreeTierReveal;
    }

    public void setNumbersThreeTierReveal(boolean numbersThreeTierReveal) {
        this.numbersThreeTierReveal = numbersThreeTierReveal;
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED}, same real reason and same
     *  pattern as {@code ExperimentsConfig#isAutonomousMode} - auto-clicking terminals is a real macro,
     *  so the legit build can never run it even from a config.json copied over from a cheat install. */
    public boolean isAutoTerminalsEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoTerminalsEnabled;
    }

    public void setAutoTerminalsEnabled(boolean autoTerminalsEnabled) {
        this.autoTerminalsEnabled = autoTerminalsEnabled;
    }

    public boolean isAutoPanesEnabled() {
        return autoPanesEnabled;
    }

    public void setAutoPanesEnabled(boolean autoPanesEnabled) {
        this.autoPanesEnabled = autoPanesEnabled;
    }

    public boolean isAutoRubixEnabled() {
        return autoRubixEnabled;
    }

    public void setAutoRubixEnabled(boolean autoRubixEnabled) {
        this.autoRubixEnabled = autoRubixEnabled;
    }

    public boolean isAutoNumbersEnabled() {
        return autoNumbersEnabled;
    }

    public void setAutoNumbersEnabled(boolean autoNumbersEnabled) {
        this.autoNumbersEnabled = autoNumbersEnabled;
    }

    public boolean isAutoStartsWithEnabled() {
        return autoStartsWithEnabled;
    }

    public void setAutoStartsWithEnabled(boolean autoStartsWithEnabled) {
        this.autoStartsWithEnabled = autoStartsWithEnabled;
    }

    public boolean isAutoSelectEnabled() {
        return autoSelectEnabled;
    }

    public void setAutoSelectEnabled(boolean autoSelectEnabled) {
        this.autoSelectEnabled = autoSelectEnabled;
    }

    public boolean isAutoMelodyEnabled() {
        return autoMelodyEnabled;
    }

    public void setAutoMelodyEnabled(boolean autoMelodyEnabled) {
        this.autoMelodyEnabled = autoMelodyEnabled;
    }

    public int getAutoClickDelayMs() {
        return autoClickDelayMs;
    }

    public void setAutoClickDelayMs(int autoClickDelayMs) {
        this.autoClickDelayMs = clampAutoClickDelay(autoClickDelayMs);
    }

    public boolean isBlockInputWhileAutoClicking() {
        return blockInputWhileAutoClicking;
    }

    public void setBlockInputWhileAutoClicking(boolean blockInputWhileAutoClicking) {
        this.blockInputWhileAutoClicking = blockInputWhileAutoClicking;
    }

    public int getMelodyLookaheadClicks() {
        return melodyLookaheadClicks;
    }

    public void setMelodyLookaheadClicks(int melodyLookaheadClicks) {
        this.melodyLookaheadClicks = clampMelodyLookahead(melodyLookaheadClicks);
    }
}
