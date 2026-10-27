package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonalerts.DungeonAlertsConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Teammate Low Health Alert (see {@link com.killer560.hub.dungeonalerts.LowHealthAlert}). Backed by
 *  {@link DungeonAlertsConfig}; every change is saved at once. */
public class LowHealthAlertTab extends BaseTab {

    public LowHealthAlertTab() {
        super("Low Health Alert");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        int y = contentY;
        w.add(SettingsButtonWidget.builder(onOff("Low Health Alert", cfg.isLowHealthEnabled()), btn -> {
                    cfg.setLowHealthEnabled(!cfg.isLowHealthEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        if (!cfg.isLowHealthEnabled()) {
            return w;
        }
        w.add(SettingsButtonWidget.builder(onOff("Only While Healer", cfg.isLowHealthOnlyHealer()), btn -> {
                    cfg.setLowHealthOnlyHealer(!cfg.isLowHealthOnlyHealer());
                    cfg.save();
                    btn.setMessage(onOff("Only While Healer", cfg.isLowHealthOnlyHealer()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;
        w.add(SettingsButtonWidget.builder(onOff("Alert Sound", cfg.isLowHealthSound()), btn -> {
                    cfg.setLowHealthSound(!cfg.isLowHealthSound());
                    cfg.save();
                    btn.setMessage(onOff("Alert Sound", cfg.isLowHealthSound()));
                }).bounds(contentX, y, contentWidth, 18).build());
        return w;
    }
}
