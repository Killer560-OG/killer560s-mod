package com.killer560.hub.gui.tab;

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

        widgets.add(onOffButton("Motion Blur", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
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
                saveOnRelease(() -> cfg.save());
            }
        });
        y += 22;

        widgets.add(onOffButton("Blur GUI", cfg::isBlurGui, cfg::setBlurGui, cfg::save,
                null, contentX, y, contentWidth, 18));

        return widgets;
    }

    private static Component strengthText(int strength) {
        return Component.literal("Strength: " + strength + "%");
    }
}
