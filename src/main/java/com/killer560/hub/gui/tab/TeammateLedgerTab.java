package com.killer560.hub.gui.tab;

import com.killer560.hub.teamledger.TeammateLedger;

/** Teammate Ledger (Dungeons): see {@link TeammateLedger}. */
public class TeammateLedgerTab extends IslandFeatureTab {

    public TeammateLedgerTab() {
        super("Teammate Ledger", TeammateLedger.CFG, false, "Your runs with a player.");
    }

    @Override
    protected void rows(Rows r) {
        r.toggle("Show On Party Finder Join", "onJoin");
        r.note("Or type /teammate NAME any time.");
    }
}
