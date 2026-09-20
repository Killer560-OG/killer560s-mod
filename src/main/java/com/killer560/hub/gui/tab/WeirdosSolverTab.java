package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.puzzlesolvers.WeirdosSolverConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Weirdos Solver settings - see {@link com.killer560.hub.puzzlesolvers.WeirdosSolverFeature}'s class doc
 *  for the real Odin-ported dialogue-matching logic this is built on. */
public class WeirdosSolverTab extends BaseTab {

    public WeirdosSolverTab() {
        super("Weirdos Solver");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        WeirdosSolverConfig cfg = WeirdosSolverConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Weirdos Solver", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Show Ruled-Out Chests", cfg.isShowWrongChests()), btn -> {
                    cfg.setShowWrongChests(!cfg.isShowWrongChests());
                    cfg.save();
                    btn.setMessage(onOff("Show Ruled-Out Chests", cfg.isShowWrongChests()));
                }).bounds(contentX, y, contentWidth, 18).build());

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
