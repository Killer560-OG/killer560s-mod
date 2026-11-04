package com.killer560.hub.gui.tab;

import com.killer560.hub.mining.fossil.FossilSolver;

/** Fossil Solver settings: see {@link FossilSolver}. */
public class FossilSolverTab extends IslandFeatureTab {

    public FossilSolverTab() {
        super("Fossil Solver", FossilSolver.CFG, false, "Best tiles in the Excavator.");
    }

    @Override
    protected void rows(Rows r) {
        r.toggle("Show Chances", "chances");
        r.toggle("Solver Info Line", "info");
    }
}
