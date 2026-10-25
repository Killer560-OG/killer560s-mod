package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.puzzlesolvers.IceFillSolverConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Ice Fill Solver settings - see {@link com.killer560.hub.puzzlesolvers.IceFillSolverFeature}'s class
 *  doc for the real Odin-ported path database this is built on. */
public class IceFillSolverTab extends BaseTab {

    public IceFillSolverTab() {
        super("Ice Fill Solver");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        IceFillSolverConfig cfg = IceFillSolverConfig.getInstance();

        widgets.add(onOffButton("Ice Fill Solver", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(onOffButton("Alternate Path", cfg::isOptimizedPath, cfg::setOptimizedPath, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 22;

        // killer560, 2026-09-20: "ice fill's line shouldn't be blue, hard to see - make it configurable".
        widgets.add(SettingsButtonWidget.cycling(colorLabel(cfg.getLineColor()), (btn, dir) -> {
                    cfg.cycleLineColor(dir);
                    cfg.save();
                    btn.setMessage(colorLabel(cfg.getLineColor()));
                }).bounds(contentX, y, contentWidth, 18).build());

        SolverEspRows.append(widgets, com.killer560.hub.puzzlesolvers.SolverEspConfig.Solver.ICE_FILL, contentX, contentWidth, y, false);

        return widgets;
    }

    private static Component colorLabel(com.killer560.hub.puzzlesolvers.IceFillSolverConfig.LineColor color) {
        return Component.literal("Line Color: " + color.name().charAt(0)
                + color.name().substring(1).toLowerCase(java.util.Locale.ROOT));
    }
}
