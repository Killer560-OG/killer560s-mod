package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.motionblur.MotionBlurConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Motion Blur settings - see {@link MotionBlurConfig}. */
public class MotionBlurTab extends BaseTab {

    public MotionBlurTab() {
        super("Motion Blur");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        MotionBlurConfig cfg = MotionBlurConfig.getInstance();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Motion Blur", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        double range = MotionBlurConfig.MAX_STRENGTH - MotionBlurConfig.MIN_STRENGTH;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, strengthText(cfg.getStrength()),
                (cfg.getStrength() - MotionBlurConfig.MIN_STRENGTH) / range) {
            @Override
            protected void updateMessage() {
                setMessage(strengthText(cfg.getStrength()));
            }

            @Override
            protected void applyValue() {
                cfg.setStrength((int) Math.round(MotionBlurConfig.MIN_STRENGTH + this.value * range));
                cfg.save();
            }
        });
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Blur GUI", cfg.isBlurGui()), btn -> {
                    cfg.setBlurGui(!cfg.isBlurGui());
                    cfg.save();
                    btn.setMessage(onOff("Blur GUI", cfg.isBlurGui()));
                }).bounds(contentX, y, contentWidth, 18).build());

        return widgets;
    }

    private static Component strengthText(int strength) {
        return Component.literal("Strength: " + strength + "%");
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
