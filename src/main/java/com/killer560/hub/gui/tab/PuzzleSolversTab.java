package com.killer560.hub.gui.tab;

import java.util.ArrayList;
import java.util.List;

/** Every dungeon puzzle solver in one place, instead of eleven separate entries scattered through the New
 *  tab (killer560, 2026-09-16: "put all solvers like ice fill quiz and whatnot under one tab called puzzle
 *  solvers then make an auto puzzle tab for the cheat variant with all the cheat stuff").
 *  <p>
 *  The solvers themselves only ever show you the answer - they are on both builds. Auto Puzzles, which
 *  actually plays the puzzle for you, is the cheat half and is appended only on the cheat jar, so the legit
 *  build has no such section at all rather than a disabled-looking one. Simon Says keeps its own entry in
 *  the Dungeon tab: it is the one solver with a whole live-tested automation story attached to it. */
public class PuzzleSolversTab extends FolderTab {

    public PuzzleSolversTab() {
        super("Puzzle Solvers", buildTabs());
    }

    private static List<BaseTab> buildTabs() {
        List<BaseTab> tabs = new ArrayList<>(List.of(
                new BoulderSolverTab(),
                new QuizSolverTab(),
                new IceFillSolverTab(),
                new IcePathSolverTab(),
                new WeirdosSolverTab(),
                new WaterSolverTab(),
                new BeamsSolverTab(),
                new BlazeSolverTab(),
                new TicTacToeSolverTab(),
                new TeleportMazeSolverTab(),
                new LividSolverTab()
        ));
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            tabs.add(new AutoPuzzlesTab());
        }
        return tabs;
    }
}
