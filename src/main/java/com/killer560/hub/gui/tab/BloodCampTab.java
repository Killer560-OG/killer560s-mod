package com.killer560.hub.gui.tab;

import com.killer560.hub.bloodcamp.BloodCampConfig;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Blood Camp settings - see {@link com.killer560.hub.bloodcamp.BloodCampFeature}'s class doc for the
 *  real Noamm-ported mechanic this is built on. */
public class BloodCampTab extends BaseTab {

    public BloodCampTab() {
        super("Blood Camp");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        BloodCampConfig cfg = BloodCampConfig.getInstance();
        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2aX = contentX;
        int col2bX = contentX + col2W + gap;

        widgets.add(SettingsButtonWidget.builder(onOff("Blood Camp", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Show Overlay", cfg.isShowOverlay()), btn -> {
                    cfg.setShowOverlay(!cfg.isShowOverlay());
                    cfg.save();
                    btn.setMessage(onOff("Show Overlay", cfg.isShowOverlay()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 24;

        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Cheat Build - Automation", true), net.minecraft.client.Minecraft.getInstance().font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Trigger Bot", cfg.isTriggerBotEnabled()), btn -> {
                    cfg.setTriggerBotEnabled(!cfg.isTriggerBotEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col2aX, y, col2W, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Aura", cfg.isAuraEnabled()), btn -> {
                    cfg.setAuraEnabled(!cfg.isAuraEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Aura", cfg.isAuraEnabled()));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 22;

        if (cfg.isTriggerBotEnabled()) {
            widgets.add(SettingsButtonWidget.builder(onOff("Auto Detect Lag", cfg.isAutoDetectLag()), btn -> {
                        cfg.setAutoDetectLag(!cfg.isAutoDetectLag());
                        cfg.save();
                        btn.setMessage(onOff("Auto Detect Lag", cfg.isAutoDetectLag()));
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 20;

            double offsetNorm = (cfg.getManualTickOffset() + 20) / 40.0;
            widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18,
                    Component.literal("Click Offset: " + cfg.getManualTickOffset() + "t"), offsetNorm) {
                @Override
                protected void updateMessage() {
                    setMessage(Component.literal("Click Offset: " + cfg.getManualTickOffset() + "t"));
                }

                @Override
                protected void applyValue() {
                    cfg.setManualTickOffset((int) Math.round(this.value * 40) - 20);
                    cfg.save();
                }
            });
        }

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
