package com.killer560.hub.gui.tab;

import java.util.ArrayList;
import java.util.List;

/** Folder tab grouping dungeon-related features: RNG Meter, Leap Message, the Terminal Solver
 *  (moved in from Helpers, 2026-09-09, per killer560's re-categorization request), Termism
 *  (2026-09-09, killer560's terminal-practice request), Secrets (2026-09-09, killer560's expanded
 *  block-hitbox request), and Auto Terminals (2026-09-09, cheat build only - per killer560's "make
 *  auto terms into its own section in dungeons" request; omitted from this list entirely on the legit
 *  build, which has no Auto Terminals functionality at all). */
public class DungeonTab extends FolderTab {

    public DungeonTab() {
        super("Dungeon", buildTabs());
    }

    private static List<BaseTab> buildTabs() {
        List<BaseTab> tabs = new ArrayList<>(List.of(
                new RngMeterTab(),
                new LeapMessageTab(),
                new TerminalSolverTab(),
                new TermismTab(),
                new SecretsTab()
        ));
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            tabs.add(new AutoTerminalTab());
        }
        return tabs;
    }
}
