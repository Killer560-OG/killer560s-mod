package com.killer560.hub.gui.tab;

import com.killer560.hub.missingenchants.MissingEnchantsConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Missing Enchants (see {@code MissingEnchantsFeature}): hold Shift over an item to list the enchants it could still take. Ships OFF. */
public class MissingEnchantsTab extends BaseTab {

    public MissingEnchantsTab() {
        super("Missing Enchants");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        MissingEnchantsConfig cfg = MissingEnchantsConfig.getInstance();
        if (masterClosed(widgets, "Missing Enchants", cfg::getEnabledRaw, cfg::setEnabled, cfg::save, requestRebuild, contentX, contentY, contentWidth)) {
            return widgets;
        }
        int y = contentY + 26;
        widgets.add(onOffButton("Show Enchants Below Max Level", cfg::isBelowMax, cfg::setBelowMax, cfg::save, null, contentX, y, contentWidth, 20));
        y += 26;
        widgets.add(onOffButton("Shift Hint Line", cfg::isHint, cfg::setHint, cfg::save, null, contentX, y, contentWidth, 20));
        return widgets;
    }
}
