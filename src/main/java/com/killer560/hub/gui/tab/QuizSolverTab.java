package com.killer560.hub.gui.tab;

import com.killer560.hub.puzzlesolvers.QuizSolverConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Quiz Solver settings - see {@link com.killer560.hub.puzzlesolvers.QuizSolverFeature}'s class doc for
 *  the real Odin-ported trivia-answer database this is built on. */
public class QuizSolverTab extends BaseTab {

    public QuizSolverTab() {
        super("Quiz Solver");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        QuizSolverConfig cfg = QuizSolverConfig.getInstance();

        widgets.add(onOffButton("Quiz Solver", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        SolverEspRows.append(widgets, com.killer560.hub.puzzlesolvers.SolverEspConfig.Solver.QUIZ, contentX, contentWidth, y, true);

        return widgets;
    }
}
