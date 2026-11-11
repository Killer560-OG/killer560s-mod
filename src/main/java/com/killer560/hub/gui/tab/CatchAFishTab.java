package com.killer560.hub.gui.tab;

import com.killer560.hub.carnival.CatchAFishConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Catch a Fish Helper settings - see {@code carnival.CatchAFish}. */
public class CatchAFishTab extends BaseTab {

    public CatchAFishTab() {
        super("Catch a Fish Helper");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        CatchAFishConfig cfg = CatchAFishConfig.getInstance();
        Runnable save = cfg::save;
        int y = IslandRows.master(w, contentX, contentY, contentWidth, "Catch a Fish Helper", cfg::isEnabledRaw,
                cfg::setEnabled, save, requestRebuild);
        if (!cfg.isEnabledRaw() && !isSearchScan()) {
            return w;
        }
        int half = (contentWidth - 4) / 2;
        int colB = contentX + half + 4;
        IslandRows.toggle(w, contentX, y, half, "Fish Outlines", cfg::isOutlines, cfg::setOutlines, save);
        y = IslandRows.toggle(w, colB, y, half, "Yellow Only", cfg::isYellowOnly, cfg::setYellowOnly, save);
        IslandRows.toggle(w, contentX, y, half, "Best Fish", cfg::isBest, cfg::setBest, save);
        y = IslandRows.toggle(w, colB, y, half, "Line to Fish", cfg::isLine, cfg::setLine, save);
        IslandRows.toggle(w, contentX, y, half, "Fish HUD", cfg::isHud, cfg::setHud, save);
        y += 22;
        IslandRows.color(w, contentX, y, half, "Yellow Fish Color", cfg::getYellowColor, cfg::setYellowColor,
                CatchAFishConfig.DEFAULT_YELLOW_COLOR, save);
        IslandRows.color(w, colB, y, half, "Other Fish Color", cfg::getOtherColor, cfg::setOtherColor,
                CatchAFishConfig.DEFAULT_OTHER_COLOR, save);
        return w;
    }
}
