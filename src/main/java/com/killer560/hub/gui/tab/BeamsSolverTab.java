package com.killer560.hub.gui.tab;

import com.killer560.hub.puzzlesolvers.BeamsSolverConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Creeper Beams Solver settings - see {@link com.killer560.hub.puzzlesolvers.BeamsSolverFeature}'s
 *  class doc for the real Odin-ported lantern-pair database this is built on. */
public class BeamsSolverTab extends BaseTab {

    public BeamsSolverTab() {
        super("Creeper Beams Solver");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        BeamsSolverConfig cfg = BeamsSolverConfig.getInstance();

        if (masterClosed(widgets, "Creeper Beams Solver", cfg::isEnabledRaw, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, y, contentWidth)) {
            return widgets;
        }
        y += 26;

        widgets.add(onOffButton("Show Tracer", cfg::isShowTracer, cfg::setShowTracer, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 22;
        widgets.add(com.killer560.hub.gui.SettingsButtonWidget.cycleEnum(() -> showText(cfg), cfg::getShowMode,
                cfg::setShowMode, cfg::save).bounds(contentX, y, contentWidth, 18).build());

        SolverEspRows.append(widgets, com.killer560.hub.puzzlesolvers.SolverEspConfig.Solver.BEAMS, contentX, contentWidth, y, true);

        return widgets;
    }

    private static net.minecraft.network.chat.Component showText(BeamsSolverConfig cfg) {
        return net.minecraft.network.chat.Component.literal("Show: \u00a76" + cfg.getShowMode().label);
    }
}
