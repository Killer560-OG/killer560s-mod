package com.killer560.hub.gui.tab;

import java.util.List;

/** Folder tab grouping dungeon-related features: RNG Meter, Leap Message, the Terminal Solver
 *  (moved in from Helpers, 2026-09-09, per killer560's re-categorization request), Termism
 *  (2026-09-09, killer560's terminal-practice request), and Secrets (2026-09-09, killer560's expanded
 *  block-hitbox request). */
public class DungeonTab extends FolderTab {

    public DungeonTab() {
        super("Dungeon", List.of(
                new RngMeterTab(),
                new LeapMessageTab(),
                new TerminalSolverTab(),
                new TermismTab(),
                new SecretsTab()
        ));
    }
}
