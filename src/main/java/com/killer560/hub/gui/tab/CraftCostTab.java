package com.killer560.hub.gui.tab;

import com.killer560.hub.craftcost.CraftCostConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Craft Cost settings - see {@link com.killer560.hub.craftcost.CraftCost}: only the switch. */
public class CraftCostTab extends BaseTab {

    public CraftCostTab() {
        super("Craft Cost");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        CraftCostConfig cfg = CraftCostConfig.getInstance();
        masterClosed(widgets, "Craft Cost", cfg::isEnabled, cfg::setEnabled, cfg::save, requestRebuild, contentX,
                contentY, contentWidth);
        return widgets;
    }
}
