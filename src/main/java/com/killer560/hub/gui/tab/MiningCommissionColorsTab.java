package com.killer560.hub.gui.tab;

import com.killer560.hub.commissioncolors.CommissionColorsConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Commission Block Colors (Mining): see {@code com.killer560.hub.commissioncolors}. */
public class MiningCommissionColorsTab extends BaseTab {

    public MiningCommissionColorsTab() {
        super("Commission Block Colors");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        CommissionColorsConfig cfg = CommissionColorsConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Commission Block Colors", cfg::isEnabled, cfg::setEnabled, cfg::save, requestRebuild, contentX, y,
                contentWidth, 20));
        if (!cfg.isEnabled() && !isSearchScan()) {
            return w;
        }
        y += MiningQolRows.STEP;
        MiningQolRows.toggle(w, contentX, y, contentWidth, "Highlight Wanted Blocks", cfg::isHighlight, cfg::setHighlight,
                cfg::save);
        y += MiningQolRows.STEP;
        MiningQolRows.color(w, contentX, y, contentWidth, "Highlight Color", cfg::getColor, cfg::setColor,
                CommissionColorsConfig.defaultColor(), cfg::save);
        y += MiningQolRows.STEP;
        MiningQolRows.toggle(w, contentX, y, contentWidth, "Dim Other Ores", cfg::isDimOthers, cfg::setDimOthers, cfg::save);
        return w;
    }
}
