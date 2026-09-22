package com.killer560.hub.gui.tab;

import java.util.List;

/** Folder tab grouping every puzzle/boss solver's world highlight in one place, inside Dungeon - killer560,
 *  2026-09-20, after testing the "move everything into New" round from earlier the same day: "put every
 *  puzzle solver back under one tab called Puzzle Solvers inside the dungeon tab... separate from Auto
 *  Puzzles". Solver Highlights (the one shared "Through Walls" toggle - see
 *  {@link com.killer560.hub.puzzlesolvers.SolverEspConfig}) lives here too since it's meaningless outside
 *  this folder. Auto Puzzles (the cheat-only half that actually plays the puzzle for you) is deliberately
 *  NOT in this list - it stays its own entry directly under {@link DungeonTab}, same as before. */
public class PuzzleSolversTab extends FolderTab {

    public PuzzleSolversTab() {
        super("Puzzle Solvers", List.of(
                new SolverHighlightsTab(),
                new BoulderSolverTab(),
                new QuizSolverTab(),
                new IceFillSolverTab(),
                new IcePathSolverTab(),
                new WeirdosSolverTab(),
                new WaterSolverTab(),
                new BeamsSolverTab(),
                new BlazeSolverTab(),
                new TicTacToeSolverTab(),
                new TeleportMazeSolverTab()
        ));
    }
}
