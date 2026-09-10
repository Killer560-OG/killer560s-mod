package com.killer560.hub.gui.tab;

import java.util.ArrayList;
import java.util.List;

/** Folder tab grouping dungeon-related features: RNG Meter, Leap Message, the Terminal Solver
 *  (moved in from Helpers, 2026-09-09, per killer560's re-categorization request), and Termism
 *  (2026-09-09, killer560's terminal-practice request) on every build; Full Block/hitboxes and Auto
 *  Terminals (2026-09-10, cheat build only - per killer560's "cheat variant should have hitbox's auto
 *  etable and auto terms" request) are omitted from this list entirely on the legit build, which has
 *  neither feature at all, not just a disabled-looking version of them. */
public class DungeonTab extends FolderTab {

    public DungeonTab() {
        super("Dungeon", buildTabs());
    }

    private static List<BaseTab> buildTabs() {
        List<BaseTab> tabs = new ArrayList<>(List.of(
                new RngMeterTab(),
                new LeapMessageTab(),
                new TerminalSolverTab(),
                new TermismTab()
        ));
        // Per killer560's explicit "cheat variant should have hitbox's auto etable and auto terms"
        // request (2026-09-10) - Full Block (hitbox expansion) moved from always-available to cheat-only
        // here, joining Auto Terminals; the legit build has neither tab at all, not just a
        // disabled-looking one (matching SecretsConfig#isMasterEnabled's own gate underneath).
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            tabs.add(new SecretsTab());
            tabs.add(new AutoTerminalTab());
        }
        return tabs;
    }
}
