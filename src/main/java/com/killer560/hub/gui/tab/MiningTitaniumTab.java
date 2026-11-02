package com.killer560.hub.gui.tab;

import com.killer560.hub.miningqol.MiningQolConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Titanium Highlight (pre-2.0 Mining): see {@code com.killer560.hub.miningqol}. */
public class MiningTitaniumTab extends BaseTab {

    public MiningTitaniumTab() {
        super("Titanium Highlight");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Titanium Highlight", () -> cfg.isTitaniumRaw(), cfg::setTitanium, cfg::save, requestRebuild, contentX, y,
                contentWidth, 20));
        y += MiningQolRows.STEP;

        MiningQolRows.cycle(w, contentX, y, contentWidth, "Show", () -> cfg.getTitaniumMode().label(),
                dir -> cfg.setTitaniumMode(MiningQolConfig.TitaniumMode.values()[Math.floorMod(
                        cfg.getTitaniumMode().ordinal() + dir, MiningQolConfig.TitaniumMode.values().length)]), cfg::save);
        y += MiningQolRows.STEP;
        MiningQolRows.toggle(w, contentX, y, contentWidth, "Universal Area Access", cfg::isUniversalAccess,
                cfg::setUniversalAccess, cfg::save);
        y += MiningQolRows.STEP;
        MiningQolRows.color(w, contentX, y, contentWidth, "Titanium Color", cfg::getTitaniumColor,
                cfg::setTitaniumColor, MiningQolConfig.DEFAULT_TITANIUM_COLOR, cfg::save);
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            y += MiningQolRows.STEP + 4;
            MiningQolRows.cheatHeader(w, contentX, y, contentWidth);
            y += 16;
            MiningQolRows.toggle(w, contentX, y, contentWidth, "Through Walls", cfg::isTitaniumEsp, cfg::setTitaniumEsp,
                    cfg::save);
        }
        return w;
    }
}
