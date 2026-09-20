package com.killer560.hub.gui.tab;

import com.killer560.hub.boss.LividSolverConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Livid Solver settings - see {@link com.killer560.hub.boss.LividSolverFeature}'s class doc for the
 *  real Odin-ported wool/entity identification this is built on. */
public class LividSolverTab extends BaseTab {

    public LividSolverTab() {
        super("Livid Solver");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        LividSolverConfig cfg = LividSolverConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Livid Solver", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Show Invuln Timer", cfg.isShowTimer()), btn -> {
                    cfg.setShowTimer(!cfg.isShowTimer());
                    cfg.save();
                    btn.setMessage(onOff("Show Invuln Timer", cfg.isShowTimer()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(onOff("Draw Line To Livid", cfg.isShowLine()), btn -> {
                    cfg.setShowLine(!cfg.isShowLine());
                    cfg.save();
                    btn.setMessage(onOff("Draw Line To Livid", cfg.isShowLine()));
                }).bounds(contentX, y, contentWidth, 18).build());

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
