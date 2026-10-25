package com.killer560.hub.gui.tab;

import com.killer560.hub.puzzlesolvers.BlazeSolverConfig;
import net.minecraft.client.gui.components.AbstractWidget;

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

        widgets.add(onOffButton("Blaze Solver", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(onOffButton("Show Order Lines", cfg::isShowLines, cfg::setShowLines, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 22;

        widgets.add(onOffButton("Fill Box", cfg::isFillBox, cfg::setFillBox, cfg::save,
                null, contentX, y, contentWidth, 18));

        SolverEspRows.append(widgets, com.killer560.hub.puzzlesolvers.SolverEspConfig.Solver.BLAZE, contentX, contentWidth, y, false);

        return widgets;
    }
}
