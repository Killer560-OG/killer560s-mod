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

        if (masterClosed(widgets, "Teleport Maze Solver", cfg::isEnabled, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, y, contentWidth)) {
            return widgets;
        }
        y += 26;

        widgets.add(onOffButton("Tracer To Best Pad", cfg::isShowTracer, cfg::setShowTracer, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 22;
        widgets.add(com.killer560.hub.gui.SettingsButtonWidget.builder(
                com.killer560.hub.gui.ColorSwatch.label("Tracer Color", cfg.getTracerColor()), btn -> {
                    net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                    com.killer560.hub.compat.McCompat.setScreen(client, new com.killer560.hub.gui.ColorPickerScreen(
                            com.killer560.hub.compat.McCompat.screen(client), "Tracer Color", cfg.getTracerColor(),
                            TeleportMazeSolverConfig.DEFAULT_TRACER_COLOR, argb -> {
                                cfg.setTracerColor(argb);
                                cfg.save();
                            }));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;
        widgets.add(onOffButton("Current Cell Only", cfg::isCurrentCellOnly, cfg::setCurrentCellOnly, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 22;
        widgets.add(onOffButton("Best Pad Only", cfg::isBestPadOnly, cfg::setBestPadOnly, cfg::save,
                null, contentX, y, contentWidth, 18));

        SolverEspRows.append(widgets, com.killer560.hub.puzzlesolvers.SolverEspConfig.Solver.TELEPORT_MAZE, contentX, contentWidth, y, true);

        return widgets;
    }
}
