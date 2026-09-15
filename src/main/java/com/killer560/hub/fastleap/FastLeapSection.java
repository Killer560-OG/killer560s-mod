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

/** Settings UI for {@link FastLeapFeature} (placed in the Leap Menu tab). Labels only, compact rows; a leap's
 *  sub-settings only show while that leap is enabled. */
public final class FastLeapSection {

    private static final int ROW = 18;
    private static final int GAP = 4;
    private static final int LABEL_W = 44;

    private FastLeapSection() {
    }

    public static int build(List<AbstractWidget> widgets, int x, int y, int width, Runnable requestRebuild) {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return y;
        }
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        widgets.add(new StringWidget(x, y, width, 12, Component.literal("§6§lFast Leap"), Minecraft.getInstance().font));
        y += 14;

        widgets.add(SettingsButtonWidget.builder(onOff("Fast Leap", cfg.isEnabledSetting()), btn -> {
            cfg.setEnabled(!cfg.isEnabledSetting());
            cfg.save();
            requestRebuild.run();
        }).bounds(x, y, width, ROW).build());
        y += ROW + GAP;
        if (!cfg.isEnabledSetting()) {
            return y + 8;
        }

        int half = (width - GAP) / 2;
        int third = (width - GAP * 2) / 3;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Target Mode: §6" + cfg.getTargetMode().label), btn -> {
            TargetMode[] all = TargetMode.values();
            cfg.setTargetMode(all[(cfg.getTargetMode().ordinal() + 1) % all.length]);
            cfg.save();
            requestRebuild.run();
        }).bounds(x, y, half, ROW).build());
        double norm = (cfg.getClickDelayMs() - FastLeapConfig.MIN_CLICK_DELAY_MS)
                / (double) (FastLeapConfig.MAX_CLICK_DELAY_MS - FastLeapConfig.MIN_CLICK_DELAY_MS);
        widgets.add(new ThemedSliderButton(x + half + GAP, y, width - half - GAP, ROW, Component.literal(delayLabel(cfg)), norm) {
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
        widgets.add(toggle(x + (third + GAP) * 2, y, width - (third + GAP) * 2, "Swap Back", cfg::isSwapBack, v -> cfg.setSwapBack(v), cfg));
        y += ROW + GAP + 4;

        for (LeapGroup group : LeapGroup.values()) {
            y = buildGroup(widgets, x, y, width, group, cfg, requestRebuild);
        }
        return y + 8;
    }

    private static int buildGroup(List<AbstractWidget> widgets, int x, int y, int width, LeapGroup group,
                                  FastLeapConfig cfg, Runnable requestRebuild) {
        boolean on = cfg.isLeapEnabled(group);
        boolean hasExtra = group == LeapGroup.DOOR || (group == LeapGroup.P3 && cfg.isLeapAuto(LeapGroup.P3));
        int cols = !on ? 1 : hasExtra ? 3 : 2;
        int colW = (width - GAP * (cols - 1)) / cols;

        widgets.add(SettingsButtonWidget.builder(onOff(group.label + " Leap", on), btn -> {
            cfg.setLeapEnabled(group, !cfg.isLeapEnabled(group));
            cfg.save();
            requestRebuild.run();
        }).bounds(x, y, on ? colW : width, ROW).build());
        if (!on) {
            return y + ROW + GAP;
        }
        int autoX = x + colW + GAP;
        int autoW = cols == 2 ? width - colW - GAP : colW;
        if (group == LeapGroup.P3) {
            widgets.add(SettingsButtonWidget.builder(onOff("Auto", cfg.isLeapAuto(group)), btn -> {
                cfg.setLeapAuto(group, !cfg.isLeapAuto(group));
                cfg.save();
                requestRebuild.run();
            }).bounds(autoX, y, autoW, ROW).build());
        } else {
            widgets.add(toggle(autoX, y, autoW, "Auto", () -> cfg.isLeapAuto(group), v -> cfg.setLeapAuto(group, v), cfg));
        }
        int extraX = x + (colW + GAP) * 2;
        int extraW = width - (colW + GAP) * 2;
        if (group == LeapGroup.DOOR) {
            widgets.add(toggle(extraX, y, extraW, "After Blood Off", cfg::isDisableAfterBloodOpen, v -> cfg.setDisableAfterBloodOpen(v), cfg));
        } else if (group == LeapGroup.P3 && cfg.isLeapAuto(LeapGroup.P3)) {
            widgets.add(toggle(extraX, y, extraW, "Gate Blown Only", cfg::isOnlyWhenGateBlown, v -> cfg.setOnlyWhenGateBlown(v), cfg));
        }
        y += ROW + GAP;

        for (LeapTarget target : LeapTarget.values()) {
            if (target.group == group) {
                y = buildTarget(widgets, x, y, width, target, cfg, requestRebuild);
            }
        }
        return y + 2;
    }

    private static int buildTarget(List<AbstractWidget> widgets, int x, int y, int width, LeapTarget target,
                                   FastLeapConfig cfg, Runnable requestRebuild) {
        int indent = 8;
        widgets.add(new StringWidget(x + indent, y + 5, LABEL_W - indent, 10, Component.literal("§7" + target.label),
                Minecraft.getInstance().font));
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
