package com.killer560.hub.gui.tab;

import java.util.ArrayList;
import java.util.List;

/**
 * The Mining category's tabs before 2.0 (killer560's to-do Doc, 2026-11-01: only these mining features before 2.0; the
 * rest of mining stays on the post-2.0 staging branches). {@link MenuCategories} puts them under "Mining".
 */
public class MiningQolTab extends FolderTab {

    public MiningQolTab() {
        super("Mining", tabs());
    }

    private static List<BaseTab> tabs() {
        List<BaseTab> t = new ArrayList<>(List.of(new MiningCommissionHudTab(), new MiningCommissionRouteTab(), new MiningPickobulusTab(), new MiningAbilityReadyTab(),
                new MiningCarpetTab(), new MiningMithrilStoneTab(), new MiningTitaniumTab(), new MiningRaffleTab(),
                new MiningResumeBreakTab(), new MiningPowderGhastTab(), new MiningWrongArmorTab()));
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            t.add(new MiningPigeonDrillTab());
        }
        return t;
    }
}
