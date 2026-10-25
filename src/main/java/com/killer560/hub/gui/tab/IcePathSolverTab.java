package com.killer560.hub.gui.tab;

import com.killer560.hub.puzzlesolvers.IcePathSolverConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Ice Path (silverfish) Solver settings - see {@link com.killer560.hub.puzzlesolvers.IcePathSolverFeature}. */
public class IcePathSolverTab extends BaseTab {

    public IcePathSolverTab() {
        super("Ice Path Solver");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        IcePathSolverConfig cfg = IcePathSolverConfig.getInstance();

        widgets.add(onOffButton("Ice Path Solver", cfg::isEnabledRaw, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        widgets.add(onOffButton("Outline Next Stop", cfg::isShowNextBox, cfg::setShowNextBox, cfg::save,
                null, contentX, y, contentWidth, 18));

        SolverEspRows.append(widgets, com.killer560.hub.puzzlesolvers.SolverEspConfig.Solver.ICE_PATH, contentX, contentWidth, y, true);

        return widgets;
    }
}
