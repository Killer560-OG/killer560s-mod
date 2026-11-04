package com.killer560.hub.gui.tab;

import java.util.ArrayList;
import java.util.List;

/** The Crystal Hollows powder grinding tabs (com.killer560.hub.powdermining), added to the Mining category's list. */
final class PowderMiningTabs {

    private PowderMiningTabs() {
    }

    static List<BaseTab> with(List<? extends BaseTab> mining) {
        List<BaseTab> out = new ArrayList<>(mining);
        out.add(new TreasureChestHighlightTab());
        out.add(new TreasureChestAlertTab());
        out.add(new PowderTrackerTab());
        out.add(new DoublePowderAlertTab());
        out.add(new ChestLootCompactTab());
        out.add(new LockpickHelperTab());
        return out;
    }
}
