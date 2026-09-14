package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.puzzlesolvers.QuizSolverConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

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

        widgets.add(SettingsButtonWidget.builder(onOff("Quiz Solver", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Highlights the real Oruo trivia answer's floor tile once chat"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7reveals which lettered option is correct. Never answers for you."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
