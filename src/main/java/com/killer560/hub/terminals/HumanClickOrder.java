package com.killer560.hub.terminals;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stream Safe's choice of WHICH slot to go for next (killer560, 2026-10-27: "make it as human as possible for clicks
 * though it shouldn't always be left to right").
 * <p>
 * It only ever chooses among slots the Terminal Solver highlights right now and that have no click waiting on the server,
 * so every order it produces is a valid solve: Panes, Select and Starts With accept their correct slots in any order,
 * Rubix panes cycle independently, and Numbers has one fixed order (only the primary slot is ever clicked; while its click
 * is unanswered the cursor heads for the next number, the way a person reads ahead). A slot that still needs clicks after
 * one (a Rubix pane needing 2) stays the target, so the cursor stays on it instead of leaving and coming back.
 * <p>
 * A person works a board in one of a few habits, and drifts between them: the nearest next slot (sometimes skipping to the
 * second nearest), a reading-order sweep whose direction flips row to row, or a column sweep. Which habit, how often it
 * skips and whether it starts top or bottom is rolled once per terminal, so two terminals never look the same.
 */
final class HumanClickOrder {

    private static final int GRID_COLUMNS = 9;
    private static final int NEAREST = 0;
    private static final int ROWS = 1;
    private static final int COLUMNS = 2;

    private static int committed = -1;
    private static int lastSlot = -1;
    private static int style = NEAREST;
    private static float skipChance = 0.12f;
    private static boolean reverse;
    private static boolean firstLeftToRight = true;

    /** Picks so far whose slot was not the first in plain reading order (testkit 280-285 read it). */
    static int picks;
    static int picksNotReadingOrder;

    private HumanClickOrder() {
    }

    /** A new terminal: a fresh habit. */
    static void begin() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        float roll = r.nextFloat();
        style = roll < 0.5f ? NEAREST : roll < 0.78f ? ROWS : COLUMNS;
        skipChance = 0.06f + 0.14f * r.nextFloat();
        reverse = r.nextFloat() < 0.35f;
        firstLeftToRight = r.nextFloat() < 0.6f;
        committed = -1;
        lastSlot = -1;
    }

    /** A quick reopen of the same terminal: clicks start over, the habit stays. */
    static void resetClicks() {
        committed = -1;
    }

    /** The slot just clicked; {@code needsMore} keeps it as the target (the cursor stays on it). */
    static void clicked(int slot, boolean needsMore) {
        lastSlot = slot;
        committed = needsMore ? slot : -1;
    }

    /**
     * @param waiting slots with a click the server has not answered yet (and not overdue)
     * @return the slot the cursor should head for, or -1 for none.
     */
    static int next(TerminalType type, Map<Integer, TerminalSolverFeature.SlotHighlight> highlights,
                    java.util.function.IntPredicate waiting) {
        if (highlights.isEmpty()) {
            committed = -1;
            return -1;
        }
        if (type == TerminalType.NUMBERS) {
            int primary = -1;
            int following = -1;
            for (Map.Entry<Integer, TerminalSolverFeature.SlotHighlight> e : highlights.entrySet()) {
                if (e.getValue().primary()) {
                    primary = e.getKey();
                } else if (following < 0) {
                    following = e.getKey();
                }
            }
            if (primary >= 0 && waiting.test(primary) && following >= 0) {
                return following;
            }
            return primary;
        }
        // Stay on a slot that still needs clicks. While its click is unanswered the slot can read empty (Rubix's click
        // is a real PICKUP, which the client predicts by lifting the pane until the server answers), so an unanswered
        // click keeps it too; leaving there was the "goes off it and comes back" the owner saw (2026-10-27).
        if (committed >= 0 && (highlights.containsKey(committed) || waiting.test(committed))) {
            return committed;
        }
        committed = -1;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (r.nextFloat() < 0.08f) {
            // Habits drift mid-board.
            style = r.nextInt(3);
        }
        int fromCol;
        int fromRow;
        if (lastSlot >= 0) {
            fromCol = lastSlot % GRID_COLUMNS;
            fromRow = lastSlot / GRID_COLUMNS;
        } else {
            // First pick: from the middle of the board, where the pointer starts.
            int sc = 0;
            int sr = 0;
            int n = 0;
            for (int slot : highlights.keySet()) {
                sc += slot % GRID_COLUMNS;
                sr += slot / GRID_COLUMNS;
                n++;
            }
            fromCol = Math.round(sc / (float) n);
            fromRow = Math.round(sr / (float) n);
        }
        int best = -1;
        float bestKey = Float.MAX_VALUE;
        int second = -1;
        float secondKey = Float.MAX_VALUE;
        int readingFirst = -1;
        for (int slot : highlights.keySet()) {
            if (waiting.test(slot)) {
                continue;
            }
            if (readingFirst < 0 || slot < readingFirst) {
                readingFirst = slot;
            }
            float key = key(slot, fromCol, fromRow);
            if (key < bestKey) {
                second = best;
                secondKey = bestKey;
                best = slot;
                bestKey = key;
            } else if (key < secondKey) {
                second = slot;
                secondKey = key;
            }
        }
        if (best < 0) {
            return -1;
        }
        int pick = best;
        if (second >= 0 && r.nextFloat() < skipChance) {
            pick = second;
        }
        picks++;
        if (pick != readingFirst) {
            picksNotReadingOrder++;
        }
        committed = pick;
        return pick;
    }

    /** Smaller is sooner, for the current habit. */
    private static float key(int slot, int fromCol, int fromRow) {
        int col = slot % GRID_COLUMNS;
        int row = slot / GRID_COLUMNS;
        switch (style) {
            case ROWS -> {
                int r = reverse ? -row : row;
                boolean ltr = (Math.floorMod(row, 2) == 0) == firstLeftToRight;
                return r * 100f + (ltr ? col : -col);
            }
            case COLUMNS -> {
                int c = reverse ? -col : col;
                boolean down = (Math.floorMod(col, 2) == 0) == firstLeftToRight;
                return c * 100f + (down ? row : -row);
            }
            default -> {
                float dc = col - fromCol;
                float dr = row - fromRow;
                // Rows are a little farther apart than columns feel, and a tiny bias keeps ties from always going left.
                return dc * dc + 1.15f * dr * dr + (reverse ? -0.01f * col : 0.01f * col);
            }
        }
    }
}
