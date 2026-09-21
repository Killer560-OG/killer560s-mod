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
 *  real Noamm-ported mechanic this is built on. Every label reads a {@code ...Raw()} getter: the gated
 *  getters are false outside Skyblock, so reading them here made every row show OFF and made a click
 *  write true no matter what the saved value was. */
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

        widgets.add(SettingsButtonWidget.builder(onOff("Blood Camp", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Show Overlay", cfg.getShowOverlayRaw()), btn -> {
                    cfg.setShowOverlay(!cfg.getShowOverlayRaw());
                    cfg.save();
                    btn.setMessage(onOff("Show Overlay", cfg.getShowOverlayRaw()));
                }).bounds(col2aX, y, col2W, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Kill Popup", cfg.getKillPopupRaw()), btn -> {
                    cfg.setKillPopup(!cfg.getKillPopupRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col2bX, y, col2W, 18).build());
        y += 22;

        if (cfg.getKillPopupRaw()) {
            double leadNorm = cfg.getKillPopupLeadTicks() / (double) BloodCampConfig.MAX_KILL_POPUP_LEAD_TICKS;
            widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18,
                    Component.literal("Kill Popup Lead: " + cfg.getKillPopupLeadTicks() + "t"), leadNorm) {
                @Override
                protected void updateMessage() {
                    setMessage(Component.literal("Kill Popup Lead: " + cfg.getKillPopupLeadTicks() + "t"));
                }

                @Override
                protected void applyValue() {
                    cfg.setKillPopupLeadTicks((int) Math.round(this.value * BloodCampConfig.MAX_KILL_POPUP_LEAD_TICKS));
                    cfg.save();
                }
            });
            y += 22;
        }

        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Cheat Build - Automation", true), net.minecraft.client.Minecraft.getInstance().font));
        y += 16;

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
