package com.killer560.hub.gui.tab;

import com.killer560.hub.mining.fetchur.FetchurSolver;

/** Fetchur Solver settings: see {@link FetchurSolver}. */
public class FetchurSolverTab extends IslandFeatureTab {

    public FetchurSolverTab() {
        super("Fetchur Solver", FetchurSolver.CFG, false, "Names the item Fetchur wants.");
    }

    @Override
    protected void rows(Rows r) {
        r.toggle("Answer In Chat", "chat");
        r.toggle("Answer Title", "title");
    }
}
