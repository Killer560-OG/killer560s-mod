package com.killer560.hub.gui.tab;

import com.killer560.hub.carnival.FruitDiggingConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Fruit Digging Solver settings - see {@code carnival.FruitDigging}. */
public class FruitDiggingTab extends BaseTab {

    public FruitDiggingTab() {
        super("Fruit Digging Solver");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        FruitDiggingConfig cfg = FruitDiggingConfig.getInstance();
        Runnable save = cfg::save;
        int y = IslandRows.master(w, contentX, contentY, contentWidth, "Fruit Digging Solver", cfg::isEnabledRaw,
                cfg::setEnabled, save, requestRebuild);
        if (!cfg.isEnabledRaw() && !isSearchScan()) {
            return w;
        }
        int half = (contentWidth - 4) / 2;
        int colB = contentX + half + 4;
        IslandRows.toggle(w, contentX, y, half, "Best Dig", cfg::isBestDig, cfg::setBestDig, save);
        y = IslandRows.toggle(w, colB, y, half, "Clue Labels", cfg::isClues, cfg::setClues, save);
        IslandRows.toggle(w, contentX, y, half, "Fruit Labels", cfg::isFound, cfg::setFound, save);
        y = IslandRows.toggle(w, colB, y, half, "Tile Odds", cfg::isOdds, cfg::setOdds, save);
        IslandRows.toggle(w, contentX, y, half, "Solver HUD", cfg::isHud, cfg::setHud, save);
        y = IslandRows.toggle(w, colB, y, half, "Fruit Left", cfg::isRemaining, cfg::setRemaining, save);
        y = IslandRows.header(w, contentX, y + 2, contentWidth, "Best Dig Color per Shovel Mode");
        IslandRows.color(w, contentX, y, half, "Mines Color", cfg::getMinesColor, cfg::setMinesColor,
                FruitDiggingConfig.DEFAULT_MINES_COLOR, save);
        y = IslandRows.color(w, colB, y, half, "Anchor Color", cfg::getAnchorColor, cfg::setAnchorColor,
                FruitDiggingConfig.DEFAULT_ANCHOR_COLOR, save);
        y = IslandRows.color(w, contentX, y, half, "Treasure Color", cfg::getTreasureColor, cfg::setTreasureColor,
                FruitDiggingConfig.DEFAULT_TREASURE_COLOR, save);
        y = IslandRows.header(w, contentX, y + 2, contentWidth, "Click Guards");
        IslandRows.toggle(w, contentX, y, half, "Prevent Misclicks", cfg::isPreventMisclicks, cfg::setPreventMisclicks,
                save);
        y = IslandRows.toggle(w, colB, y, half, "Prevent Wrong Shovel", cfg::isPreventWrongShovel,
                cfg::setPreventWrongShovel, save);
        IslandRows.toggle(w, contentX, y, half, "Sneak To Dig Anyway", cfg::isSneakAllows, cfg::setSneakAllows, save);
        return w;
    }
}
