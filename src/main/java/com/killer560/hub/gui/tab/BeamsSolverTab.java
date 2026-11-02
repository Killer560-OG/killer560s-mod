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

        widgets.add(onOffButton("Creeper Beams Solver", cfg::isEnabledRaw, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        widgets.add(onOffButton("Show Tracer", cfg::isShowTracer, cfg::setShowTracer, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 22;
        widgets.add(com.killer560.hub.gui.SettingsButtonWidget.cycling(showText(cfg), (btn, dir) -> {
                    BeamsSolverConfig.ShowMode[] modes = BeamsSolverConfig.ShowMode.values();
                    cfg.setShowMode(modes[com.killer560.hub.gui.SettingsButtonWidget.step(cfg.getShowMode().ordinal(), dir,
                            modes.length)]);
                    cfg.save();
                    btn.setMessage(showText(cfg));
                }).bounds(contentX, y, contentWidth, 18).build());

        SolverEspRows.append(widgets, com.killer560.hub.puzzlesolvers.SolverEspConfig.Solver.BEAMS, contentX, contentWidth, y, true);

        return widgets;
    }

    private static net.minecraft.network.chat.Component showText(BeamsSolverConfig cfg) {
        return net.minecraft.network.chat.Component.literal("Show: \u00a76" + cfg.getShowMode().label);
    }
}
