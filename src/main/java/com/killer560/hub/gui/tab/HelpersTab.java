package com.killer560.hub.gui.tab;

import java.util.List;

/** Folder tab grouping active gameplay-assist features - currently just the Experimentation Table
 *  solver, split out from its own top-level slot (2026-09-07) per killer560's re-categorization request.
 *  The Floor 7 Terminal Solver briefly lived here too (2026-09-08) but moved to Dungeon (2026-09-09),
 *  per killer560's own follow-up request. */
public class HelpersTab extends FolderTab {

    public HelpersTab() {
        super("Helpers", List.of(
                new ExperimentsTab()
        ));
    }
}
