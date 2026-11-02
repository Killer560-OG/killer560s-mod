package com.killer560.hub.gui.tab;

import com.killer560.hub.miningqol.MiningQolConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Carpet Highlighter (pre-2.0 Mining): see {@code com.killer560.hub.miningqol}. */
public class MiningCarpetTab extends BaseTab {

    public MiningCarpetTab() {
        super("Carpet Highlighter");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Carpet Highlighter", () -> cfg.isCarpetRaw(), cfg::setCarpet, cfg::save, requestRebuild, contentX, y,
                contentWidth, 20));
        y += MiningQolRows.STEP;

        MiningQolRows.color(w, contentX, y, contentWidth, "Carpet Color", cfg::getCarpetColor, cfg::setCarpetColor,
                MiningQolConfig.DEFAULT_CARPET_COLOR, cfg::save);
        return w;
    }
}
