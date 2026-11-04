package com.killer560.hub.gui.tab;

import com.killer560.hub.powdermining.PowderMiningConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Chest Loot Compact (Mining): see {@code com.killer560.hub.powdermining.PowderChat}. */
public class ChestLootCompactTab extends BaseTab {

    public ChestLootCompactTab() {
        super("Chest Loot Compact");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        PowderMiningConfig cfg = PowderMiningConfig.getInstance();
        w.add(onOffButton("Chest Loot Compact", cfg::isLootCompactRaw, cfg::setLootCompact, cfg::save,
                requestRebuild, contentX, contentY, contentWidth, 20));
        MiningQolRows.cycle(w, contentX, contentY + MiningQolRows.STEP, contentWidth, "Show", () -> cfg.getLootShow().label(),
                cfg::stepLootShow, cfg::save);
        w.add(onOffButton("Hide Treasure Chest Uncovered", cfg::isHideTreasureUncoveredRaw, cfg::setHideTreasureUncovered,
                cfg::save, requestRebuild, contentX, contentY + 2 * MiningQolRows.STEP, contentWidth, 20));
        w.add(onOffButton("Keep Rare Rewards", cfg::isKeepRareRewards, cfg::setKeepRareRewards,
                cfg::save, requestRebuild, contentX, contentY + 3 * MiningQolRows.STEP, contentWidth, 20));
        return w;
    }
}
