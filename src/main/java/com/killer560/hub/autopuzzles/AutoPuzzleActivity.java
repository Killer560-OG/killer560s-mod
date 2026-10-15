package com.killer560.hub.autopuzzles;

import net.minecraft.client.Minecraft;

/**
 * Whether an auto puzzle is working right now, for the automation that would otherwise move him in the middle of it
 * (killer560, 2026-10-14: "auto clear related things need to realize when an auto puzzle is working to stop making it
 * etherwarp back to the start"). Auto Routes does not arm, and Auto Secret, Dungeon Autopilot and Auto Clear hold where
 * they are, while this reads true; they carry on once it has been quiet for {@link #GRACE_TICKS}.
 * <p>
 * Every auto marks itself here on each tick it drives him: the free camera every turning auto holds for its whole run
 * ({@link FreeCam#keep}), Auto Boulder while it acts, Auto Tic Tac Toe while it walks or takes the chest, and every
 * click, use or map walk an auto sends through {@link AutoPuzzleUtil}.
 */
public final class AutoPuzzleActivity {

    /** Quiet ticks after the last mark before the puzzle counts as no longer working. Covers the gaps inside a run
     *  where an auto is waiting on the puzzle (Tic Tac Toe's reply, a box settling) without marking. */
    public static final int GRACE_TICKS = 40;

    private static int ticks;
    private static int lastMark = Integer.MIN_VALUE / 2;
    private static String what = "";
    /** util/ServerLag at the last mark: a server stall since then (the puzzle's answer is late) extends the grace. */
    private static long markLag;
    private static Object level;

    private AutoPuzzleActivity() {
    }

    /** Once per client tick, from {@link AutoPuzzlesFeature}: the clock, and a world change forgets the last mark. */
    static void tick(Minecraft client) {
        ticks++;
        if (client.level != level) {
            level = client.level;
            lastMark = Integer.MIN_VALUE / 2;
        }
    }

    /** An auto puzzle is driving him this tick. Public for the testkit, which stands in for a puzzle with it. */
    public static void note(String tag) {
        lastMark = ticks;
        markLag = com.killer560.hub.util.ServerLag.mark();
        what = tag == null ? "" : tag;
    }

    /** True while an auto puzzle marked itself within the last {@link #GRACE_TICKS} ticks. */
    public static boolean isWorking() {
        return ticks - lastMark <= GRACE_TICKS + com.killer560.hub.util.ServerLag.ticksSince(markLag);
    }

    /** The auto that marked last, for chat and log lines ("Auto Water"). */
    public static String what() {
        return what.isEmpty() ? "an auto puzzle" : "Auto " + what;
    }
}
