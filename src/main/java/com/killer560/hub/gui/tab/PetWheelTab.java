package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.petwheel.PetPickerScreen;
import com.killer560.hub.petwheel.PetWheelConfig;
import com.killer560.hub.util.KeyUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Pet Wheel settings (killer560's item 8.2) - OFF by default, New tab until confirmed live. Not cheat-gated
 * (killer560, 2026-09-21: "it's just a reskin of the /pets menu, not real automation"), so this tab is not
 * marked {@link #isCheatOnly()} and has no red header.
 */
public class PetWheelTab extends BaseTab implements KeyCaptureTab {

    private boolean capturingKey = false;

    public PetWheelTab() {
        super("Pet Wheel");
    }

    @Override
    public boolean isListeningForKey() {
        return capturingKey;
    }

    @Override
    public boolean supportsMouseCapture() {
        return true;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        PetWheelConfig cfg = PetWheelConfig.getInstance();
        cfg.setKeyCode(keyCode == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE ? KeyUtil.NONE : keyCode);
        capturingKey = false;
        cfg.save();
    }

    @Override
    public void onMouseCaptured(int button) {
        PetWheelConfig cfg = PetWheelConfig.getInstance();
        cfg.setKeyCode(KeyUtil.codeForMouseButton(button));
        capturingKey = false;
        cfg.save();
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        PetWheelConfig cfg = PetWheelConfig.getInstance();
        var font = Minecraft.getInstance().font;
        int y = contentY;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Pet Wheel", false), font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Pet Wheel", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2bX = contentX + col2W + gap;

        widgets.add(SettingsButtonWidget.builder(keyText(cfg), btn -> {
                    capturingKey = true;
                    btn.setMessage(Component.literal("Press any key/button..."));
                }).bounds(contentX, y, col2W, 18).build());

        widgets.add(SettingsButtonWidget.builder(modeText(cfg), btn -> {
                    cfg.cycleMode();
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col2bX, y, col2W, 18).build());
        y += 22;

        int minSlices = PetWheelConfig.MIN_SLICES;
        int maxSlices = PetWheelConfig.MAX_SLICES;
        double sliceNorm = (cfg.getSliceCount() - minSlices) / (double) (maxSlices - minSlices);
        widgets.add(new ThemedSliderButton(contentX, y, col2W, 18, sliceText(cfg), sliceNorm) {
            @Override
            protected void updateMessage() {
                setMessage(sliceText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setSliceCount((int) Math.round(minSlices + this.value * (maxSlices - minSlices)));
                cfg.save();
            }
        });

        int minScale = PetWheelConfig.MIN_SCALE_PCT;
        int maxScale = PetWheelConfig.MAX_SCALE_PCT;
        double scaleNorm = (cfg.getScalePercent() - minScale) / (double) (maxScale - minScale);
        widgets.add(new ThemedSliderButton(col2bX, y, col2W, 18, scaleText(cfg), scaleNorm) {
            @Override
            protected void updateMessage() {
                setMessage(scaleText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setScalePercent((int) Math.round(minScale + this.value * (maxScale - minScale)));
                cfg.save();
            }
        });
        y += 24;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Edit Pets..."), btn ->
                        Minecraft.getInstance().setScreen(new PetPickerScreen(Minecraft.getInstance().screen)))
                .bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal(statusLine(cfg)), font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private Component keyText(PetWheelConfig cfg) {
        if (capturingKey) {
            return Component.literal("Press any key/button...");
        }
        return Component.literal("Wheel Key: " + KeyUtil.bindDisplayName(cfg.getKeyCode()));
    }

    private static Component modeText(PetWheelConfig cfg) {
        String label = cfg.getMode() == PetWheelConfig.InteractionMode.HOLD_RELEASE
                ? "Hold & Release" : "Press then Click";
        return Component.literal("Mode: " + label);
    }

    private static Component sliceText(PetWheelConfig cfg) {
        return Component.literal("Slices: " + cfg.getSliceCount());
    }

    private static Component scaleText(PetWheelConfig cfg) {
        return Component.literal("Scale: " + cfg.getScalePercent() + "%");
    }

    /** Live counts, not an explanatory paragraph - the "in-panel paragraphs are being removed" rule (2026-09-21)
     *  explicitly keeps dynamic status lines like this one. */
    private static String statusLine(PetWheelConfig cfg) {
        return "§7" + cfg.getWheelPets().size() + " pet(s) on the wheel, " + cfg.getKnownPets().size() + " known.";
    }
}
