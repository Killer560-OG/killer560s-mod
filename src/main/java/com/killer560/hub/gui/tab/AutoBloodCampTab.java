package com.killer560.hub.gui.tab;

import com.killer560.hub.bloodcamp.BloodCampConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Auto Blood Camp - Blood Camp's cheat-build automation (Trigger Bot, Aura, and the Trigger Bot's timing), split
 *  out of {@link BloodCampTab}'s old "Cheat Build - Automation" section on 2026-10-07 (killer560: "make auto blood
 *  camp its own cheat tab"). Same {@link BloodCampConfig} fields and JSON keys as before, nothing renamed. Both
 *  automations act on Blood Camp's own predictions, so they still need Blood Camp itself ON (in its own tab); the
 *  note below says so when it is OFF. Only added to its category behind {@code CHEAT_FEATURES_ENABLED}. */
public class AutoBloodCampTab extends BaseTab {

    public AutoBloodCampTab() {
        super("Auto Blood Camp");
    }

    /** Only added to its category behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }
        int y = contentY;
        BloodCampConfig cfg = BloodCampConfig.getInstance();
        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2aX = contentX;
        int col2bX = contentX + col2W + gap;

        if (!cfg.isEnabledRaw()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7Needs Blood Camp ON (its own tab) - it predicts the mobs these act on."),
                    net.minecraft.client.Minecraft.getInstance().font));
            y += 16;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Trigger Bot", cfg.getTriggerBotRaw()), btn -> {
                    cfg.setTriggerBotEnabled(!cfg.getTriggerBotRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col2aX, y, col2W, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Aura", cfg.getAuraRaw()), btn -> {
                    cfg.setAuraEnabled(!cfg.getAuraRaw());
                    cfg.save();
                    btn.setMessage(onOff("Aura", cfg.getAuraRaw()));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 22;

        if (cfg.getTriggerBotRaw()) {
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
