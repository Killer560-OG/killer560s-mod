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
 * Reworked again 2026-09-15 (killer560: "I dont like the current leaps style. Make it so on the left half it is
 * the title of leaps, on the right half is an edit button. If you press the edit button then it opens all the
 * settings for that specific auto/fastleap"). The old "Leaps" switch grid + "Leap Targets" block are gone;
 * instead there are two views, and {@code FastLeapTab} decides which one it is showing:
 * <ol>
 *     <li>{@link #buildGeneral} - the master switch, then two headed groups: Targeting (Target Mode, which
 *         every leap below shares) and Click Behavior (Click Delay, Block Inputs, Fast Mode, Swap Back).
 *         Always at the top of the LIST view.</li>
 *     <li>{@link #buildLeapList} - one row per leap under a red header per boss phase (Door Opener, P1, P2,
 *         P3, P4, P5, plus Testing for the temporary Test leap): the leap's name + its ON/OFF state on the
 *         left half, an "Edit" button on the right half.</li>
 *     <li>{@link #buildLeapEditor} - one leap's own page: its ON switch, then - only once that's on - Auto,
 *         its extra toggle if it has one, and its target fields, indented under the switch that gates them.</li>
 * </ol>
 * Regrouped again 2026-09-21 (killer560: "I hate the way fast leaps menu is currently set up. Please redesign
 * the setting menu for it" - a judgement call, see {@code FastLeapTab}'s class doc for the reasoning). Two
 * concrete problems drove it: (1) the general row of toggles put Fast Mode next to Block Inputs as if they were
 * equally-weighted peers, when Fast Mode's own tooltip says it does nothing unless Block Inputs is also on;
 * (2) a leap's Auto switch, its extra toggle and every one of its targets used to show unconditionally even
 * while that leap's own ON switch was off, so a leap that would never fire still looked fully configured. Both
 * now follow the same "hide the child until its parent is on" rule Simon Says' own three-section layout uses
 * ({@code SimonSaysTab}, 2026-09-21). The 13-leap list was also flat with no grouping at all; it's now split
 * into the phase headers above, sourced straight from each leap's own tooltip text (see the "F7 P1"/"F7 P2"/
 * etc. wording in {@code SettingTooltipsData}) so the grouping doesn't invent any dungeon fact that wasn't
 * already documented. None of this changes what any setting does, only where it sits and when it's drawn -
 * {@code buildGeneral}/{@code buildLeapEditor} both take an {@code includeHidden} escape hatch so
 * {@code FastLeapTab#matchesSearch}'s off-screen scan still finds a hidden child by text, exactly as it did
 * before anything here was ever hidden.
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
        widgets.add(SettingsButtonWidget.builder(Component.literal("Target Mode: §6" + cfg.getTargetMode().label), btn -> {
            TargetMode[] all = TargetMode.values();
            cfg.setTargetMode(all[(cfg.getTargetMode().ordinal() + 1) % all.length]);
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

    // ---- section 2: the leap list -------------------------------------------------------------------------------

    /** One boss-phase bucket of {@link #buildLeapList}'s rows: a red header plus the leaps under it. Wording and
     *  membership come straight from each {@link LeapGroup}'s own tooltip text in {@code SettingTooltipsData}
     *  ("F7 boss P1", "F7 P2", "F7 P3", "F7 P4", "F7/M7 P5") so the grouping doesn't assert a dungeon fact this
     *  code doesn't already document elsewhere. Door Opener stands alone because its own tooltip is explicit
     *  that it fires "outside the boss" at any door, not tied to a phase; Predev sits with P1 because its
     *  tooltip places its trigger "during P1/P2" starting from the P1 window. */
    private record LeapPhase(String header, LeapGroup... groups) {
    }

    private static final LeapPhase[] PHASES = {
            new LeapPhase("Door Opener", LeapGroup.DOOR),
            new LeapPhase("P1", LeapGroup.P1, LeapGroup.PREDEV),
            new LeapPhase("P2", LeapGroup.GREEN, LeapGroup.YELLOW, LeapGroup.PURPLE, LeapGroup.PY_HEALER, LeapGroup.STORM_DEATH),
            new LeapPhase("P3", LeapGroup.P3),
            new LeapPhase("P4", LeapGroup.MIDDLE, LeapGroup.P4),
            new LeapPhase("P5", LeapGroup.RELIC),
            // "(Temporary)" so it reads as the testing aid it is, not a 14th real leap - see LeapGroup.TEST.
            new LeapPhase("Testing (Temporary)", LeapGroup.TEST),
    };

    /** One row per leap, grouped under a red {@link #sectionHeader} per {@link #PHASES} bucket instead of one
     *  flat 13-row list: "&lt;name&gt; Leap: ON/OFF" on the left half, an "Edit" button on the right half.
     *  Clicking Edit hands the leap to {@code onEdit}, which is what makes the tab switch to
     *  {@link #buildLeapEditor} for it.
     *  @return the y below the last row. */
    public static int buildLeapList(List<AbstractWidget> widgets, int x, int y, int width, Consumer<LeapGroup> onEdit) {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return y;
        }
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        // left half = the leap's title + state, right half = its Edit button (killer560's own wording)
        int half = Math.max(1, (width - GAP) / 2);
        int editX = x + half + GAP;
        int editW = Math.max(1, x + width - editX);
        for (LeapPhase phase : PHASES) {
            widgets.add(sectionHeader(x, y, width, phase.header()));
            y += SECTION_HEADER_H;
            for (LeapGroup group : phase.groups()) {
                widgets.add(rowLabel(x, y, half, onOffText(group.label + " Leap", cfg.isLeapEnabled(group))));
                widgets.add(SettingsButtonWidget.builder(Component.literal("Edit"), btn -> onEdit.accept(group))
                        .bounds(editX, y, editW, ROW).build());
                y += ROW + GAP;
            }
            y += GROUP_GAP - GAP;
        }
        return y;
    }

    // ---- section 3: one leap's own settings page ----------------------------------------------------------------

    /** One leap's own page: its ON switch first, full width. Only once that's on does anything else appear -
     *  Auto, then whichever extra toggle this leap has (Door -> "After Blood Off", P3 -> "Gate Blown Only",
     *  Test -> its class), then each of its targets - indented under the switch that gates all of them. See the
     *  class doc for why this used to show everything unconditionally and no longer does.
     *  @return the y below the last row. */
    public static int buildLeapEditor(List<AbstractWidget> widgets, int x, int y, int width, LeapGroup group) {
        return buildLeapEditor(widgets, x, y, width, group, false);
    }

    /** @param includeHidden true also draws Auto/the extra toggle/every target even while this leap's own ON
     *  switch is off - only for {@code FastLeapTab#matchesSearch}'s off-screen scan, so a search still finds
     *  (say) a Posmsg keyword on a leap that happens to be turned off, which is most of them by default. */
    public static int buildLeapEditor(List<AbstractWidget> widgets, int x, int y, int width, LeapGroup group,
                                      boolean includeHidden) {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return y;
        }
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        int w = Math.max(1, width);

        // "<group> Leap" is also the SettingTooltips key
        widgets.add(toggle(x, y, w, group.label + " Leap",
                () -> cfg.isLeapEnabled(group), v -> cfg.setLeapEnabled(group, v), cfg));
        y += ROW + GAP;

        if (!cfg.isLeapEnabled(group) && !includeHidden) {
            widgets.add(titleLabel(x, y, w, "§7Turn this leap on to set up its options below."));
            return y + GROUP_TITLE_H;
        }

        int ix = x + INDENT;
        int iw = Math.max(1, w - INDENT);

        widgets.add(toggle(ix, y, iw, "Auto", () -> cfg.isLeapAuto(group), v -> cfg.setLeapAuto(group, v), cfg));
        y += ROW + GAP;

        if (group == LeapGroup.DOOR) {
            widgets.add(toggle(ix, y, iw, "After Blood Off", cfg::isDisableAfterBloodOpen,
                    v -> cfg.setDisableAfterBloodOpen(v), cfg));
            y += ROW + GAP;
        } else if (group == LeapGroup.P3) {
            widgets.add(toggle(ix, y, iw, "Gate Blown Only", cfg::isOnlyWhenGateBlown,
                    v -> cfg.setOnlyWhenGateBlown(v), cfg));
            y += ROW + GAP;
        } else if (group == LeapGroup.TEST) {
            // TEMPORARY - test leap, added 2026-09-15 at killer560's request ("a test fast leap ... it will
            // always leap to that class no matter where I am ... only for testing right now and will be
            // removed after I finish testing"). Delete this whole branch when the test leap goes. The old
            // three lines of "Testing aid: ..." panel text are gone (2026-09-21, in-panel paragraphs are being
            // removed mod-wide) - the same explanation now lives in the "fast/auto leap/test leap" tooltip.
            widgets.add(classButton(ix, y, iw, "Class", cfg.getTestLeapClass(), c -> {
                cfg.setTestLeapClass(c);
                cfg.save();
            }));
            y += ROW + GAP;
            return y;
        }

        for (LeapTarget target : LeapTarget.values()) {
            if (target.group == group) {
                y = buildTarget(widgets, ix, y + GROUP_GAP - GAP, iw, target, cfg);
            }
        }
        return y;
    }

    /** One target of the leap being edited: a title line ("Target", or "S1".."S4" for P3) and then, depending
     *  on Target Mode, a full-width name box, a full-width class button, or the Posmsg keyword box with the
     *  two backup controls under it. Full width on purpose - the old layout squeezed these boxes into a
     *  shared row where you couldn't read what you'd typed. */
    private static int buildTarget(List<AbstractWidget> widgets, int x, int y, int width, LeapTarget target,
                                   FastLeapConfig cfg) {
        widgets.add(titleLabel(x, y, width, "§6" + target.label));
        y += GROUP_TITLE_H;
        switch (cfg.getTargetMode()) {
            case NAME -> {
                widgets.add(editBox(x, y, width, cfg.getTargetName(target), "Name", 16, v -> {
                    cfg.setTargetName(target, v);
                    cfg.save();
                }));
                y += ROW + GAP;
            }
            case CLASS -> {
                widgets.add(classButton(x, y, width, "Class", cfg.getTargetClass(target), c -> {
                    cfg.setTargetClass(target, c);
                    cfg.save();
                }));
                y += ROW + GAP;
            }
            default -> {
                widgets.add(editBox(x, y, width, cfg.getTargetKeywords(target), "Keywords", 128, v -> {
                    cfg.setTargetKeywords(target, v);
                    cfg.save();
                }));
                y += ROW + GAP;
                if (width >= MIN_SPLIT_ROW_W) {
                    int nw = (width - GAP) / 2;
                    int cw = Math.max(1, width - nw - GAP);
                    widgets.add(editBox(x, y, nw, cfg.getTargetName(target), "Backup name", 16, v -> {
                        cfg.setTargetName(target, v);
                        cfg.save();
                    }));
                    widgets.add(classButton(x + nw + GAP, y, cw, "Backup", cfg.getTargetClass(target), c -> {
                        cfg.setTargetClass(target, c);
                        cfg.save();
                    }));
                    y += ROW + GAP;
                } else {
                    widgets.add(editBox(x, y, width, cfg.getTargetName(target), "Backup name", 16, v -> {
                        cfg.setTargetName(target, v);
                        cfg.save();
                    }));
                    y += ROW + GAP;
                    widgets.add(classButton(x, y, width, "Backup", cfg.getTargetClass(target), c -> {
                        cfg.setTargetClass(target, c);
                        cfg.save();
                    }));
                    y += ROW + GAP;
                }
            }
        }
        return y;
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
        return SettingsButtonWidget.builder(classLabel(label, current), btn -> {
            holder[0] = nextClass(holder[0]);
            onChange.accept(holder[0]);
            btn.setMessage(classLabel(label, holder[0]));
        }).bounds(x, y, Math.max(1, w), ROW).build();
    }

    static DungeonClass nextClass(DungeonClass c) {
        DungeonClass[] all = DungeonClass.values();
        if (c == null) {
            return all[0];
        }
        return c.ordinal() + 1 < all.length ? all[c.ordinal() + 1] : null;
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
