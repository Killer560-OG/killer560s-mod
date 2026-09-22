package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.puzzlesolvers.TicTacToeSolverConfig;
import net.minecraft.client.gui.components.AbstractWidget;
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
        y += 22;
        widgets.add(SettingsButtonWidget.builder(styleText(cfg), btn -> {
                    cfg.setFill(!cfg.isFill());
                    cfg.save();
                    btn.setMessage(styleText(cfg));
                }).bounds(contentX, y, contentWidth, 18).build());

        return widgets;
    }

    private static Component styleText(TicTacToeSolverConfig cfg) {
        return Component.literal("Highlight Style: \u00a76" + (cfg.isFill() ? "Fill" : "Outline"));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
