package com.killer560.hub.fastleap;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.fastleap.FastLeapConfig.LeapGroup;
import com.killer560.hub.fastleap.FastLeapConfig.LeapTarget;
import com.killer560.hub.fastleap.FastLeapConfig.TargetMode;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * Settings UI for {@link FastLeapFeature}. Lives in its own {@code FastLeapTab} ("Fast/Auto Leap") since
 * 2026-09-15, per killer560: "For fast leap put that in its own complete section outside of the leap menu.
 * Title it fast/auto leap. Then reformat it to look better." It used to be a section inside the Leap Menu tab.
 * <p>
 * Two parts: {@link #buildGeneral} (the master switch, Targeting and Click Behavior) and {@link #buildLeapList} (every
 * leap's switch, Mode and Target in place, under a red header per boss phase). Until 2026-10-13 each leap had an Edit
 * page instead; killer560 asked for them to go ("remove the edit buttons and instead have a toggle then a button to
 * select what class/name it should leap to").
 * <p>
 * This class builds bodies only - the tab draws the (red, cheat-only) section headers and the "&lt; Back"
 * button, so a header never appears twice. Every row is {@link #ROW} high with {@link #GAP} between controls;
 * the only row that ever splits into two columns is the Posmsg backup row, and it stacks again on a narrow
 * panel. Button label texts are unchanged - {@code SettingTooltips} looks descriptions up by label text.
 */
public final class FastLeapSection {

    /** Shared row metrics for the leap tabs. */
    public static final int ROW = 18;
    public static final int GAP = 4;
    public static final int LABEL_W = 72;
    /** Indent of a leap's sub-settings under its "&lt;name&gt; Leap" title line. */
    public static final int INDENT = 10;

    private static final int GROUP_TITLE_H = 12;
    private static final int GROUP_GAP = 8;
    /** Height reserved for a red {@link #sectionHeader}, matching the header+16 convention SimonSaysTab uses. */
    private static final int SECTION_HEADER_H = 16;
    /** Narrowest column that still fits a general toggle label ("Block Inputs: OFF"). */
    private static final int MIN_TOGGLE_W = 96;
    /** Below this the Posmsg backup row (backup name + backup class) stacks onto two full-width lines
     *  instead of sharing one, so neither half ever turns into an unreadable sliver. */
    private static final int MIN_SPLIT_ROW_W = 260;

    private FastLeapSection() {
    }

    /** @return the raw Fast Leap master switch, i.e. whether the rest of the page should be shown. */
    public static boolean isEnabledSetting() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && FastLeapConfig.getInstance().isEnabledSetting();
    }

    // ---- section 1: master switch + general settings ------------------------------------------------------------

    /** Master switch, then (when it's on) the Targeting and Click Behavior groups - see the class doc for why
     *  they're split this way.
     *  @return the y below the last row. */
    public static int buildGeneral(List<AbstractWidget> widgets, int x, int y, int width, Runnable requestRebuild) {
        return buildGeneral(widgets, x, y, width, requestRebuild, false);
    }

    /** @param includeHidden true also draws Fast Mode even while Block Inputs is off - only for
     *  {@code FastLeapTab#matchesSearch}'s off-screen scan, so searching "fast mode" still finds it. */
    public static int buildGeneral(List<AbstractWidget> widgets, int x, int y, int width, Runnable requestRebuild,
                                   boolean includeHidden) {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return y;
        }
        FastLeapConfig cfg = FastLeapConfig.getInstance();

        // master switch on its own full-width row - it gates everything below it
        widgets.add(SettingsButtonWidget.builder(onOff("Fast Leap", cfg.isEnabledSetting()), btn -> {
            cfg.setEnabled(!cfg.isEnabledSetting());
            cfg.save();
            requestRebuild.run();
        }).bounds(x, y, Math.max(1, width), ROW).build());
        y += ROW + GAP;
        if (!cfg.isEnabledSetting()) {
            widgets.add(titleLabel(x, y, width, "§7Turn Fast Leap on to set up the leaps below."));
            return y + GROUP_TITLE_H;
        }
        y += GROUP_GAP - GAP;

        // --- Targeting: the one rule every leap below shares, so it gets its own row instead of sharing one
        // with Click Delay (it used to, as if the two mattered equally - Target Mode reshapes every leap's
        // target fields, Click Delay is just an anti-double-click timer). ---
        widgets.add(sectionHeader(x, y, width, "Targeting"));
        y += SECTION_HEADER_H;
        widgets.add(SettingsButtonWidget.cycling(Component.literal("Target Mode: §6" + cfg.getTargetMode().label), (btn, dir) -> {
            cfg.setTargetMode(SettingsButtonWidget.step(cfg.getTargetMode(), dir));
            cfg.save();
            requestRebuild.run();
        }).bounds(x, y, Math.max(1, width), ROW).build());
        y += ROW + GAP + GROUP_GAP - GAP;

        // --- Click Behavior: what happens while a leap is actually clicking the menu. ---
        widgets.add(sectionHeader(x, y, width, "Click Behavior"));
        y += SECTION_HEADER_H;

        int cols = fitCols(width, 2, MIN_TOGGLE_W);
        double norm = (cfg.getClickDelayMs() - FastLeapConfig.MIN_CLICK_DELAY_MS)
                / (double) (FastLeapConfig.MAX_CLICK_DELAY_MS - FastLeapConfig.MIN_CLICK_DELAY_MS);
        widgets.add(new ThemedSliderButton(cellX(x, width, cols, 0), y, cellW(x, width, cols, 0), ROW,
                Component.literal(delayLabel(cfg)), norm) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal(delayLabel(cfg)));
            }

            @Override
            protected void applyValue() {
                int range = FastLeapConfig.MAX_CLICK_DELAY_MS - FastLeapConfig.MIN_CLICK_DELAY_MS;
                cfg.setClickDelayMs(FastLeapConfig.MIN_CLICK_DELAY_MS + (int) Math.round(this.value * range));
                cfg.save();
            }
        });
        int blockCol = cols == 1 ? 0 : 1;
        if (cols == 1) {
            y += ROW + GAP;
        }
        widgets.add(toggle(cellX(x, width, cols, blockCol), y, cellW(x, width, cols, blockCol), "Block Inputs",
                cfg::isBlockInputs, v -> cfg.setBlockInputs(v), cfg));
        y += ROW + GAP;

        // Fast Mode only changes what Block Inputs does (its own tooltip: "With Block Inputs ON, only blocks
        // input..."), so it stays hidden until Block Inputs is on instead of sitting next to it as a peer.
        if (cfg.isBlockInputs() || includeHidden) {
            widgets.add(toggle(x + INDENT, y, Math.max(1, width - INDENT), "Fast Mode",
                    cfg::isFastMode, v -> cfg.setFastMode(v), cfg));
            y += ROW + GAP;
        }

        // independent of the above - always visible once Click Behavior is showing at all
        widgets.add(toggle(x, y, width, "Swap Back", cfg::isSwapBack, v -> cfg.setSwapBack(v), cfg));
        y += ROW + GAP;
        return y;
    }

    // ---- section 2: the leaps ----------------------------------------------------------------------------------

    /** One boss-phase bucket of {@link #buildLeapList}'s rows: a red header plus the leaps under it. Door Opener stands
     *  alone because it fires outside the boss at any door; Predev sits with Crystal (P1) because it starts there. */
    private record LeapPhase(String header, LeapGroup... groups) {
    }

    private static final LeapPhase[] PHASES = {
            new LeapPhase("Door Opener", LeapGroup.DOOR),
            new LeapPhase("P1", LeapGroup.P1, LeapGroup.PREDEV),
            new LeapPhase("P2", LeapGroup.GREEN, LeapGroup.YELLOW, LeapGroup.PURPLE, LeapGroup.PY_HEALER, LeapGroup.STORM_DEATH),
            new LeapPhase("P3", LeapGroup.P3),
            new LeapPhase("P4", LeapGroup.MIDDLE, LeapGroup.P4),
            new LeapPhase("P5", LeapGroup.RELIC),
    };

    /** Below this a leap's row stacks: its switch on one line, Mode and Target on the next. */
    private static final int MIN_ONE_LINE_W = 300;

    /**
     * Every leap's settings in place, no Edit pages (killer560, 2026-10-13: "there is no option under each leap to change
     * whether it is fast leaping (on my left click) or auto leaping. Also remove the edit buttons and instead have a toggle
     * then a button to select what class/name it should leap to"). One row per leap: "&lt;name&gt; Leap: ON/OFF", then
     * "Mode: Fast/Auto" and "Target: ..." - Door Opener has no target (it goes to whoever opened the door), so its third
     * button is After Blood Off; P3's is Gate Blown Only and its four section targets sit on the row below. In Posmsg mode
     * each target also gets its keyword box. Everything shows whether the leap is on or not.
     * @return the y below the last row.
     */
    public static int buildLeapList(List<AbstractWidget> widgets, int x, int y, int width, Runnable requestRebuild) {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return y;
        }
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        int w = Math.max(1, width);
        for (LeapPhase phase : PHASES) {
            widgets.add(sectionHeader(x, y, w, phase.header()));
            y += SECTION_HEADER_H;
            for (LeapGroup group : phase.groups()) {
                y = buildLeapRow(widgets, x, y, w, group, cfg, requestRebuild);
            }
            y += GROUP_GAP - GAP;
        }
        return y;
    }

    private static int buildLeapRow(List<AbstractWidget> widgets, int x, int y, int w, LeapGroup group, FastLeapConfig cfg,
                                    Runnable requestRebuild) {
        boolean oneLine = w >= MIN_ONE_LINE_W;
        int switchW = oneLine ? (w - 2 * GAP) * 2 / 5 : w;
        int restX = oneLine ? x + switchW + GAP : x + INDENT;
        int restW = oneLine ? w - switchW - GAP : w - INDENT;
        int halfW = Math.max(1, (restW - GAP) / 2);
        int thirdX = restX + halfW + GAP;
        int thirdW = Math.max(1, restX + restW - thirdX);

        // "<group> Leap" is also the SettingTooltips key
        widgets.add(toggle(x, y, switchW, group.label + " Leap",
                () -> cfg.isLeapEnabled(group), v -> cfg.setLeapEnabled(group, v), cfg));
        if (!oneLine) {
            y += ROW + GAP;
        }
        widgets.add(SettingsButtonWidget.cycling(modeLabel(cfg.isLeapAuto(group)), (btn, dir) -> {
            cfg.setLeapAuto(group, !cfg.isLeapAuto(group));
            cfg.save();
            btn.setMessage(modeLabel(cfg.isLeapAuto(group)));
        }).bounds(restX, y, halfW, ROW).build());
        LeapTarget single = null;
        if (group == LeapGroup.DOOR) {
            widgets.add(toggle(thirdX, y, thirdW, "After Blood Off", cfg::isDisableAfterBloodOpen,
                    v -> cfg.setDisableAfterBloodOpen(v), cfg));
        } else if (group == LeapGroup.P3) {
            widgets.add(toggle(thirdX, y, thirdW, "Gate Blown Only", cfg::isOnlyWhenGateBlown,
                    v -> cfg.setOnlyWhenGateBlown(v), cfg));
        } else {
            for (LeapTarget t : LeapTarget.values()) {
                if (t.group == group) {
                    single = t;
                }
            }
            if (single != null) {
                widgets.add(targetButton(thirdX, y, thirdW, single, cfg));
            }
        }
        y += ROW + GAP;

        int ix = x + INDENT;
        int iw = Math.max(1, w - INDENT);
        if (group == LeapGroup.P3) {
            // S1-S4: four target buttons, two to a row (one on a very narrow panel)
            int cols = iw >= 2 * MIN_TOGGLE_W + GAP ? 2 : 1;
            int cw = Math.max(1, (iw - GAP * (cols - 1)) / cols);
            int col = 0;
            for (LeapTarget t : LeapTarget.values()) {
                if (t.group != LeapGroup.P3) {
                    continue;
                }
                widgets.add(targetButton(ix + col * (cw + GAP), y, col == cols - 1 ? ix + iw - (ix + col * (cw + GAP)) : cw,
                        t, cfg));
                if (++col == cols) {
                    col = 0;
                    y += ROW + GAP;
                }
            }
            if (col != 0) {
                y += ROW + GAP;
            }
        }
        if (cfg.getTargetMode() == FastLeapConfig.TargetMode.POSMSG) {
            for (LeapTarget t : LeapTarget.values()) {
                if (t.group != group) {
                    continue;
                }
                String hint = (group == LeapGroup.P3 ? t.label + " " : "") + "Keywords";
                widgets.add(editBox(ix, y, iw, cfg.getTargetKeywords(t), hint, 128, v -> {
                    cfg.setTargetKeywords(t, v);
                    cfg.save();
                }));
                y += ROW + GAP;
            }
        }
        return y;
    }

    static Component modeLabel(boolean auto) {
        return Component.literal("Mode: §6" + (auto ? "Auto" : "Fast"));
    }

    /**
     * The leap's Target button (killer560: "a button to select what class/name it should leap to"): left click forward,
     * right click back through None, the five classes, then everyone in the party by name. A class shows who in the party
     * has it now with Leap Order's class picks applied ({@link Teammates#firstAliveOfClass} is what the leap itself uses),
     * and a name shows that player's class. Labelled "Target", or "S1".."S4" for P3's sections.
     */
    static SettingsButtonWidget targetButton(int x, int y, int w, LeapTarget target, FastLeapConfig cfg) {
        String label = target.group == LeapGroup.P3 ? target.label : "Target";
        return SettingsButtonWidget.cycling(targetLabel(label, target, cfg), (btn, dir) -> {
            stepTarget(target, cfg, dir);
            cfg.save();
            btn.setMessage(targetLabel(label, target, cfg));
        }).bounds(x, y, Math.max(1, w), ROW).build();
    }

    /** The Target button's choices in order: null (None), each class, then each party member's name. */
    static List<Object> targetChoices(LeapTarget target, FastLeapConfig cfg) {
        List<Object> out = new java.util.ArrayList<>();
        out.add(null);
        out.addAll(List.of(DungeonClass.values()));
        for (String n : com.killer560.hub.leapmenu.PartyTracker.teammates()) {
            out.add(n);
        }
        String name = cfg.getTargetName(target);
        if (!cfg.isTargetByClass(target) && !name.isBlank() && !containsIgnoreCase(out, name)) {
            out.add(name); // a saved name from an earlier party stays selectable
        }
        return out;
    }

    /** Moves the target one choice forward (dir 1) or back (dir -1), wrapping. */
    public static void stepTarget(LeapTarget target, FastLeapConfig cfg, int dir) {
        List<Object> choices = targetChoices(target, cfg);
        int at = 0;
        if (cfg.isTargetByClass(target)) {
            at = Math.max(0, choices.indexOf(cfg.getTargetClass(target)));
        } else {
            String name = cfg.getTargetName(target);
            for (int i = 0; i < choices.size(); i++) {
                if (choices.get(i) instanceof String s && s.equalsIgnoreCase(name)) {
                    at = i;
                }
            }
        }
        Object next = choices.get(Math.floorMod(at + (dir < 0 ? -1 : 1), choices.size()));
        if (next instanceof String s) {
            cfg.setTargetPickName(target, s);
        } else {
            cfg.setTargetPickClass(target, (DungeonClass) next);
        }
    }

    private static boolean containsIgnoreCase(List<Object> list, String name) {
        for (Object o : list) {
            if (o instanceof String s && s.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    static Component targetLabel(String label, LeapTarget target, FastLeapConfig cfg) {
        String shown;
        if (cfg.isTargetByClass(target)) {
            DungeonClass c = cfg.getTargetClass(target);
            if (c == null) {
                shown = "§7None";
            } else {
                Teammates.Teammate who = Teammates.firstAliveOfClass(c);
                String holder = who != null ? who.name() : partyMemberOfClass(c);
                shown = com.killer560.hub.gui.tab.ClassOverridesTab.colourCode(c) + c.displayName()
                        + (holder == null ? "" : " §7(" + holder + ")");
            }
        } else {
            String n = cfg.getTargetName(target);
            DungeonClass c = n.isBlank() ? null : com.killer560.hub.leapmenu.PartyTracker.classOf(n);
            shown = "§6" + (n.isBlank() ? "None" : n) + (c == null ? "" : " §7(" + c.displayName() + ")");
        }
        return Component.literal(label + ": " + shown);
    }

    /** Outside a dungeon (no Teammates yet): the party member Leap Order's class picks give this class. */
    private static String partyMemberOfClass(DungeonClass c) {
        for (String n : com.killer560.hub.leapmenu.PartyTracker.teammates()) {
            if (com.killer560.hub.leapmenu.PartyTracker.classOf(n) == c) {
                return n;
            }
        }
        return null;
    }

    // ---- layout helpers -----------------------------------------------------------------------------------------

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

    // ------------------------------------------------------------------------------------------------------------

    public interface BoolGetter {
        boolean get();
    }

    /** A row label, vertically centred on a {@link #ROW}-high control starting at {@code y}. */
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

    /** A standalone line of text (a leap's title line, a hint) - not tied to a control row. */
    public static StringWidget titleLabel(int x, int y, int w, String text) {
        return new StringWidget(x, y, Math.max(1, w), 10, Component.literal(text), Minecraft.getInstance().font);
    }

    /** A red section header (this whole tab is cheat-only), {@link #SECTION_HEADER_H} tall including the gap
     *  below it - same header convention {@code SimonSaysTab} uses for its per-feature dividers. */
    private static StringWidget sectionHeader(int x, int y, int width, String title) {
        return new StringWidget(x, y, Math.max(1, width), 12, SectionHeaders.header(title, true), Minecraft.getInstance().font);
    }

    static SettingsButtonWidget toggle(int x, int y, int w, String label, BoolGetter getter, Consumer<Boolean> setter,
                                       FastLeapConfig cfg) {
        return SettingsButtonWidget.builder(onOff(label, getter.get()), btn -> {
            setter.accept(!getter.get());
            cfg.save();
            btn.setMessage(onOff(label, getter.get()));
        }).bounds(x, y, Math.max(1, w), ROW).build();
    }

    static EditBox editBox(int x, int y, int w, String value, String hint, int maxLength, Consumer<String> onChange) {
        EditBox box = new EditBox(Minecraft.getInstance().font, x, y, Math.max(1, w), ROW, Component.literal(hint));
        box.setMaxLength(maxLength);
        box.setValue(value == null ? "" : value);
        box.setHint(Component.literal("§8" + hint));
        box.setResponder(onChange);
        return box;
    }

    static SettingsButtonWidget classButton(int x, int y, int w, String label, DungeonClass current, Consumer<DungeonClass> onChange) {
        DungeonClass[] holder = {current};
        return SettingsButtonWidget.cycling(classLabel(label, current), (btn, dir) -> {
            holder[0] = com.killer560.hub.gui.tab.ClassOverridesTab.step(holder[0], dir);
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

    private static String delayLabel(FastLeapConfig cfg) {
        return "Click Delay: " + cfg.getClickDelayMs() + "ms";
    }
}
