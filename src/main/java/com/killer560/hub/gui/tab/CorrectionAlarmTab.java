package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.util.CorrectionAlarmConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Home's Correction Alarm (killer560, 2026-10-06): a server position correction never stops an automation; it posts a
 *  chat line and plays this siren (util/ServerCorrections, util/ModSounds). Cheat build only. It sat in the middle of
 *  Mod &amp; HUD until 2026-10-07, when killer560 asked for cheat settings to sit at the bottom of their category rather
 *  than under a legit one; it is now Home's last section, after Discord Rich Presence. Same CorrectionAlarmConfig. */
public class CorrectionAlarmTab extends BaseTab {

    public CorrectionAlarmTab() {
        super("Correction Alarm");
    }

    /** Only added to Home behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
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
        int gap = 8;
        int half = (contentWidth - gap) / 2;
        int rightW = Math.max(1, contentWidth - half - gap);
        int y = contentY;

        // Home draws every section inline with no accordion header, so each one draws its own heading.
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Correction Alarm", true), Minecraft.getInstance().font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(alarmText(), btn -> {
                    CorrectionAlarmConfig cfg = CorrectionAlarmConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(alarmText());
                }).bounds(contentX, y, half, 20).build());
        widgets.add(new ThemedSliderButton(contentX + half + gap, y, rightW, 20, volumeText(),
                CorrectionAlarmConfig.getInstance().getVolume()) {
            @Override
            protected void updateMessage() {
                setMessage(volumeText());
            }

            @Override
            protected void applyValue() {
                CorrectionAlarmConfig cfg = CorrectionAlarmConfig.getInstance();
                cfg.setVolume(Math.round(this.value * 100) / 100f);
                cfg.save();
            }
        });
        return widgets;
    }

    private static Component alarmText() {
        return Component.literal("Correction Alarm: "
                + (CorrectionAlarmConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component volumeText() {
        return Component.literal("Alarm Volume: §b"
                + Math.round(CorrectionAlarmConfig.getInstance().getVolume() * 100) + "%");
    }
}
