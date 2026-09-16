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

/** Settings UI for {@link FastLeapFeature} (placed in the Leap Menu tab, which draws the section header). Labels only,
 *  compact rows on one grid (2026-09-15 consolidation): the general settings share two rows of thirds, every leap's
 *  on/off switch sits in a 3-column grid, and each ENABLED leap then gets its own block (Auto + extra option, then its
 *  target rows) with the names in one aligned label column. */
public final class FastLeapSection {

    /** Shared row metrics for the whole Leap Menu tab. */
    public static final int ROW = 18;
    public static final int GAP = 4;
    public static final int LABEL_W = 72;
    /** Narrowest grid column that still fits the longest leap switch label ("Door Opener Leap: OFF"). */
    private static final int MIN_GROUP_COL_W = 124;

    private FastLeapSection() {
    }

    /** Builds the Fast Leap controls (no header - the caller draws it). @return the y below the last row. */
    public static int build(List<AbstractWidget> widgets, int x, int y, int width, Runnable requestRebuild) {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return y;
        }
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        int third = (width - GAP * 2) / 3;
        int lastW = width - (third + GAP) * 2;

        boolean enabled = cfg.isEnabledSetting();
        widgets.add(SettingsButtonWidget.builder(onOff("Fast Leap", enabled), btn -> {
            cfg.setEnabled(!cfg.isEnabledSetting());
            cfg.save();
            requestRebuild.run();
        }).bounds(x, y, enabled ? third : width, ROW).build());
        if (!enabled) {
            return y + ROW + GAP;
        }

        widgets.add(SettingsButtonWidget.builder(Component.literal("Target Mode: §6" + cfg.getTargetMode().label), btn -> {
            TargetMode[] all = TargetMode.values();
            cfg.setTargetMode(all[(cfg.getTargetMode().ordinal() + 1) % all.length]);
            cfg.save();
            requestRebuild.run();
        }).bounds(x + third + GAP, y, third, ROW).build());
        double norm = (cfg.getClickDelayMs() - FastLeapConfig.MIN_CLICK_DELAY_MS)
                / (double) (FastLeapConfig.MAX_CLICK_DELAY_MS - FastLeapConfig.MIN_CLICK_DELAY_MS);
        widgets.add(new ThemedSliderButton(x + (third + GAP) * 2, y, lastW, ROW, Component.literal(delayLabel(cfg)), norm) {
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

        widgets.add(toggle(x, y, third, "Block Inputs", cfg::isBlockInputs, v -> cfg.setBlockInputs(v), cfg));
        widgets.add(toggle(x + third + GAP, y, third, "Fast Mode", cfg::isFastMode, v -> cfg.setFastMode(v), cfg));
        widgets.add(toggle(x + (third + GAP) * 2, y, lastW, "Swap Back", cfg::isSwapBack, v -> cfg.setSwapBack(v), cfg));
        y += ROW + GAP;

        // every leap's on/off switch in one grid - 3 columns when "Door Opener Leap: OFF" fits, else 2
        LeapGroup[] groups = LeapGroup.values();
        int cols = (width - GAP * 2) / 3 >= MIN_GROUP_COL_W ? 3 : 2;
        int colW = (width - GAP * (cols - 1)) / cols;
        for (int i = 0; i < groups.length; i++) {
            LeapGroup group = groups[i];
            int col = i % cols;
            int cx = x + (colW + GAP) * col;
            int cw = col == cols - 1 ? width - (colW + GAP) * col : colW;
            // "<group> Leap" is also the SettingTooltips key
            widgets.add(SettingsButtonWidget.builder(onOff(group.label + " Leap", cfg.isLeapEnabled(group)), btn -> {
                cfg.setLeapEnabled(group, !cfg.isLeapEnabled(group));
                cfg.save();
                requestRebuild.run();
            }).bounds(cx, y, cw, ROW).build());
            if (col == cols - 1 || i == groups.length - 1) {
                y += ROW + GAP;
            }
        }

        // per-leap options, only for enabled leaps
        for (LeapGroup group : groups) {
            if (cfg.isLeapEnabled(group)) {
                y = buildGroup(widgets, x, y + 2, width, group, cfg, requestRebuild);
            }
        }
        return y;
    }

    private static int buildGroup(List<AbstractWidget> widgets, int x, int y, int width, LeapGroup group,
                                  FastLeapConfig cfg, Runnable requestRebuild) {
        widgets.add(label(x, y, LABEL_W, "§6" + group.label));
        int fx = x + LABEL_W;
        int fw = width - LABEL_W;
        boolean hasExtra = group == LeapGroup.DOOR || (group == LeapGroup.P3 && cfg.isLeapAuto(LeapGroup.P3));
        int autoW = hasExtra ? (fw - GAP) / 2 : fw;
        if (group == LeapGroup.P3) {
            widgets.add(SettingsButtonWidget.builder(onOff("Auto", cfg.isLeapAuto(group)), btn -> {
                cfg.setLeapAuto(group, !cfg.isLeapAuto(group));
                cfg.save();
                requestRebuild.run();
            }).bounds(fx, y, autoW, ROW).build());
        } else {
            widgets.add(toggle(fx, y, autoW, "Auto", () -> cfg.isLeapAuto(group), v -> cfg.setLeapAuto(group, v), cfg));
        }
        int extraX = fx + autoW + GAP;
        int extraW = fw - autoW - GAP;
        if (group == LeapGroup.DOOR) {
            widgets.add(toggle(extraX, y, extraW, "After Blood Off", cfg::isDisableAfterBloodOpen, v -> cfg.setDisableAfterBloodOpen(v), cfg));
        } else if (hasExtra) {
            widgets.add(toggle(extraX, y, extraW, "Gate Blown Only", cfg::isOnlyWhenGateBlown, v -> cfg.setOnlyWhenGateBlown(v), cfg));
        }
        y += ROW + GAP;

        for (LeapTarget target : LeapTarget.values()) {
            if (target.group == group) {
                y = buildTarget(widgets, x, y, width, target, cfg);
            }
        }
        return y;
    }

    private static int buildTarget(List<AbstractWidget> widgets, int x, int y, int width, LeapTarget target,
                                   FastLeapConfig cfg) {
        int indent = 8;
        widgets.add(label(x + indent, y, LABEL_W - indent, "§7" + target.label));
        int fx = x + LABEL_W;
        int fw = width - LABEL_W;
        switch (cfg.getTargetMode()) {
            case NAME -> widgets.add(editBox(fx, y, fw, cfg.getTargetName(target), "Name", 16, v -> {
                cfg.setTargetName(target, v);
                cfg.save();
            }));
            case CLASS -> widgets.add(classButton(fx, y, fw, "Class", cfg.getTargetClass(target), c -> {
                cfg.setTargetClass(target, c);
                cfg.save();
            }));
            default -> {
                int kw = (fw - GAP * 2) / 2;
                int nw = (fw - kw - GAP * 2) / 2;
                int cw = fw - kw - nw - GAP * 2;
                widgets.add(editBox(fx, y, kw, cfg.getTargetKeywords(target), "Keywords", 128, v -> {
                    cfg.setTargetKeywords(target, v);
                    cfg.save();
                }));
                widgets.add(editBox(fx + kw + GAP, y, nw, cfg.getTargetName(target), "Backup name", 16, v -> {
                    cfg.setTargetName(target, v);
                    cfg.save();
                }));
                widgets.add(classButton(fx + kw + nw + GAP * 2, y, cw, "Backup", cfg.getTargetClass(target), c -> {
                    cfg.setTargetClass(target, c);
                    cfg.save();
                }));
            }
        }
        return y + ROW + GAP;
    }

    // ------------------------------------------------------------------------------------------------------------

    interface BoolGetter {
        boolean get();
    }

    /** A row label, vertically centred on a {@link #ROW}-high control starting at {@code y}. */
    public static StringWidget label(int x, int y, int w, String text) {
        return new StringWidget(x, y + (ROW - 8) / 2, Math.max(1, w), 10, Component.literal(text), Minecraft.getInstance().font);
    }

    static SettingsButtonWidget toggle(int x, int y, int w, String label, BoolGetter getter, Consumer<Boolean> setter,
                                       FastLeapConfig cfg) {
        return SettingsButtonWidget.builder(onOff(label, getter.get()), btn -> {
            setter.accept(!getter.get());
            cfg.save();
            btn.setMessage(onOff(label, getter.get()));
        }).bounds(x, y, w, ROW).build();
    }

    static EditBox editBox(int x, int y, int w, String value, String hint, int maxLength, Consumer<String> onChange) {
        EditBox box = new EditBox(Minecraft.getInstance().font, x, y, w, ROW, Component.literal(hint));
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
        }).bounds(x, y, w, ROW).build();
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
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private static String delayLabel(FastLeapConfig cfg) {
        return "Click Delay: " + cfg.getClickDelayMs() + "ms";
    }
}
