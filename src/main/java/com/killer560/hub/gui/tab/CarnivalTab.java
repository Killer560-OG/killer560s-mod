package com.killer560.hub.gui.tab;

import java.util.ArrayList;
import java.util.List;

/**
 * The Carnival category's tabs (carnival-1110, ships before 2.0): solvers, helpers and a tracker for the Hub's Carnival minigames
 * during Foxy's Chivalrous Carnival. The legit cards first; the cheat-only autos behind
 * {@code BuildVariant.CHEAT_FEATURES_ENABLED}, drawn by {@link MenuCategories#cheatLast} under the red "Cheat" header.
 */
public class CarnivalTab extends FolderTab {

    public CarnivalTab() {
        super("Carnival", buildTabs());
    }

    private static List<BaseTab> buildTabs() {
        List<BaseTab> tabs = new ArrayList<>();
        tabs.add(new CarnivalTrackerTab());
        tabs.add(new FruitDiggingTab());
        tabs.add(new ZombieShootoutTab());
        tabs.add(new CatchAFishTab());
        // carnival-legit-tabs-end
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            tabs.add(new ZombieTriggerbotTab());
            tabs.add(new AutoZombieShootoutTab());
            tabs.add(new AutoShovelSwapTab());
            tabs.add(new AutoFruitDiggingTab());
            tabs.add(new FruitDiggingTriggerbotTab());
            tabs.add(new AutoCatchAFishTab());
            // carnival-cheat-tabs-end
        }
        return tabs;
    }
}
