package com.killer560.hub.fastleap;

import com.killer560.hub.gui.CommitEditBox;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.fastleap.FastLeapConfig.LeapGroup;
import com.killer560.hub.fastleap.FastLeapConfig.LeapTarget;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * Settings UI for {@link FastLeapFeature}, drawn by {@code FastLeapTab} ("Fast/Auto Leap").
 * <p>
 * Three pages since 2026-11-02 (killer560: "first keep the main toggle. then have two buttons. one for universal and one
 * to set class. if you click universal then it takes you to the universal page if it is set class then set class page.
 * you should have a toggle to use universal or to use set class. right now everything feels very hard to read"):
 * <ul>
 *   <li>{@link #buildGeneral} - the front page: the Fast Leap switch, Targets (Universal / Set Class), the two page
 *       buttons (the one in use lit orange and marked "(in use)"), then the settings both modes share: Click
 *       Behavior ({@code each leap's Off / Fast / Auto and Target Mode are on the two pages below}).</li>
 *   <li>{@link #buildUniversalTargets} - who each leap goes to for everyone, per boss phase, with Posmsg's keywords.</li>
 *   <li>{@link #buildClassTargets} - the same list for the class picked at its top ("When You Play").</li>
 * </ul>
 * Every page starts with the Fast Leap switch (the card's switch reads it), and a sub-page then has "&lt; Back". Which page
 * shows is view state here ({@link #page}), not a separate screen, so the menu's search, scrolling and drawer keep
 * working. Every row is one idea at full width ({@link #ROW} high, {@link #GAP} between), except a leap's line on the
 * front page (its name, Fast, Auto) and the two page buttons, which the menu stacks in a narrow drawer. Button labels
 * stay "Label: value" - {@code SettingTooltips} looks descriptions up by the text before the colon.
 */
public final class FastLeapSection {

    /** Shared row metrics for the leap tabs. */
    public static final int ROW = 18;
    public static final int GAP = 4;
    public static final int LABEL_W = 72;
    /** Indent of a leap's sub-settings under its row. */
    public static final int INDENT = 10;

    private static final int GROUP_TITLE_H = 12;
    private static final int GROUP_GAP = 8;
    /** Height reserved for a red {@link #sectionHeader}, matching the header+16 convention SimonSaysTab uses. */
    private static final int SECTION_HEADER_H = 16;
    /** Narrowest page button that still fits "Set Class Targets (in use) &gt;". */
    private static final int MIN_PAGE_W = 150;
    private static final int BACK_W = 56;

    /** The three pages of the tab. */
    public enum Page {
        FRONT, UNIVERSAL, CLASS
    }

    /** The page the tab shows; view state only, not saved. */
    private static Page page = Page.FRONT;

    /** The class the Set Class page edits; view state only (null until the page first opens: your class, else Mage). */
    private static DungeonClass editingClass = null;

    private FastLeapSection() {
    }

    public static Page page() {
        return page;
    }

    public static void openPage(Page p) {
        page = p == null ? Page.FRONT : p;
    }

    /** @return the raw Fast Leap master switch, i.e. whether the rest of the page should be shown. */
    public static boolean isEnabledSetting() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && FastLeapConfig.getInstance().isEnabledSetting();
    }

    public static int buildGeneral(List<AbstractWidget> widgets, int x, int y, int width, Runnable requestRebuild) {
        return buildGeneral(widgets, x, y, width, requestRebuild, false);
    }

    /**
     * The front page : the switch, Targets, the two page buttons, then Click
     * Behavior.
     * @param includeHidden true also draws Fast Mode while Block Inputs is off - only for the tab's off-screen search
     *  scan, so searching "fast mode" still finds it.
     * @return the y below the last row.
     */
    public static int buildGeneral(List<AbstractWidget> widgets, int x, int y, int width, Runnable requestRebuild,
                                   boolean includeHidden) {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return y;
        }
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        int w = Math.max(1, width);
        y = buildSwitch(widgets, x, y, w, requestRebuild, cfg);
        if (!cfg.isEnabledSetting() && !includeHidden) {
            widgets.add(titleLabel(x, y, w, "§7Turn Fast Leap on to set up your leaps."));
            return y + GROUP_TITLE_H;
        }

        // Targets: which of the two pages the leaps use. Universal for everyone, or Set Class for the class you play.
        widgets.add(SettingsButtonWidget.cycling(targetsLabel(cfg), (btn, dir) -> {
            cfg.setUseClassTargets(!cfg.isUseClassTargets());
            cfg.save();
            requestRebuild.run();
        }).bounds(x, y, w, ROW).build());
        y += ROW + GAP;

        // The two pages, side by side (stacked by the menu in a narrow drawer); the one in use is lit and says so.
        int cols = fitCols(w, 2, MIN_PAGE_W);
        widgets.add(pageButton(cellX(x, w, cols, 0), y, cellW(x, w, cols, 0), "Universal Targets", !cfg.isUseClassTargets(),
                Page.UNIVERSAL, requestRebuild));
        if (cols == 1) {
            y += ROW + GAP;
        }
        int c2 = cols == 1 ? 0 : 1;
        widgets.add(pageButton(cellX(x, w, cols, c2), y, cellW(x, w, cols, c2), "Set Class Targets", cfg.isUseClassTargets(),
                Page.CLASS, requestRebuild));
        y += ROW + GAP;

        y += GROUP_GAP - GAP;
        return buildClickBehavior(widgets, x, y, w, includeHidden) + GROUP_GAP - GAP;
    }

    /** Click Behavior, the front page's second section: what happens while a leap is clicking the menu. One row each. */
    public static int buildClickBehavior(List<AbstractWidget> widgets, int x, int y, int width, boolean includeHidden) {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return y;
        }
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        int w = Math.max(1, width);
        widgets.add(sectionHeader(x, y, w, "Click Behavior"));
        y += SECTION_HEADER_H;
        widgets.add(toggle(x, y, w, "Block Inputs", cfg::isBlockInputs, v -> cfg.setBlockInputs(v), cfg));
        y += ROW + GAP;
        // Fast Mode only changes what Block Inputs does, so it sits under it, indented, while Block Inputs is on.
        if (cfg.isBlockInputs() || includeHidden) {
            widgets.add(toggle(x + INDENT, y, Math.max(1, w - INDENT), "Fast Mode", cfg::isFastMode, v -> cfg.setFastMode(v), cfg));
            y += ROW + GAP;
        }
        double norm = (cfg.getClickDelayMs() - FastLeapConfig.MIN_CLICK_DELAY_MS)
                / (double) (FastLeapConfig.MAX_CLICK_DELAY_MS - FastLeapConfig.MIN_CLICK_DELAY_MS);
        widgets.add(rangeSlider(x, y, w, cfg, norm, () -> delayLabel(cfg), FastLeapConfig.MIN_CLICK_DELAY_MS,
                FastLeapConfig.MAX_CLICK_DELAY_MS, cfg::setClickDelayMs));
        y += ROW + GAP;
        // Leap Timeout (killer560, 2026-11-01): how long a leap waits for the menu / the target's head before giving up
        double tnorm = (cfg.getTimeoutMs() - FastLeapConfig.MIN_TIMEOUT_MS)
                / (double) (FastLeapConfig.MAX_TIMEOUT_MS - FastLeapConfig.MIN_TIMEOUT_MS);
        widgets.add(rangeSlider(x, y, w, cfg, tnorm, () -> timeoutLabel(cfg), FastLeapConfig.MIN_TIMEOUT_MS,
                FastLeapConfig.MAX_TIMEOUT_MS, cfg::setTimeoutMs));
        y += ROW + GAP;
        // Return To (2026-10-13): one setting for every Auto leap - where to swap back is a property of his hotbar
        widgets.add(SettingsButtonWidget.cycling(returnLabel(cfg.getReturnToSlot()), (btn, dir) -> {
            cfg.setReturnToSlot(stepReturn(cfg.getReturnToSlot(), dir));
            cfg.save();
            btn.setMessage(returnLabel(cfg.getReturnToSlot()));
        }).bounds(x, y, w, ROW).build());
        y += ROW + GAP;
        return y;
    }

    /** The Fast Leap switch, every page's first row (the card's switch reads it). Turning it off goes back to the front. */
    private static int buildSwitch(List<AbstractWidget> widgets, int x, int y, int w, Runnable requestRebuild,
                                   FastLeapConfig cfg) {
        widgets.add(SettingsButtonWidget.builder(onOff("Fast Leap", cfg.isEnabledSetting()), btn -> {
            cfg.setEnabled(!cfg.isEnabledSetting());
            cfg.save();
            if (!cfg.isEnabledSetting()) {
                page = Page.FRONT;
            }
            requestRebuild.run();
        }).bounds(x, y, w, ROW).build());
        return y + ROW + GAP;
    }

    static Component targetsLabel(FastLeapConfig cfg) {
        return Component.literal("Targets: §6" + (cfg.isUseClassTargets() ? "Set Class" : "Universal"));
    }

    /** A page button: lit orange with "(in use)" for the page the leaps use, dim for the other. */
    private static SettingsButtonWidget pageButton(int x, int y, int w, String title, boolean inUse, Page to,
                                                   Runnable requestRebuild) {
        SettingsButtonWidget.Builder b = SettingsButtonWidget.builder(
                Component.literal(inUse ? title + " (in use) >" : "§7" + title + " >"), btn -> {
                    page = to;
                    requestRebuild.run();
                }).bounds(x, y, Math.max(1, w), ROW);
        if (inUse) {
            b.primary();
        }
        return b.build();
    }

    static Component returnLabel(int slot) {
        return Component.literal("Return To: §6" + (slot == FastLeapConfig.RETURN_PREVIOUS ? "Previous Slot"
                : slot == FastLeapConfig.RETURN_STAY ? "Stay on Leap" : "Slot " + (slot + 1)));
    }

    /** Previous Slot, Stay on Leap, Slot 1 ... Slot 9, wrapping; dir 1 forward, -1 back. */
    static int stepReturn(int slot, int dir) {
        int i = slot == FastLeapConfig.RETURN_PREVIOUS ? 0 : slot == FastLeapConfig.RETURN_STAY ? 1 : slot + 2;
        int n = Math.floorMod(i + (dir < 0 ? -1 : 1), 11);
        return n == 0 ? FastLeapConfig.RETURN_PREVIOUS : n == 1 ? FastLeapConfig.RETURN_STAY : n - 2;
    }

    // ---- the leaps, by boss phase -------------------------------------------------------------------------------

    /** One boss-phase bucket: a caption plus the leaps under it. Door Opener stands alone because it fires outside the
     *  boss at any door; Predev sits with Crystal (P1) because it starts there. */
    private record LeapPhase(String caption, LeapGroup... groups) {
    }

    private static final LeapPhase[] PHASES = {
            new LeapPhase("Clear", LeapGroup.DOOR),
            new LeapPhase("P1 Maxor", LeapGroup.P1, LeapGroup.PREDEV),
            new LeapPhase("P2 Storm", LeapGroup.GREEN, LeapGroup.YELLOW, LeapGroup.PURPLE, LeapGroup.PY_HEALER, LeapGroup.STORM_DEATH),
            new LeapPhase("P3 Goldor", LeapGroup.P3),
            new LeapPhase("P4 Necron", LeapGroup.MIDDLE, LeapGroup.P4),
            new LeapPhase("P5", LeapGroup.RELIC),
    };

    static Component leapSwitchLabel(LeapGroup group, FastLeapConfig cfg) {
        FastLeapConfig.LeapMode m = cfg.getLeapMode(group);
        String colour = m == FastLeapConfig.LeapMode.AUTO ? "§a" : m == FastLeapConfig.LeapMode.FAST ? "§6" : "§7";
        return Component.literal(group.label + " Leap: " + colour + m.label);
    }

    // ---- sub-pages ----------------------------------------------------------------------------------------------

    /** A sub-page's top: the Fast Leap switch, then "&lt; Back" with the page's title and whether the leaps use it. */
    public static int buildPageHeader(List<AbstractWidget> widgets, int x, int y, int width, Runnable requestRebuild,
                                      Page which) {
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        int w = Math.max(1, width);
        y = buildSwitch(widgets, x, y, w, requestRebuild, cfg);
        widgets.add(SettingsButtonWidget.builder(Component.literal("< Back"), btn -> {
            page = Page.FRONT;
            requestRebuild.run();
        }).bounds(x, y + 1, BACK_W, ROW - 2).build());
        boolean inUse = (which == Page.CLASS) == cfg.isUseClassTargets();
        String title = which == Page.CLASS ? "Set Class Targets" : "Universal Targets";
        widgets.add(textAt(x + BACK_W + 8, y + 5, w - BACK_W - 8,
                "§f" + title + (inUse ? " §a(in use)" : " §7(not in use)")));
        y += ROW + GAP + 2;
        return y;
    }

    /**
     * The Universal Targets page: for each boss phase, each leap's own rows (killer560, 2026-11-09: "you should set it in
     * universal or class specific", "target mode should be inside of each leap option"): the leap's Off / Fast / Auto, then
     * its target and Target Mode side by side (P3's S1..S4 each), with Posmsg's keyword box under a leap in Posmsg mode.
     * @param includeHidden true draws the keyword boxes outside Posmsg too (search scan only).
     */
    public static int buildUniversalTargets(List<AbstractWidget> widgets, int x, int y, int width, boolean includeHidden) {
        return buildUniversalTargets(widgets, x, y, width, includeHidden, () -> { });
    }

    public static int buildUniversalTargets(List<AbstractWidget> widgets, int x, int y, int width, boolean includeHidden,
                                            Runnable requestRebuild) {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return y;
        }
        int w = Math.max(1, width);
        widgets.add(titleLabel(x, y, w, "§7Who each leap goes to, whatever class you play."));
        return phaseRows(widgets, x, y + GROUP_TITLE_H, w, null, includeHidden, requestRebuild);
    }

    /**
     * The Set Class Targets page: "When You Play" picks one of your classes, then the same rows as the Universal page
     * with that class's own target ("Same as Universal" until one is set). A leap's Off / Fast / Auto and Target Mode are
     * the leap's own, the same on both pages.
     */
    public static int buildClassTargets(List<AbstractWidget> widgets, int x, int y, int width, Runnable requestRebuild) {
        return buildClassTargets(widgets, x, y, width, requestRebuild, false);
    }

    /** @param includeHidden true for the tab's off-screen search scan (no column heads). */
    public static int buildClassTargets(List<AbstractWidget> widgets, int x, int y, int width, Runnable requestRebuild,
                                        boolean includeHidden) {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return y;
        }
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        int w = Math.max(1, width);
        DungeonClass mine = editingClass();
        widgets.add(SettingsButtonWidget.cycling(Component.literal("When You Play: " + DungeonClass.colourCode(mine)
                + mine.displayName()), (btn, dir) -> {
            editingClass = SettingsButtonWidget.step(editingClass(), dir);
            requestRebuild.run();
        }).bounds(x, y, w, ROW).build());
        y += ROW + GAP;
        int n = cfg.classTargetCount();
        widgets.add(titleLabel(x, y, w, "§7Universal: uses the Universal target. "
                + n + (n == 1 ? " target" : " targets") + " set in all."));
        return phaseRows(widgets, x, y + GROUP_TITLE_H, w, mine, includeHidden, requestRebuild);
    }

    /** Widest a leap grid column gets (Off/Fast/Auto, target, mode); the name column takes the rest. */
    private static final int[] COL_MAX = {60, 92, 80};
    /** Narrowest a column gets: "Auto", "Universal" and "Per Leap" with the pill's arrows. */
    private static final int[] COL_MIN = {44, 64, 60};
    /** The name column ("Door Opener", "Storm Death") never goes under this while the cyclers can still shrink. */
    private static final int NAME_MIN = 64;

    /**
     * The leap grid's column x offsets and widths at {@code w}: {name w, c1 x, c1 w, c2 x, c2 w, c3 x, c3 w}, x relative
     * to the page's left. The cyclers sit right, at their widest, and the name column takes the slack; on a narrow page
     * the cyclers shrink together (not below {@link #COL_MIN}) before the name column does.
     */
    public static int[] grid(int w) {
        int[] c = COL_MAX.clone();
        int spare = w - 3 * GAP - c[0] - c[1] - c[2];
        if (spare < NAME_MIN) {
            int need = NAME_MIN - spare;
            int give = 0;
            for (int k = 0; k < 3; k++) {
                give += COL_MAX[k] - COL_MIN[k];
            }
            for (int k = 0; k < 3; k++) {
                c[k] -= Math.min(COL_MAX[k] - COL_MIN[k], (int) Math.ceil(need * (COL_MAX[k] - COL_MIN[k]) / (double) give));
            }
        }
        int c3x = w - c[2];
        int c2x = c3x - GAP - c[1];
        int c1x = c2x - GAP - c[0];
        return new int[]{Math.max(1, c1x - GAP), c1x, c[0], c2x, c[1], c3x, c[2]};
    }

    /**
     * Every leap as ONE row (killer560, 2026-11-10: "there should be a line like crystal leap, (toggles for off, fast,
     * auto) (togle for class) (toggle for mode). all in one line"): its name, then Off / Fast / Auto, the target and the
     * Target Mode in three columns that line up down the page, under quiet column heads. P3 has a row per section (its
     * Off / Fast / Auto on the first). A leap that is Off keeps its target and mode, drawn dim and still clickable.
     * Door Opener's After Blood Off and P3's Gate Blown Only sit in the target and mode columns; Posmsg's keyword box is
     * a sub-row under its leap. {@code mine} null = the Universal target, else that class's own.
     */
    private static int phaseRows(List<AbstractWidget> widgets, int x, int y, int w, DungeonClass mine, boolean includeHidden,
                                 Runnable requestRebuild) {
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        int[] g = grid(w);
        if (!includeHidden) {
            // The column heads, once at the top: dim, centered over their columns.
            widgets.add(centered(x + g[1], y, g[2], "§8Fast/Auto"));
            widgets.add(centered(x + g[3], y, g[4], "§8Target"));
            widgets.add(centered(x + g[5], y, g[6], "§8Mode"));
        }
        y += GROUP_TITLE_H - 2;
        for (LeapPhase phase : PHASES) {
            y = caption(widgets, x, y, w, phase.caption());
            for (LeapGroup group : phase.groups()) {
                boolean off = cfg.getLeapMode(group) == FastLeapConfig.LeapMode.OFF;
                SettingsButtonWidget leap = SettingsButtonWidget.cycling(leapSwitchLabel(group, cfg), (btn, dir) -> {
                    cfg.setLeapMode(group, SettingsButtonWidget.step(cfg.getLeapMode(group), dir));
                    cfg.save();
                    btn.setMessage(leapSwitchLabel(group, cfg));
                    requestRebuild.run(); // the row's dimming follows Off
                }).bounds(x + g[1], y, g[2], ROW).compact().dim(off).build();
                if (group == LeapGroup.DOOR) {
                    widgets.add(rowName(x, y, g[0], group.label, off));
                    widgets.add(leap);
                    widgets.add(toggle(x + g[3], y, g[4] + GAP + g[6], "After Blood Off", cfg::isDisableAfterBloodOpen,
                            v -> cfg.setDisableAfterBloodOpen(v), cfg));
                    y += ROW + GAP;
                    continue;
                }
                boolean first = true;
                for (LeapTarget t : LeapTarget.values()) {
                    if (t.group != group) {
                        continue;
                    }
                    String name = cellName(t);
                    widgets.add(rowName(x, y, g[0], name, off));
                    if (first) {
                        widgets.add(leap);
                        first = false;
                    }
                    widgets.add(mine == null ? targetButton(x + g[3], y, g[4], t, cfg, off)
                            : classTargetButton(x + g[3], y, g[4], t, mine, cfg, off));
                    widgets.add(targetModeButton(x + g[5], y, g[6], t, cfg, requestRebuild, off));
                    y += ROW + GAP;
                    if (mine == null && (includeHidden || cfg.getTargetMode(t) == FastLeapConfig.TargetMode.POSMSG)) {
                        String hint = (group == LeapGroup.P3 ? t.label + " " : "") + "Keywords";
                        addEditBox(widgets, editBox(x + g[3], y, w - g[3], cfg.getTargetKeywords(t), hint, 128, v -> {
                            cfg.setTargetKeywords(t, v);
                            cfg.save();
                        }));
                        y += ROW + GAP;
                    }
                }
                if (group == LeapGroup.P3) {
                    widgets.add(toggle(x + g[3], y, g[4] + GAP + g[6], "Gate Blown Only", cfg::isOnlyWhenGateBlown,
                            v -> cfg.setOnlyWhenGateBlown(v), cfg));
                    y += ROW + GAP;
                }
            }
        }
        return y;
    }

    /** A grid row's name: "Crystal", "P3 S1"; also the start of its cyclers' labels ("Crystal Target: Tank"). */
    static String cellName(LeapTarget t) {
        return t.group == LeapGroup.P3 ? "P3 " + t.label : t.group.label;
    }

    /** A grid row's name at the left, dim while the leap is Off. */
    private static StringWidget rowName(int x, int y, int w, String name, boolean off) {
        return textAt(x + 4, y + (ROW - 8) / 2, w - 4, (off ? "§7" : "§f") + name);
    }

    /** A line of text as wide as itself (at most {@code maxW}), so it reads from {@code x} whatever StringWidget's
     *  alignment. */
    private static StringWidget textAt(int x, int y, int maxW, String text) {
        Component c = Component.literal(text);
        var font = Minecraft.getInstance().font;
        return new StringWidget(x, y, Math.max(1, Math.min(Math.max(1, maxW), font.width(c) + 1)), 10, c, font);
    }

    /** A line of text centered over the column at {@code x}, {@code w} wide. */
    private static StringWidget centered(int x, int y, int w, String text) {
        int tw = Minecraft.getInstance().font.width(Component.literal(text));
        return textAt(x + Math.max(0, (w - tw) / 2), y, w, text);
    }

    /** One leap's Target Mode: Per Leap (the target on its row) or Posmsg (whoever called the spot, the target as backup). */
    static SettingsButtonWidget targetModeButton(int x, int y, int w, LeapTarget t, FastLeapConfig cfg, Runnable requestRebuild,
                                                 boolean dim) {
        return SettingsButtonWidget.cycling(Component.literal(cellName(t) + " Mode: §6" + cfg.getTargetMode(t).label), (btn, dir) -> {
            cfg.setTargetMode(t, SettingsButtonWidget.step(cfg.getTargetMode(t), dir));
            cfg.save();
            requestRebuild.run();
        }).bounds(x, y, Math.max(1, w), ROW).compact().dim(dim).build();
    }

    /** The class the Set Class page edits: the one picked there, else the class you play now, else Mage. */
    static DungeonClass editingClass() {
        if (editingClass == null) {
            DungeonClass self = com.killer560.hub.leapmenu.PartyTracker.selfClass();
            editingClass = self != null ? self : DungeonClass.values()[0];
        }
        return editingClass;
    }

    private static ThemedSliderButton rangeSlider(int x, int y, int w, FastLeapConfig cfg, double norm,
            java.util.function.Supplier<String> label, int min, int max, java.util.function.IntConsumer set) {
        return new ThemedSliderButton(x, y, w, ROW, Component.literal(label.get()), norm) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal(label.get()));
            }

            @Override
            protected void applyValue() {
                set.accept(min + (int) Math.round(this.value * (max - min)));
                saveOnRelease(() -> cfg.save());
            }
        };
    }

    /** A boss phase's header over its rows, in the theme's section style. */
    private static int caption(List<AbstractWidget> widgets, int x, int y, int w, String text) {
        widgets.add(sectionHeader(x, y + 4, w, text));
        return y + SECTION_HEADER_H + 2;
    }

    /**
     * A target's button (killer560: "a button to select what class/name it should leap to"): left click forward, right
     * click back through None, the five classes, then everyone in the party by name. A class shows who in the party has
     * it now with Leap Order's class picks applied ({@link Teammates#firstAliveOfClass} is what the leap itself uses), and
     * a name shows that player's class.
     */
    static SettingsButtonWidget targetButton(int x, int y, int w, LeapTarget target, FastLeapConfig cfg, boolean dim) {
        return SettingsButtonWidget.cycling(targetLabel(target, cfg), (btn, dir) -> {
            stepTarget(target, cfg, dir);
            cfg.save();
            btn.setMessage(targetLabel(target, cfg));
        }).bounds(x, y, Math.max(1, w), ROW).compact().dim(dim).build();
    }

    /** The Target button's choices in order: null (None), then each class (no player names, 2026-11-09). */
    static List<Object> targetChoices(LeapTarget target, FastLeapConfig cfg) {
        List<Object> out = new java.util.ArrayList<>();
        out.add(null);
        out.addAll(List.of(DungeonClass.values()));
        return out;
    }

    /** Moves the target one choice forward (dir 1) or back (dir -1), wrapping. */
    public static void stepTarget(LeapTarget target, FastLeapConfig cfg, int dir) {
        List<Object> choices = targetChoices(target, cfg);
        int at = Math.max(0, choices.indexOf(cfg.getTargetClass(target)));
        cfg.setTargetPickClass(target, (DungeonClass) choices.get(Math.floorMod(at + (dir < 0 ? -1 : 1), choices.size())));
    }

    /**
     * A leap's target while you play {@code mine}: "Crystal Target: Universal" (none set - the Universal target applies)
     * or a class, cycling like the Universal button.
     */
    static SettingsButtonWidget classTargetButton(int x, int y, int w, LeapTarget target, DungeonClass mine, FastLeapConfig cfg,
                                                  boolean dim) {
        return SettingsButtonWidget.cycling(classTargetLabel(target, mine, cfg), (btn, dir) -> {
            stepClassTarget(target, mine, cfg, dir);
            cfg.save();
            btn.setMessage(classTargetLabel(target, mine, cfg));
        }).bounds(x, y, Math.max(1, w), ROW).compact().dim(dim).build();
    }

    /** Moves the per-class target one choice forward (dir 1) or back (dir -1): Same as Universal, then each class, wrapping. */
    public static void stepClassTarget(LeapTarget target, DungeonClass mine, FastLeapConfig cfg, int dir) {
        FastLeapConfig.ClassPick pick = cfg.getClassTarget(target, mine);
        List<Object> choices = new java.util.ArrayList<>();
        choices.add(null);
        choices.addAll(List.of(DungeonClass.values()));
        int at = pick != null && pick.byClass() ? choices.indexOf(pick.clazz()) : 0;
        Object next = choices.get(Math.floorMod(at + (dir < 0 ? -1 : 1), choices.size()));
        if (next == null) {
            cfg.clearClassTarget(target, mine);
        } else {
            cfg.setClassTargetClass(target, mine, (DungeonClass) next);
        }
    }

    static Component classTargetLabel(LeapTarget target, DungeonClass mine, FastLeapConfig cfg) {
        FastLeapConfig.ClassPick pick = cfg.getClassTarget(target, mine);
        String shown = pick == null || !pick.byClass() ? "§7Universal"
                : DungeonClass.colourCode(pick.clazz()) + pick.clazz().displayName();
        return Component.literal(cellName(target) + " Target: " + shown);
    }

    static Component targetLabel(LeapTarget target, FastLeapConfig cfg) {
        DungeonClass c = cfg.getTargetClass(target);
        return Component.literal(cellName(target) + " Target: " + (c == null ? "§7None" : DungeonClass.colourCode(c) + c.displayName()));
    }

    /** @return how many equal columns of at least {@code minW} fit in {@code width}, capped at {@code max}. */
    private static int fitCols(int width, int max, int minW) {
        int cols = (width + GAP) / (minW + GAP);
        return Math.max(1, Math.min(max, cols));
    }

    /** x of column {@code col} of {@code cols} equal columns across {@code width} starting at {@code x}. */
    private static int cellX(int x, int width, int cols, int col) {
        return x + (colWidth(width, cols) + GAP) * col;
    }

    /** Width of column {@code col}; the last column absorbs the rounding remainder. */
    private static int cellW(int x, int width, int cols, int col) {
        if (col == cols - 1) {
            return Math.max(1, x + width - cellX(x, width, cols, col));
        }
        return Math.max(1, colWidth(width, cols));
    }

    private static int colWidth(int width, int cols) {
        return (width - GAP * (cols - 1)) / cols;
    }

    public interface BoolGetter {
        boolean get();
    }

    /** A row label, vertically centered on a {@link #ROW}-high control starting at {@code y}. */
    public static StringWidget label(int x, int y, int w, String text) {
        return new StringWidget(x, y + (ROW - 8) / 2, Math.max(1, w), 10, Component.literal(text), Minecraft.getInstance().font);
    }

    /** Like {@link #label} but sized to the text itself (capped at {@code maxW}), so it reads as a left-hand
     *  row title instead of floating in the middle of a half-panel-wide box. */
    public static StringWidget rowLabel(int x, int y, int maxW, String text) {
        Component component = Component.literal(text);
        int w = Math.max(1, Math.min(Math.max(1, maxW), Minecraft.getInstance().font.width(component) + 2));
        return new StringWidget(x, y + (ROW - 8) / 2, w, 10, component, Minecraft.getInstance().font);
    }

    /** A standalone line of text (a caption, a hint) - not tied to a control row. */
    public static StringWidget titleLabel(int x, int y, int w, String text) {
        return new StringWidget(x, y, Math.max(1, w), 10, Component.literal(text), Minecraft.getInstance().font);
    }

    /** A section header in the theme's accent ({@link SectionHeaders}), {@link #SECTION_HEADER_H} tall including the gap
     *  below it. Not the cheat red: the whole tab is cheat-only already, and a page of red bars read as noise
     *  (killer560, 2026-11-10). */
    private static StringWidget sectionHeader(int x, int y, int width, String title) {
        return new StringWidget(x, y, Math.max(1, width), 12, SectionHeaders.header(title, false), Minecraft.getInstance().font);
    }

    static SettingsButtonWidget toggle(int x, int y, int w, String label, BoolGetter getter, Consumer<Boolean> setter,
                                       FastLeapConfig cfg) {
        return SettingsButtonWidget.builder(onOff(label, getter.get()), btn -> {
            setter.accept(!getter.get());
            cfg.save();
            btn.setMessage(onOff(label, getter.get()));
        }).bounds(x, y, Math.max(1, w), ROW).build();
    }

    /** Adds {@code box} with its Set button (Enter and Set both run its responder). */
    static void addEditBox(List<AbstractWidget> widgets, CommitEditBox box) {
        widgets.add(box);
        CommitEditBox.withSet(widgets, box);
    }

    static CommitEditBox editBox(int x, int y, int w, String value, String hint, int maxLength, Consumer<String> onChange) {
        CommitEditBox box = new CommitEditBox(Minecraft.getInstance().font, x, y, Math.max(1, w), ROW, Component.literal(hint));
        box.setMaxLength(maxLength);
        box.setValue(value == null ? "" : value);
        box.setHint(Component.literal("§8" + hint));
        box.setResponder(onChange);
        return box;
    }

    static SettingsButtonWidget classButton(int x, int y, int w, String label, DungeonClass current, Consumer<DungeonClass> onChange) {
        DungeonClass[] holder = {current};
        return SettingsButtonWidget.cycling(classLabel(label, current), (btn, dir) -> {
            holder[0] = com.killer560.hub.dungeonclass.DungeonClass.step(holder[0], dir);
            onChange.accept(holder[0]);
            btn.setMessage(classLabel(label, holder[0]));
        }).bounds(x, y, Math.max(1, w), ROW).build();
    }

    static Component classLabel(String label, DungeonClass c) {
        return Component.literal(label + ": §6" + (c == null ? "None" : c.displayName()));
    }

    static Component onOff(String label, boolean value) {
        return Component.literal(onOffText(label, value));
    }

    /** The plain-string half of {@link #onOff}, for the list rows (which are labels, not buttons). */
    static String onOffText(String label, boolean value) {
        return label + ": " + (value ? "§aON" : "§cOFF");
    }

    private static String timeoutLabel(FastLeapConfig cfg) {
        return "Leap Timeout: " + String.format(java.util.Locale.ROOT, "%.2f", cfg.getTimeoutMs() / 1000.0) + "s";
    }

    private static String delayLabel(FastLeapConfig cfg) {
        return "Click Delay: " + cfg.getClickDelayMs() + "ms";
    }
}
