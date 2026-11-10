package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonqol.DungeonQolConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Block Ender Pearls In Boss switch - see {@link com.killer560.hub.dungeonqol.BossPearlBlock}. */
public class BossPearlTab extends BaseTab {

    public BossPearlTab() {
        super("Block Ender Pearls In Boss");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        DungeonQolConfig cfg = DungeonQolConfig.getInstance();
        List<AbstractWidget> widgets = new ArrayList<>();
        widgets.add(onOffButton("Block Ender Pearls In Boss", cfg::isBlockBossPearls, cfg::setBlockBossPearls, cfg::save,
                null, contentX, contentY, contentWidth, 20));
        return widgets;
    }
}
