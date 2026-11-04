package com.killer560.hub.gui.tab;

import com.killer560.hub.powdermining.PowderMiningConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** 2x Powder Alert (Mining): a title and sound when Hypixel's "2X POWDER STARTED!" line comes. */
public class DoublePowderAlertTab extends BaseTab {

    public DoublePowderAlertTab() {
        super("2x Powder Alert");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        PowderMiningConfig cfg = PowderMiningConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("2x Powder Alert", cfg::isDoublePowderRaw, cfg::setDoublePowder, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += MiningQolRows.STEP;
        MiningQolRows.toggle(w, contentX, y, contentWidth, "Sound", cfg::isDoublePowderSound, cfg::setDoublePowderSound,
                cfg::save);
        return w;
    }
}
