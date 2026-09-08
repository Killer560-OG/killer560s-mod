package com.killer560.hub.gui.tab;

import java.util.List;

/** Folder tab grouping active gameplay-assist features - currently just the Experimentation Table
 *  solver, split out from its own top-level slot (2026-09-07) per killer560's re-categorization request.
 *  A natural home for future solver/helper-style features. */
public class HelpersTab extends FolderTab {

    public HelpersTab() {
        super("Helpers", List.of(
                new ExperimentsTab()
        ));
    }
}
