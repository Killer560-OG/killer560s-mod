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

        if (!cfg.isEnabled() && !isSearchScan()) {
            return widgets;
        }

        widgets.add(onOffButton("Alternate Path", cfg::isOptimizedPath, cfg::setOptimizedPath, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 22;

        // killer560, 2026-11-01: "line color should be a customizable box not a toggle through".
        widgets.add(SettingsButtonWidget.builder(com.killer560.hub.gui.ColorSwatch.label("Line Color", cfg.getLineColorArgb()),
                btn -> {
                    net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                    com.killer560.hub.compat.McCompat.setScreen(client, new com.killer560.hub.gui.ColorPickerScreen(
                            com.killer560.hub.compat.McCompat.screen(client), "Line Color", cfg.getLineColorArgb(),
                            IceFillSolverConfig.DEFAULT_LINE_COLOR, argb -> {
                                cfg.setLineColorArgb(argb);
                                cfg.save();
                            }));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;
        // Only drops his own etherwarp click (nothing is sent), like Block Wrong Answers: legit.
        widgets.add(onOffButton("Etherwarp Protection", cfg::isEtherwarpProtection, cfg::setEtherwarpProtection, cfg::save,
                null, contentX, y, contentWidth, 18));

        SolverEspRows.append(widgets, com.killer560.hub.puzzlesolvers.SolverEspConfig.Solver.ICE_FILL, contentX, contentWidth, y, false);

        return widgets;
    }

}
