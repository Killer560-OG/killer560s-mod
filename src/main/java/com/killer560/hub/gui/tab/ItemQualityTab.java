package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonqol.DungeonQolConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Item Quality tooltip switch - see {@link com.killer560.hub.dungeonqol.ItemQualityFeature}. */
public class ItemQualityTab extends BaseTab {

    public ItemQualityTab() {
        super("Item Quality");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        DungeonQolConfig cfg = DungeonQolConfig.getInstance();
        List<AbstractWidget> widgets = new ArrayList<>();
        widgets.add(onOffButton("Item Quality", cfg::isItemQualityRaw, cfg::setItemQuality, cfg::save,
                null, contentX, contentY, contentWidth, 20));
        return widgets;
    }
}
