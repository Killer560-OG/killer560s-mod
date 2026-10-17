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
            solvedRooms.clear();
        }
    }

    /**
     * Hypixel's own "solved" line, the tick it arrives: {@code PUZZLE SOLVED! <player> tied Tic Tac Toe! Good job!} (his
     * Mod Only Test log, 22:28:37). The rest of the line names the puzzle in its own words, so only the shape is matched:
     * anchored, the player a real name group (a rank tag allowed before it, as on the PUZZLE FAIL! line), every part
     * bounded - another player's chat line cannot be read as it (chat lines start with their sender).
     */
    private static final java.util.regex.Pattern SOLVED = java.util.regex.Pattern.compile(
            "^PUZZLE SOLVED! (?:\\[[^\\]]{1,20}\\] )?([A-Za-z0-9_]{1,16}) [^!]{1,80}! Good job!$");
    /** Rooms whose puzzle Hypixel said HE solved, this world, with the client tick it said so. */
    private static final java.util.Map<String, Integer> solvedRooms = new java.util.HashMap<>();

    /** ChatObserver listener (registered by AutoPuzzlesFeature): files the line under the room he stands in. */
    static void onChat(net.minecraft.network.chat.Component message) {
        String text = com.killer560.hub.util.ChatObserver.strip(message);
        if (text == null || !text.startsWith("PUZZLE SOLVED! ")) {
            return;
        }
        java.util.regex.Matcher m = SOLVED.matcher(text.trim());
        Minecraft client = Minecraft.getInstance();
        if (!m.matches() || client.player == null || !m.group(1).equals(client.player.getGameProfile().name())) {
            return;
        }
        com.killer560.hub.roomdatabase.RoomEntry room = com.killer560.hub.livemap.LiveMapFeature.currentRoomEntry();
        if (room != null && room.name != null) {
            solvedRooms.put(room.name, ticks);
        }
    }

    /** Whether Hypixel's "PUZZLE SOLVED!" line for him came while he stood in {@code room}, this world. */
    public static boolean solvedByHim(String room) {
        return room != null && solvedRooms.containsKey(room);
    }

    /** The tag every click, use and map walk sent through {@link AutoPuzzleUtil} marks with. */
    static final String GENERIC = "Puzzles";

    /**
     * An auto puzzle is driving him this tick. Public for the testkit, which stands in for a puzzle with it.
     * <p>
     * The name is the one the working spell began with, upgraded once from the generic {@link #GENERIC} to the first
     * auto that names itself, and kept until the spell ends: several marks land in one tick (Ice Path's chest step marks
     * "Ice Path", its free camera "Ice Path chest", its click "Puzzles"), and taking the last of them made the name
     * alternate tick by tick - and every reader that says the name once per change (Autopilot's "Waiting for ... to
     * finish" in his chat, Auto Routes' "Arming: inert" line) said it twenty times a second (killer560, 2026-10-15).
     */
    public static void note(String tag) {
        String t = tag == null ? "" : tag;
        if (!isWorking() || what.isEmpty() || (GENERIC.equals(what) && !t.isEmpty() && !GENERIC.equals(t))) {
            what = t;
        }
        lastMark = ticks;
        markLag = com.killer560.hub.util.ServerLag.mark();
    }

    /**
     * The auto's last step is over (its reward chest claimed): the working spell ends now, not {@link #GRACE_TICKS}
     * later - the grace is for gaps INSIDE a run, and after the last step it only held Autopilot, Auto Secret and Auto
     * Routes for two seconds of nothing (killer560's rule: no fixed waits). Anything that marks again afterwards starts
     * a new spell.
     */
    static void ended() {
        lastMark = Integer.MIN_VALUE / 2;
    }

    /** True while an auto puzzle marked itself within the last {@link #GRACE_TICKS} ticks. */
    public static boolean isWorking() {
        return ticks - lastMark <= GRACE_TICKS + com.killer560.hub.util.ServerLag.ticksSince(markLag);
    }

    /** The auto that marked last, for chat and log lines ("Auto Water"). */
    public static String what() {
        // The free cameras' log tags are run together ("IcePath", "TicTacToe"); a chat line spells the name out.
        return what.isEmpty() ? "an auto puzzle" : "Auto " + what.replaceAll("(?<=[a-z])(?=[A-Z])", " ");
    }
}
