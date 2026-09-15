package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.puzzlesolvers.TicTacToeSolverConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Tic Tac Toe Solver settings - see {@link com.killer560.hub.puzzlesolvers.TicTacToeSolverFeature}. */
public class TicTacToeSolverTab extends BaseTab {

    public TicTacToeSolverTab() {
        super("Tic Tac Toe Solver");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        TicTacToeSolverConfig cfg = TicTacToeSolverConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Tic Tac Toe Solver", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Show Prediction", cfg.isShowPrediction()), btn -> {
                    cfg.setShowPrediction(!cfg.isShowPrediction());
                    cfg.save();
                    btn.setMessage(onOff("Show Prediction", cfg.isShowPrediction()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Reads the map item frames and outlines the best move (green) on your turn."),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Prediction outlines your likely next move (yellow). Never clicks."),
                Minecraft.getInstance().font));
        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
