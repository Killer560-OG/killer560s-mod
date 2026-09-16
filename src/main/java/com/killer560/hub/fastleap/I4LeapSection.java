package com.killer560.hub.fastleap;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.fastleap.I4LeapConfig.BackupType;
import com.killer560.hub.fastleap.I4LeapConfig.TargetType;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Settings UI for {@link I4LeapFeature} (placed in the Sharp Shooter (i4) tab). Labels only. */
public final class I4LeapSection {

    private static final int ROW = 18;
    private static final int GAP = 4;

    private I4LeapSection() {
    }

    public static int build(List<AbstractWidget> widgets, int x, int y, int width, Runnable requestRebuild) {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return y;
        }
        I4LeapConfig cfg = I4LeapConfig.getInstance();
        // cheat-only section -> red header (SectionHeaders rule, 2026-09-15)
        widgets.add(new StringWidget(x, y, width, 12, SectionHeaders.header("I4 Leap", true), Minecraft.getInstance().font));
        y += 14;

        widgets.add(SettingsButtonWidget.builder(FastLeapSection.onOff("I4 Leap", cfg.isEnabled()), btn -> {
            cfg.setEnabled(!cfg.isEnabled());
            cfg.save();
            requestRebuild.run();
        }).bounds(x, y, width, ROW).build());
        y += ROW + GAP;
        if (!cfg.isEnabled()) {
            return y + 8;
        }

        int third = (width - GAP * 2) / 3;
        int lastW = width - (third + GAP) * 2;
        widgets.add(toggle(x, y, third, "Auto", cfg::isAuto, cfg::setAuto, cfg));
        widgets.add(toggle(x + third + GAP, y, third, "Fast Leap", cfg::isFastLeap, cfg::setFastLeap, cfg));
        widgets.add(toggle(x + (third + GAP) * 2, y, lastW, "Prevent Inputs", cfg::isPreventInputs, cfg::setPreventInputs, cfg));
        y += ROW + GAP;

        int half = (width - GAP) / 2;
        int rightX = x + half + GAP;
        int rightW = width - half - GAP;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Target: §6" + cfg.getTargetType().label), btn -> {
            TargetType[] all = TargetType.values();
            cfg.setTargetType(all[(cfg.getTargetType().ordinal() + 1) % all.length]);
            cfg.save();
            requestRebuild.run();
        }).bounds(x, y, half, ROW).build());
        switch (cfg.getTargetType()) {
            case CLASS -> widgets.add(FastLeapSection.classButton(rightX, y, rightW, "Class", cfg.getTargetClass(), c -> {
                cfg.setTargetClass(c);
                cfg.save();
            }));
            case PLAYER -> widgets.add(FastLeapSection.editBox(rightX, y, rightW, cfg.getTargetName(), "Player", 16, v -> {
                cfg.setTargetName(v);
                cfg.save();
            }));
            default -> {
                y += ROW + GAP;
                widgets.add(SettingsButtonWidget.builder(Component.literal("Backup: §6" + cfg.getBackupType().label), btn -> {
                    BackupType[] all = BackupType.values();
                    cfg.setBackupType(all[(cfg.getBackupType().ordinal() + 1) % all.length]);
                    cfg.save();
                    requestRebuild.run();
                }).bounds(x, y, half, ROW).build());
                if (cfg.getBackupType() == BackupType.CLASS) {
                    widgets.add(FastLeapSection.classButton(rightX, y, rightW, "Backup Class", cfg.getBackupClass(), c -> {
                        cfg.setBackupClass(c);
                        cfg.save();
                    }));
                } else {
                    widgets.add(FastLeapSection.editBox(rightX, y, rightW, cfg.getBackupName(), "Backup player", 16, v -> {
                        cfg.setBackupName(v);
                        cfg.save();
                    }));
                }
            }
        }
        y += ROW + GAP;
        return y + 8;
    }

    private interface Setter {
        void set(boolean v);
    }

    private static SettingsButtonWidget toggle(int x, int y, int w, String label, FastLeapSection.BoolGetter getter, Setter setter,
                                               I4LeapConfig cfg) {
        return SettingsButtonWidget.builder(FastLeapSection.onOff(label, getter.get()), btn -> {
            setter.set(!getter.get());
            cfg.save();
            btn.setMessage(FastLeapSection.onOff(label, getter.get()));
        }).bounds(x, y, w, ROW).build();
    }
}
