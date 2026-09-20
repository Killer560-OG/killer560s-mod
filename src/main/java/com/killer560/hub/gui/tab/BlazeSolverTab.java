package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.puzzlesolvers.BlazeSolverConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Blaze Solver settings - see {@link com.killer560.hub.puzzlesolvers.BlazeSolverFeature}'s class doc
 *  for the real Odin-ported HP-order logic this is built on. */
public class BlazeSolverTab extends BaseTab {

    public BlazeSolverTab() {
        super("Blaze Solver");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        BlazeSolverConfig cfg = BlazeSolverConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Blaze Solver", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Show Order Lines", cfg.isShowLines()), btn -> {
                    cfg.setShowLines(!cfg.isShowLines());
                    cfg.save();
                    btn.setMessage(onOff("Show Order Lines", cfg.isShowLines()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Fill Box", cfg.isFillBox()), btn -> {
                    cfg.setFillBox(!cfg.isFillBox());
                    cfg.save();
                    btn.setMessage(onOff("Fill Box", cfg.isFillBox()));
                }).bounds(contentX, y, contentWidth, 18).build());

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
