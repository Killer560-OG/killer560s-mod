package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.puzzlesolvers.WaterSolverConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Water Board Solver settings - see {@link com.killer560.hub.puzzlesolvers.WaterSolverFeature}'s class
 *  doc for the real Odin-ported lever-timing database this is built on. */
public class WaterSolverTab extends BaseTab {

    public WaterSolverTab() {
        super("Water Board Solver");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        WaterSolverConfig cfg = WaterSolverConfig.getInstance();
        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2aX = contentX;
        int col2bX = contentX + col2W + gap;

        widgets.add(SettingsButtonWidget.builder(onOff("Water Board Solver", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Show Tracer", cfg.isShowTracer()), btn -> {
                    cfg.setShowTracer(!cfg.isShowTracer());
                    cfg.save();
                    btn.setMessage(onOff("Show Tracer", cfg.isShowTracer()));
                }).bounds(col2aX, y, col2W, 18).build());

        // Renamed from "Alt. Timing" (killer560, 2026-09-20: "rename alt timing to optimized solutions") -
        // same setting, same data (Odin's alternate lever-timing set), clearer label.
        widgets.add(SettingsButtonWidget.builder(onOff("Optimized Solutions", cfg.isOptimizedPath()), btn -> {
                    cfg.setOptimizedPath(!cfg.isOptimizedPath());
                    cfg.save();
                    btn.setMessage(onOff("Optimized Solutions", cfg.isOptimizedPath()));
                }).bounds(col2bX, y, col2W, 18).build());

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
