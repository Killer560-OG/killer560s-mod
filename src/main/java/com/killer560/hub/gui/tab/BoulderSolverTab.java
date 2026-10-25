package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.puzzlesolvers.BoulderSolverConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Boulder Solver settings - see {@link com.killer560.hub.puzzlesolvers.BoulderSolverFeature}'s class doc
 *  for the real Odin-ported puzzle-solution database this is built on. */
public class BoulderSolverTab extends BaseTab {

    public BoulderSolverTab() {
        super("Boulder Solver");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        BoulderSolverConfig cfg = BoulderSolverConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Boulder Solver", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Show All Remaining Clicks", cfg.isShowAllClicks()), btn -> {
                    cfg.setShowAllClicks(!cfg.isShowAllClicks());
                    cfg.save();
                    btn.setMessage(onOff("Show All Remaining Clicks", cfg.isShowAllClicks()));
                }).bounds(contentX, y, contentWidth, 18).build());

        SolverEspRows.append(widgets, com.killer560.hub.puzzlesolvers.SolverEspConfig.Solver.BOULDER, contentX, contentWidth, y, true);

        return widgets;
    }
}
