package com.killer560.hub.gui.tab;

import com.killer560.hub.mining.puzzler.PuzzlerSolver;

/** Puzzler Solver settings: see {@link PuzzlerSolver}. */
public class PuzzlerSolverTab extends IslandFeatureTab {

    public PuzzlerSolverTab() {
        super("Puzzler Solver", PuzzlerSolver.CFG, false, "Boxes the Puzzler's block.");
    }

    @Override
    protected void rows(Rows r) {
        r.toggle("Show Box", "box");
        r.toggle("Chat Coordinates", "chat");
    }
}
