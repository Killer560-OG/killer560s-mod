package com.killer560.hub.gui.tab;

import com.killer560.hub.powdermining.PowderMiningConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Treasure Chest Alert (Mining): a sound (and optional title) on "You uncovered a treasure chest!". */
public class TreasureChestAlertTab extends BaseTab {

    public TreasureChestAlertTab() {
        super("Treasure Chest Alert");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        PowderMiningConfig cfg = PowderMiningConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Treasure Chest Alert", cfg::isChestAlertRaw, cfg::setChestAlert, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += MiningQolRows.STEP;
        MiningQolRows.toggle(w, contentX, y, contentWidth, "Sound", cfg::isChestAlertSound, cfg::setChestAlertSound,
                cfg::save);
        y += MiningQolRows.STEP;
        MiningQolRows.toggle(w, contentX, y, contentWidth, "Title", cfg::isChestAlertTitle, cfg::setChestAlertTitle,
                cfg::save);
        return w;
    }
}
