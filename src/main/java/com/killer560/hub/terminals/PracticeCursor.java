package com.killer560.hub.terminals;

import java.util.Map;

/**
 * Stream Safe for a terminal that is not a Hypixel container: Termism's practice board (killer560, 2026-11-02: "auto terms
 * stream safe doesnt show in termism"). Termism ran its own auto-click loop on the Min/Max delays and never asked the
 * {@link FakeCursor}, which also drew and hid the pointer only over container screens. This is the door into the same
 * hand, click order, settle and Melody lookahead rule a real terminal uses, so practicing looks like the real thing.
 * Every call is a no-op for the cursor while it is not showing ({@link #setShowing}), and {@link #ready} is then true.
 * <p>
 * Slots are chest-style indices, nine to a row ({@code row * 9 + column}), because {@link HumanClickOrder} measures
 * distances on that grid.
 */
public final class PracticeCursor {

    /** The opening settle a real terminal waits before its first click (the hand already sets off meanwhile). */
    public static final long SETTLE_MS = TerminalSolverFeature.INITIAL_CLICK_SETTLE_MS;

    private PracticeCursor() {
    }

    /** A new practice board: a fresh hand from the window center and a fresh click-order habit. */
    public static void begin() { FakeCursor.beginTerminal(); }

    /** Every frame: whether Stream Safe Auto Terminals is driving this board. */
    public static void setShowing(boolean show) { FakeCursor.setShowing(show); }

    public static boolean showing() { return FakeCursor.active(); }

    /** The practice board is solved: the real pointer is back at once. */
    public static void end() { TerminalSolverFeature.endAutoClicks(); }

    public static void aim(FakeCursor.SlotLocator locator, int slot) { FakeCursor.aim(locator, slot); }

    /** Melody's row button: straight there, as on a real board. */
    public static void aimQuick(FakeCursor.SlotLocator locator, int slot) { FakeCursor.aimQuick(locator, slot); }

    /** @return true when the hand is on {@code slot} and pressing (always true while Stream Safe is not showing). */
    public static boolean ready(int slot) { return FakeCursor.ready(slot); }

    /** The slot the hand goes for next among {@code highlights} (chest-style indices), or -1. */
    public static int next(TerminalType type, Map<Integer, TerminalSolverFeature.SlotHighlight> highlights) {
        return HumanClickOrder.next(type, highlights, s -> false);
    }

    /** Whether the click about to go out on this highlight's slot is followed by another on it (a Rubix pane needing 2). */
    public static boolean needsMoreClicks(TerminalType type, TerminalSolverFeature.SlotHighlight highlight) {
        return TerminalSolverFeature.needsMoreClicks(type, highlight);
    }

    public static void expectRepeat(boolean repeat) { FakeCursor.expectRepeat(repeat); }

    /** A practice click went out on {@code slot}: the hand records it (and Hide Mouse Cursor counts from now). */
    public static void clicked(int slot, boolean more) {
        TerminalSolverFeature.noteAutoClick(slot, System.currentTimeMillis());
        HumanClickOrder.clicked(slot, more);
    }

    /** Melody: rows after {@code matchedRow} a proper click on track column {@code column} (0-4) bursts (Skip Mode). */
    public static int melodyLookaheadRows(int matchedRow, int column, int lastRow) {
        return TerminalSolverFeature.melodyLookaheadRows(matchedRow, column, lastRow);
    }
}
