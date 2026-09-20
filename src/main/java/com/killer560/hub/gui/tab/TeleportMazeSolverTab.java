package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.puzzlesolvers.TeleportMazeSolverConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

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

        widgets.add(SettingsButtonWidget.builder(onOff("Teleport Maze Solver", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Tracer To Best Pad", cfg.isShowTracer()), btn -> {
                    cfg.setShowTracer(!cfg.isShowTracer());
                    cfg.save();
                    btn.setMessage(onOff("Tracer To Best Pad", cfg.isShowTracer()));
                }).bounds(contentX, y, contentWidth, 18).build());

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
