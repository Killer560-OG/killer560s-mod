package com.killer560.hub.gui.tab;

import java.util.ArrayList;
import java.util.List;

/** Folder tab grouping active gameplay-assist features - currently just the Experimentation Table
 *  solver, split out from its own top-level slot (2026-09-07) per killer560's re-categorization request.
 *  The Floor 7 Terminal Solver briefly lived here too (2026-09-08) but moved to Dungeon (2026-09-09),
 *  per killer560's own follow-up request. */
public class HelpersTab extends FolderTab {

    public HelpersTab() {
        super("Helpers", buildTabs());
    }

    private static List<BaseTab> buildTabs() {
        List<BaseTab> tabs = new ArrayList<>(List.of(
                new ExperimentsTab(),
                // Moved out of New 2026-09-16.
                new EtherwarpOverlayTab()
        ));
        // The autonomous Experimentation Table macro got its own section here 2026-09-30, per killer560:
        // "Move the auto etable stuff into its own red header in the same helpers tab but different area."
        // Cheat build only, so FolderTab draws its accordion header red (AutoExperimentsTab#isCheatOnly) -
        // that IS the red header. The legit build has no such section at all, same as Auto Terminals and
        // Auto Puzzles over in Dungeon.
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            tabs.add(new AutoExperimentsTab());
        }
        return tabs;
    }
}
