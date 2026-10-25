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

        widgets.add(onOffButton("Tic Tac Toe Solver", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(onOffButton("Show Prediction", cfg::isShowPrediction, cfg::setShowPrediction, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 22;
        widgets.add(onOffButton("Prevent Misclicks", cfg::isPreventMisclicks, cfg::setPreventMisclicks, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 22;
        widgets.add(SettingsButtonWidget.cycling(styleText(cfg), (btn, dir) -> {
                    cfg.setFill(!cfg.isFill());
                    cfg.save();
                    btn.setMessage(styleText(cfg));
                }).bounds(contentX, y, contentWidth, 18).build());

        SolverEspRows.append(widgets, com.killer560.hub.puzzlesolvers.SolverEspConfig.Solver.TIC_TAC_TOE, contentX, contentWidth, y, false);

        return widgets;
    }

    private static Component styleText(TicTacToeSolverConfig cfg) {
        return Component.literal("Highlight Style: \u00a76" + (cfg.isFill() ? "Fill" : "Outline"));
    }
}
