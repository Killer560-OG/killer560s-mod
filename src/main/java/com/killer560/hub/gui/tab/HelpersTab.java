package com.killer560.hub.gui.tab;

import java.util.List;

/** Folder tab grouping active gameplay-assist features: the Experimentation Table solver (split out
 *  from its own top-level slot, 2026-09-07, per killer560's re-categorization request) and the Floor 7
 *  Terminal Solver (2026-09-08). */
public class HelpersTab extends FolderTab {

    public HelpersTab() {
        super("Helpers", List.of(
                new ExperimentsTab(),
                new TerminalSolverTab()
        ));
    }
}
