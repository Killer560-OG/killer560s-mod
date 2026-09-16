package com.killer560.hub.fastleap;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.fastleap.FastLeapConfig.LeapGroup;
import com.killer560.hub.fastleap.FastLeapConfig.LeapTarget;
import com.killer560.hub.fastleap.FastLeapConfig.TargetMode;
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
 *     <li>{@link #buildGeneral} - the master switch, then Target Mode + Click Delay, then the three
 *         behaviour toggles. Always at the top of the LIST view.</li>
 *     <li>{@link #buildLeapList} - one row per leap: the leap's name + its ON/OFF state on the left half,
 *         an "Edit" button on the right half.</li>
 *     <li>{@link #buildLeapEditor} - every setting of ONE leap (enable, Auto, its extra toggle, all of its
 *         target fields), laid out full width now that it has the whole panel to itself.</li>
 * </ol>
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

    /** Master switch, then (when it's on) Target Mode + Click Delay and the three behaviour toggles.
     *  @return the y below the last row. */
    public static int buildGeneral(List<AbstractWidget> widgets, int x, int y, int width, Runnable requestRebuild) {
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

        // Target Mode | Click Delay
        int cols = fitCols(width, 2, MIN_TOGGLE_W + 24);
        widgets.add(SettingsButtonWidget.builder(Component.literal("Target Mode: §6" + cfg.getTargetMode().label), btn -> {
            TargetMode[] all = TargetMode.values();
            cfg.setTargetMode(all[(cfg.getTargetMode().ordinal() + 1) % all.length]);
            cfg.save();
            requestRebuild.run();
        }).bounds(cellX(x, width, cols, 0), y, cellW(x, width, cols, 0), ROW).build());
        if (cols == 1) {
            y += ROW + GAP;
        }
        int delayCol = cols == 1 ? 0 : 1;
        double norm = (cfg.getClickDelayMs() - FastLeapConfig.MIN_CLICK_DELAY_MS)
                / (double) (FastLeapConfig.MAX_CLICK_DELAY_MS - FastLeapConfig.MIN_CLICK_DELAY_MS);
        widgets.add(new ThemedSliderButton(cellX(x, width, cols, delayCol), y, cellW(x, width, cols, delayCol), ROW,
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
        y += ROW + GAP;

        // Block Inputs | Fast Mode | Swap Back - 3 across, wrapping to 2 or 1 on a narrow panel
        cols = fitCols(width, 3, MIN_TOGGLE_W);
        y = flowToggles(widgets, x, y, width, cols, cfg,
                new String[]{"Block Inputs", "Fast Mode", "Swap Back"},
                new BoolGetter[]{cfg::isBlockInputs, cfg::isFastMode, cfg::isSwapBack},
                new BoolSetter[]{cfg::setBlockInputs, cfg::setFastMode, cfg::setSwapBack});
        return y;
    }

    // ---- section 2: the leap list -------------------------------------------------------------------------------

    /** One row per leap: "&lt;name&gt; Leap: ON/OFF" on the left half, an "Edit" button on the right half.
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
        for (LeapGroup group : LeapGroup.values()) {
            widgets.add(rowLabel(x, y, half, onOffText(group.label + " Leap", cfg.isLeapEnabled(group))));
            widgets.add(SettingsButtonWidget.builder(Component.literal("Edit"), btn -> onEdit.accept(group))
                    .bounds(editX, y, editW, ROW).build());
            y += ROW + GAP;
        }
        return y;
    }

    // ---- section 3: one leap's own settings page ----------------------------------------------------------------

    /** EVERY setting of a single leap, full width: its enable toggle first, then Auto, then whichever extra
     *  toggle it has (Door -> "After Blood Off", P3 -> "Gate Blown Only", Test -> its class), then each of its
     *  targets. Nothing here is hidden behind another switch - this is the leap's whole page, so a setting is
     *  never unreachable just because its parent happens to be off.
     *  @return the y below the last row. */
    public static int buildLeapEditor(List<AbstractWidget> widgets, int x, int y, int width, LeapGroup group) {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return y;
        }
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        int w = Math.max(1, width);

        // "<group> Leap" is also the SettingTooltips key
        widgets.add(toggle(x, y, w, group.label + " Leap",
                () -> cfg.isLeapEnabled(group), v -> cfg.setLeapEnabled(group, v), cfg));
        y += ROW + GAP;

        widgets.add(toggle(x, y, w, "Auto", () -> cfg.isLeapAuto(group), v -> cfg.setLeapAuto(group, v), cfg));
        y += ROW + GAP;

        if (group == LeapGroup.DOOR) {
            widgets.add(toggle(x, y, w, "After Blood Off", cfg::isDisableAfterBloodOpen,
                    v -> cfg.setDisableAfterBloodOpen(v), cfg));
            y += ROW + GAP;
        } else if (group == LeapGroup.P3) {
            widgets.add(toggle(x, y, w, "Gate Blown Only", cfg::isOnlyWhenGateBlown,
                    v -> cfg.setOnlyWhenGateBlown(v), cfg));
            y += ROW + GAP;
        } else if (group == LeapGroup.TEST) {
            // TEMPORARY - test leap, added 2026-09-15 at killer560's request ("a test fast leap ... it will
            // always leap to that class no matter where I am ... only for testing right now and will be
            // removed after I finish testing"). Delete this whole branch when the test leap goes.
            widgets.add(classButton(x, y, w, "Class", cfg.getTestLeapClass(), c -> {
                cfg.setTestLeapClass(c);
                cfg.save();
            }));
            y += ROW + GAP + 2;
            widgets.add(titleLabel(x, y, w, "§7Testing aid: while this is ON it overrides the position-based"));
            y += GROUP_TITLE_H;
            widgets.add(titleLabel(x, y, w, "§7leaps and always leaps to this class, wherever you are."));
            y += GROUP_TITLE_H;
            widgets.add(titleLabel(x, y, w, "§7Auto ON also redirects the automatic leaps to it."));
            y += GROUP_TITLE_H;
            return y;
        }

        for (LeapTarget target : LeapTarget.values()) {
            if (target.group == group) {
                y = buildTarget(widgets, x, y + GROUP_GAP - GAP, w, target, cfg);
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

    /** Lays a list of on/off toggles out across {@code cols} columns, wrapping onto further rows.
     *  @return the y below the last row used. */
    private static int flowToggles(List<AbstractWidget> widgets, int x, int y, int width, int cols, FastLeapConfig cfg,
                                   String[] labels, BoolGetter[] getters, BoolSetter[] setters) {
        for (int i = 0; i < labels.length; i++) {
            int col = i % cols;
            BoolSetter setter = setters[i];
            widgets.add(toggle(cellX(x, width, cols, col), y, cellW(x, width, cols, col), labels[i],
                    getters[i], setter::set, cfg));
            if (col == cols - 1 || i == labels.length - 1) {
                y += ROW + GAP;
            }
        }
        return y;
    }

    // ------------------------------------------------------------------------------------------------------------

    public interface BoolGetter {
        boolean get();
    }

    /** Setter half of a boolean setting, so a row of toggles can be built from parallel arrays. */
    public interface BoolSetter {
        void set(boolean v);
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
