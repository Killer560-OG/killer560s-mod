package com.killer560.hub.gui.tab;

import java.util.ArrayList;
import java.util.List;

/** Dungeon > Map, Leap & Party (2026-10-07): the README's "Dungeon: Map, Leap & Party" features out of the removed New
 *  category. The cheat halves (Interactive Map, Fast/Auto Leap) sit under their legit sections, cheat build only. */
public class DungeonMapPartyTab extends FolderTab {

    public DungeonMapPartyTab() {
        super("Map, Leap & Party", buildTabs());
    }

    private static List<BaseTab> buildTabs() {
        boolean cheat = com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED;
        List<BaseTab> tabs = new ArrayList<>();
        tabs.add(new LiveMapTab());
        if (cheat) {
            tabs.add(new InteractiveMapTab());
        }
        tabs.add(new ChunkCacheTab());
        tabs.add(new LeapCounterTab());
        if (cheat) {
            tabs.add(new FastLeapTab());
        }
        tabs.add(new DungeonQueueTab());
        tabs.add(new AutoKickTab());
        tabs.add(new ClassColorsTab());
        tabs.add(new TeammatesTab());
        tabs.add(new TeamMelodyTab());
        tabs.add(new InteropTab());
        return tabs;
    }
}
