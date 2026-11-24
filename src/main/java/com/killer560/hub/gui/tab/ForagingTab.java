package com.killer560.hub.gui.tab;

import java.util.List;

/**
 * The Foraging category's tabs (killer560, 2026-11-23: "make sure these new foraging perks are under their own foraging
 * tab"). {@link MenuCategories} puts them under "Foraging"; the testing build never moves them to Untested (they go before
 * 2.0). Cards of foragebeacon-solver-1122 and invisibug-1122 belong here once those ship.
 */
public class ForagingTab extends FolderTab {

    public ForagingTab() {
        super("Foraging", List.of(new AgathaContestOverlayTab(), new BeaconReadyAlertTab(), new BeeheemothHighlightTab(), new InvisibugHighlightTab(), new TreeGiftMessagesTab(), new TreeRegenTab()));
    }
}
