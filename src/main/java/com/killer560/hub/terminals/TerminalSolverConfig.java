package com.killer560.hub.terminals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

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

    // ---- Overlay colours (2026-09-15, killer560: "add the option to set custom colors for terminal
    // overlays") ----
    /** Alpha floor applied to every overlay colour except {@link OverlayColor#PANEL_BACKGROUND} (which is
     *  forced opaque instead). The picker exposes a real alpha slider - translucent highlights are a
     *  legitimate choice and are NOT clamped up to opaque - but a colour dragged all the way to alpha 0
     *  would draw literally nothing, which reads as "the solver broke" rather than as a colour setting.
     *  Turning a highlight off is what the per-type toggles above are for, so the floor keeps a colour
     *  always at least faintly visible. */
    public static final int MIN_OVERLAY_ALPHA = 0x10;

    /** Every colour the terminal overlays actually draw with. Each constant's {@code defaultArgb} is
     *  EXACTLY the value that used to be hardcoded in {@link TerminalSolverFeature} (THEME_ORANGE /
     *  BRIGHT_ORANGE / MUTED_ORANGE / FAINT_ORANGE / PANEL_BG_COLOR / the Melody palette / ...), so
     *  nothing changes visually until the user actually edits one.
     *  <p>
     *  Roles that carry real meaning stay separate constants on purpose rather than being collapsed into
     *  one "highlight colour": Numbers' next vs after-next vs 3rd tier is the whole point of that reveal,
     *  Rubix's left-click vs right-click tells you which mouse button to use, and Melody's endpoint /
     *  moving-piece / button / track roles are what make its board readable at a glance. */
    public enum OverlayColor {
        PANES("colorPanes", "Panes Colour", 0xFFFFA500, false),
        NUMBERS_NEXT("colorNumbersNext", "Numbers Next Colour", 0xFFFFA500, false),
        NUMBERS_AFTER_NEXT("colorNumbersAfterNext", "Numbers After Next Colour", 0xFFB37744, false),
        NUMBERS_THIRD("colorNumbersThird", "Numbers 3rd Colour", 0xFF4D3319, false),
        STARTS_WITH("colorStartsWith", "Starts With Colour", 0xFFFFA500, false),
        SELECT("colorSelect", "Select All Colour", 0xFFFFA500, false),
        RUBIX_LEFT_CLICK("colorRubixLeftClick", "Rubix Left Click Colour", 0xFFFF8C00, false),
        RUBIX_RIGHT_CLICK("colorRubixRightClick", "Rubix Right Click Colour", 0xFF3399FF, false),
        MELODY_ENDPOINT("colorMelodyEndpoint", "Melody Endpoint Colour", 0xFFFFA500, false),
        MELODY_MOVING("colorMelodyMoving", "Melody Moving Piece Colour", 0xFFFFA500, false),
        MELODY_BUTTON("colorMelodyButton", "Melody Button Colour", 0xFFFFA500, false),
        MELODY_TRACK("colorMelodyTrack", "Melody Track Colour", 0xFFCDA775, false),
        /** Forced fully opaque on both load and set - a translucent panel fill can never fully hide the
         *  real screen still being drawn underneath it, which is exactly the "Inactive Terminal"/"CLICK
         *  HERE" ghost-text bleed-through killer560 screenshotted in round 10 (the old constant had
         *  already been bumped from 0xEE to 0xFF for that reason). The alpha slider is therefore a no-op
         *  for this one entry. */
        PANEL_BACKGROUND("colorPanelBackground", "Panel Background Colour", 0xFF241206, true),
        PANEL_BORDER("colorPanelBorder", "Panel Border Colour", 0xFFFFA500, false),
        /** The little per-slot label drawn over the vanilla (non-Custom-GUI) overlay. */
        LABEL_TEXT("colorLabelText", "Label Text Colour", 0xFFFFFFFF, false),
        /** Rubix's click-count number, drawn centred inside its filled Custom GUI cell. */
        RUBIX_COUNT_TEXT("colorRubixCountText", "Rubix Count Text Colour", 0xFF000000, false);

        private final String key;
        private final String label;
        private final int defaultArgb;
        private final boolean forceOpaque;

        OverlayColor(String key, String label, int defaultArgb, boolean forceOpaque) {
            this.key = key;
            this.label = label;
            this.defaultArgb = defaultArgb;
            this.forceOpaque = forceOpaque;
        }

        /** JSON key in {@code killer560smod-terminalsolver.json}. */
        public String key() {
            return key;
        }

        /** Button label in the Terminal Solver tab. Every one ends in "Colour" so its lower-cased form
         *  stays a unique {@code SettingTooltipsData} key - bare "Panes"/"Starts With"/"Select" are
         *  already taken by this same tab's per-type solve toggles. */
        public String label() {
            return label;
        }

        public int defaultArgb() {
            return defaultArgb;
        }

        /** See {@link TerminalSolverConfig#MIN_OVERLAY_ALPHA} and {@link #PANEL_BACKGROUND}. */
        public int sanitize(int argb) {
            if (forceOpaque) {
                return argb | 0xFF000000;
            }
            int alpha = (argb >>> 24) & 0xFF;
            return alpha >= MIN_OVERLAY_ALPHA ? argb : (MIN_OVERLAY_ALPHA << 24) | (argb & 0x00FFFFFF);
        }
    }


    private static final Random RANDOM = new Random();
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
    // Per killer560's explicit "give it a min and max delay and randomly pick between the two" request
    // (2026-09-09) - matches NoammAddons' own real Random Delay feature (min/max, picks a fresh random
    // value in that range per click) rather than one fixed interval. Defaults match NoammAddons' own
    // proven-safe range.
    private int autoClickMinDelayMs = 120;
    private int autoClickMaxDelayMs = 200;
    // Per killer560's explicit "add a toggle on by default" (2026-09-09) - a stray real click/keypress
    // while the bot is mid-click could otherwise fight it, same real risk ExperimentsConfig's own
    // Block Input toggle protects against (that one defaults off; this one defaults ON per killer560's
    // explicit request here specifically).
    private boolean blockInputWhileAutoClicking = true;
    // Killer560's own explicit request (2026-09-14): "it should send a clientside message of 'numbers
    // took 1.8s to complete' ect." Defaults on, matching that request being a definite ask, not
    // conditional.
    private boolean announceCompletionTime = true;
    // Per killer560's explicit request (2026-09-09): "a configurable amount of first row clicks... 0-4
    // max" - when Melody's real-time detection finds a correct match at some row, this many TOTAL
    // consecutive rows (starting from that one) get clicked in one burst instead of just the one that
    // actually matched - e.g. 2 means click the matched row AND immediately also the next one down,
    // gambling that the same column repeats. 0 (default, safest) means no lookahead at all - only ever
    // clicks the row that's actually confirmed correct right now. See TerminalSolverFeature's own
    // Melody auto-click doc for the real mechanic this is built on.
    private int melodyLookaheadClicks = 0;
    // Per killer560's explicit "add a skip on edges or skip on all section" request (2026-09-10) - gates
    // WHEN the lookahead burst above is allowed to fire. EDGES (default, safer) only allows it when the
    // real match happens at row 0 or the last row - the two physical ends of the track, matching the
    // original "very first spot of the very first row" request; middle rows just click live, one at a
    // time. ALL fires the burst from a match at ANY row and - per killer560's explicit "click through the
    // whole row anytime it gets a proper click" - ignores melodyLookaheadClicks entirely and bursts every
    // remaining row down to the last one, not just a capped number of them.
    private MelodySkipMode melodySkipMode = MelodySkipMode.EDGES;
    /** Never null and always fully populated - see the constructor. */
    private final Map<OverlayColor, Integer> overlayColors = new EnumMap<>(OverlayColor.class);

    /** See {@link #melodySkipMode}'s own doc. */
    public enum MelodySkipMode {
        ALL, EDGES
    }

    private TerminalSolverConfig() {
        for (OverlayColor c : OverlayColor.values()) {
            overlayColors.put(c, c.defaultArgb());
        }
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
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.scale = clampScale(ConfigJson.getFloat(obj, "scale", 1.0f));
            cfg.panesEnabled = ConfigJson.getBool(obj, "panesEnabled", true);
            cfg.rubixEnabled = ConfigJson.getBool(obj, "rubixEnabled", true);
            cfg.numbersEnabled = ConfigJson.getBool(obj, "numbersEnabled", true);
            cfg.startsWithEnabled = ConfigJson.getBool(obj, "startsWithEnabled", true);
            cfg.selectEnabled = ConfigJson.getBool(obj, "selectEnabled", true);
            cfg.melodyEnabled = ConfigJson.getBool(obj, "melodyEnabled", true);
            cfg.customGuiEnabled = ConfigJson.getBool(obj, "customGuiEnabled", false);
            cfg.numbersThreeTierReveal = ConfigJson.getBool(obj, "numbersThreeTierReveal", false);
            cfg.autoTerminalsEnabled = ConfigJson.getBool(obj, "autoTerminalsEnabled", false);
            cfg.autoPanesEnabled = ConfigJson.getBool(obj, "autoPanesEnabled", false);
            cfg.autoRubixEnabled = ConfigJson.getBool(obj, "autoRubixEnabled", false);
            cfg.autoNumbersEnabled = ConfigJson.getBool(obj, "autoNumbersEnabled", false);
            cfg.autoStartsWithEnabled = ConfigJson.getBool(obj, "autoStartsWithEnabled", false);
            cfg.autoSelectEnabled = ConfigJson.getBool(obj, "autoSelectEnabled", false);
            cfg.autoMelodyEnabled = ConfigJson.getBool(obj, "autoMelodyEnabled", false);
            cfg.autoClickMinDelayMs = clampAutoClickDelay(ConfigJson.getInt(obj, "autoClickMinDelayMs", 120));
            cfg.autoClickMaxDelayMs = clampAutoClickDelay(ConfigJson.getInt(obj, "autoClickMaxDelayMs", 200));
            // Same min <= max invariant the setters enforce (a hand-edited file could otherwise load min > max).
            if (cfg.autoClickMaxDelayMs < cfg.autoClickMinDelayMs) {
                cfg.autoClickMaxDelayMs = cfg.autoClickMinDelayMs;
            }
            cfg.blockInputWhileAutoClicking = ConfigJson.getBool(obj, "blockInputWhileAutoClicking", true);
            cfg.announceCompletionTime = ConfigJson.getBool(obj, "announceCompletionTime", true);
            cfg.melodyLookaheadClicks = clampMelodyLookahead(ConfigJson.getInt(obj, "melodyLookaheadClicks", 0));
            cfg.melodySkipMode = ConfigJson.getEnum(obj, "melodySkipMode", MelodySkipMode.class, MelodySkipMode.EDGES);
            // Per-key reads (ConfigJson) so one bad/missing colour falls back to just that colour's
            // default instead of resetting every other setting in the file.
            for (OverlayColor c : OverlayColor.values()) {
                cfg.overlayColors.put(c, c.sanitize(ConfigJson.getInt(obj, c.key(), c.defaultArgb())));
            }
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
            obj.addProperty("autoClickMinDelayMs", autoClickMinDelayMs);
            obj.addProperty("autoClickMaxDelayMs", autoClickMaxDelayMs);
            obj.addProperty("blockInputWhileAutoClicking", blockInputWhileAutoClicking);
            obj.addProperty("announceCompletionTime", announceCompletionTime);
            obj.addProperty("melodyLookaheadClicks", melodyLookaheadClicks);
            obj.addProperty("melodySkipMode", melodySkipMode.name());
            for (OverlayColor c : OverlayColor.values()) {
                obj.addProperty(c.key(), getOverlayColor(c));
            }
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
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
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
        return customGuiEnabled && com.killer560.hub.util.SkyblockGate.allows();
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
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoTerminalsEnabled && com.killer560.hub.util.SkyblockGate.allows();
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

    public int getAutoClickMinDelayMs() {
        return autoClickMinDelayMs;
    }

    /** Keeps min &lt;= max by clamping the OTHER bound too if this push would cross it - a slider that
     *  can only ever move one bound at a time otherwise has no way to stop min from being dragged past
     *  whatever max currently is (or vice versa). */
    public void setAutoClickMinDelayMs(int autoClickMinDelayMs) {
        this.autoClickMinDelayMs = clampAutoClickDelay(autoClickMinDelayMs);
        if (this.autoClickMinDelayMs > this.autoClickMaxDelayMs) {
            this.autoClickMaxDelayMs = this.autoClickMinDelayMs;
        }
    }

    public int getAutoClickMaxDelayMs() {
        return autoClickMaxDelayMs;
    }

    public void setAutoClickMaxDelayMs(int autoClickMaxDelayMs) {
        this.autoClickMaxDelayMs = clampAutoClickDelay(autoClickMaxDelayMs);
        if (this.autoClickMaxDelayMs < this.autoClickMinDelayMs) {
            this.autoClickMinDelayMs = this.autoClickMaxDelayMs;
        }
    }

    /** @return a fresh random delay in [min, max] - per killer560's explicit "randomly pick between the
     *  two" request, a new value each time this is called (i.e. once per click), not a value fixed for
     *  the whole session. */
    public int rollAutoClickDelayMs() {
        if (autoClickMinDelayMs >= autoClickMaxDelayMs) {
            return autoClickMinDelayMs;
        }
        return autoClickMinDelayMs + RANDOM.nextInt(autoClickMaxDelayMs - autoClickMinDelayMs + 1);
    }

    public boolean isBlockInputWhileAutoClicking() {
        return blockInputWhileAutoClicking;
    }

    public boolean isAnnounceCompletionTime() {
        return announceCompletionTime;
    }

    public void setAnnounceCompletionTime(boolean announceCompletionTime) {
        this.announceCompletionTime = announceCompletionTime;
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

    public MelodySkipMode getMelodySkipMode() {
        return melodySkipMode;
    }

    public void setMelodySkipMode(MelodySkipMode melodySkipMode) {
        this.melodySkipMode = melodySkipMode != null ? melodySkipMode : MelodySkipMode.EDGES;
    }

    /** @return the live ARGB for {@code key}, already sanitized (see {@link OverlayColor#sanitize}).
     *  Falls back to the default if the map somehow has no entry, so a draw call can never NPE. */
    public int getOverlayColor(OverlayColor key) {
        if (key == null) {
            return 0xFFFFFFFF;
        }
        Integer v = overlayColors.get(key);
        return v == null ? key.defaultArgb() : v;
    }

    public void setOverlayColor(OverlayColor key, int argb) {
        if (key != null) {
            overlayColors.put(key, key.sanitize(argb));
        }
    }

    /** "Reset Colours" button - puts every overlay colour back to the value it shipped with. Callers
     *  still have to {@link #save()} (same as every other setter here). */
    public void resetOverlayColors() {
        for (OverlayColor c : OverlayColor.values()) {
            overlayColors.put(c, c.defaultArgb());
        }
    }

    /** @return true if every overlay colour is still its shipped default - drives the Reset button's
     *  own enabled/disabled look in {@code TerminalSolverTab}. */
    public boolean isOverlayColorsDefault() {
        for (OverlayColor c : OverlayColor.values()) {
            if (getOverlayColor(c) != c.defaultArgb()) {
                return false;
            }
        }
        return true;
    }

    /** Shorthand for the render code - {@code TerminalSolverConfig.color(OverlayColor.PANES)}. */
    public static int color(OverlayColor key) {
        return getInstance().getOverlayColor(key);
    }
}
