package com.killer560.hub.gui.tab;

import com.killer560.hub.puzzlehud.PuzzleHudConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Puzzle HUD (see {@link com.killer560.hub.puzzlehud.PuzzleHudFeature}): the switch, and which columns the rows carry. */
public class PuzzleHudTab extends BaseTab {

    public PuzzleHudTab() {
        super("Puzzle HUD");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        PuzzleHudConfig cfg = PuzzleHudConfig.getInstance();
        if (masterClosed(widgets, "Puzzle HUD", cfg::getEnabledRaw, cfg::setEnabled, cfg::save, requestRebuild, contentX, contentY, contentWidth)) {
            return widgets;
        }
        int colW = (contentWidth - 8) / 2;
        widgets.add(onOffButton("Puzzle Times", cfg::isShowTimes, cfg::setShowTimes, cfg::save, null, contentX, contentY + 24, colW, 18));
        widgets.add(onOffButton("Puzzle Solvers", cfg::isShowWho, cfg::setShowWho, cfg::save, null, contentX + colW + 8, contentY + 24, colW, 18));
        return widgets;
    }
}
