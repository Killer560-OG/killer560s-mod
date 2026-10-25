package com.killer560.hub.gui.tab;

import com.killer560.hub.puzzlesolvers.WeirdosSolverConfig;
import net.minecraft.client.gui.components.AbstractWidget;

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

        widgets.add(onOffButton("Weirdos Solver", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(onOffButton("Show Ruled-Out Chests", cfg::isShowWrongChests, cfg::setShowWrongChests, cfg::save,
                null, contentX, y, contentWidth, 18));

        SolverEspRows.append(widgets, com.killer560.hub.puzzlesolvers.SolverEspConfig.Solver.WEIRDOS, contentX, contentWidth, y, true);

        return widgets;
    }
}
