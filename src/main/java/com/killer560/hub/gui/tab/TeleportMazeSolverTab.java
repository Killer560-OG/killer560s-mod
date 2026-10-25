package com.killer560.hub.gui.tab;

import com.killer560.hub.puzzlesolvers.TeleportMazeSolverConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Teleport Maze Solver settings - see {@link com.killer560.hub.puzzlesolvers.TeleportMazeSolverFeature}. */
public class TeleportMazeSolverTab extends BaseTab {

    public TeleportMazeSolverTab() {
        super("Teleport Maze Solver");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        TeleportMazeSolverConfig cfg = TeleportMazeSolverConfig.getInstance();

        widgets.add(onOffButton("Teleport Maze Solver", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(onOffButton("Tracer To Best Pad", cfg::isShowTracer, cfg::setShowTracer, cfg::save,
                null, contentX, y, contentWidth, 18));

        SolverEspRows.append(widgets, com.killer560.hub.puzzlesolvers.SolverEspConfig.Solver.TELEPORT_MAZE, contentX, contentWidth, y, true);

        return widgets;
    }
}
