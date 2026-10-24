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

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-autopuzzles");
    /** Logging only: the tick the current working spell began. */
    private static int spellStart = 0;

    private AutoPuzzleActivity() {
    }

    /** Once per client tick, from {@link AutoPuzzlesFeature}: the clock, and a world change forgets the last mark. */
    static void tick(Minecraft client) {
        ticks++;
        if (client.level != level) {
            level = client.level;
            lastMark = Integer.MIN_VALUE / 2;
            solvedRooms.clear();
            endedRooms.clear();
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
            LOGGER.info("[AutoPuzzles] Activity: a PUZZLE SOLVED line not filed - {}", !m.matches()
                    ? "not the server's line shape" : client.player == null ? "no player" : "solved by another player");
            return;
        }
        com.killer560.hub.roomdatabase.RoomEntry room = com.killer560.hub.livemap.LiveMapFeature.currentRoomEntry();
        if (room != null && room.name != null) {
            solvedRooms.put(room.name, ticks);
            LOGGER.info("[AutoPuzzles] Activity: Hypixel says he solved '{}' (tick {})", room.name, ticks);
        } else {
            LOGGER.info("[AutoPuzzles] Activity: his PUZZLE SOLVED line came with no room under him - not filed");
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
        boolean wasWorking = isWorking();
        if (!wasWorking) {
            spellStart = ticks;
            LOGGER.info("[AutoPuzzles] Activity: an auto puzzle is working ({}) - other automation holds, tick={}", t,
                    ticks);
        } else if (GENERIC.equals(what) && !t.isEmpty() && !GENERIC.equals(t)) {
            LOGGER.debug("[AutoPuzzles] Activity: working spell named {} (was {})", t, what);
        }
        if (!isWorking() || what.isEmpty() || (GENERIC.equals(what) && !t.isEmpty() && !GENERIC.equals(t))) {
            what = t;
        }
        lastMark = ticks;
        markLag = com.killer560.hub.util.ServerLag.mark();
    }

    /**
     * The auto's last step is over (its reward chest claimed): the working spell ends now, not {@link #GRACE_TICKS}
     * later - the grace is for gaps INSIDE a run, and after the last step it only held Autopilot, Auto Secret and Auto
     * Routes for two seconds of nothing (killer560's rule: no fixed waits). Anything that marks again afterward starts
     * a new spell.
     */
    static void ended() {
        if (isWorking()) {
            LOGGER.info("[AutoPuzzles] Activity: {} ended its last step - other automation may carry on now"
                    + " (spell of {} tick(s))", what(), ticks - spellStart);
        }
        lastMark = Integer.MIN_VALUE / 2;
        com.killer560.hub.roomdatabase.RoomEntry room = com.killer560.hub.livemap.LiveMapFeature.currentRoomEntry();
        if (room != null && room.name != null) {
            endedRooms.put(room.name, ticks);
        }
    }

    /** Rooms where an auto ended its last step this world ({@link #ended}), with the tick it did. */
    private static final java.util.Map<String, Integer> endedRooms = new java.util.HashMap<>();

    /** This class's tick clock, for {@link #endedSince}. */
    public static int clock() {
        return ticks;
    }

    /**
     * An auto ended its last step in {@code room} on or after tick {@code since} ({@link #clock}): nothing it does there is
     * left, so whatever the puzzle still does (the server's solve, the map's tick) comes whether he stays or not. Auto
     * Secret and Dungeon Autopilot leave a puzzle on it rather than waiting for the map or tab list's check (Higher Blaze,
     * killer560's Mod Only Test log 2026-10-20 09:55:27: done, then 7 s for the map). The Quiz is the exception the caller
     * handles: its auto ends after every answer, not only the last.
     */
    public static boolean endedSince(String room, int since) {
        Integer at = room == null ? null : endedRooms.get(room);
        return at != null && at >= since;
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
