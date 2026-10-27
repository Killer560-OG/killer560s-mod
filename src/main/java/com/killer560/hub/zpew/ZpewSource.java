package com.killer560.hub.zpew;

/**
 * Who sent an etherwarp, for Zero Ping Etherwarp's "Works For" list (killer560, 2026-10-27: "also add a zpew for anything
 * else that uses etherwarp automatically. that should all go under zpew itself. make an option like things that zpew
 * works for with a list of everything"). Each sender marks its own use packet with {@link ZeroPingEtherwarp#sending};
 * a use nobody marked is the player's own click ({@link #MANUAL}).
 *
 * <p>Every source is on by default: before the list existed ZPEW predicted every etherwarp use it saw, whoever sent it.
 * The cheat sources exist only in a cheat build (their switches read false in a legit one, and the tab does not show
 * them).
 */
public enum ZpewSource {
    /** Your own right click (and anything no sender marked). */
    MANUAL("Manual Etherwarps", false),
    /** Left Click Etherwarp ({@code leftclickew/LeftClickEtherwarp}). */
    LEFT_CLICK("Left Click Etherwarp", true),
    /** Auto Routes' etherwarp nodes, path hops and Zero Ping / Charged Chain warps ({@code RouteExecutor.sendHeldItemUse}). */
    AUTO_ROUTES("Auto Routes", true),
    /** The Interactive Map's pathing ({@code ClearExecutor}): map presses, Go + Secret, Auto Blood Rush, Auto Routes' Go To
     *  and start-node trips, puzzle walk-ins - every map trip no feature below owns. */
    INTERACTIVE_MAP("Interactive Map", true),
    /** Auto Secret and Dungeon Autopilot trips (the map's executor while either runs). */
    AUTO_SECRET("Auto Secret / Autopilot", true),
    /** Auto Clear's etherwarp trips (the map's executor while Auto Clear runs on its own). */
    AUTO_CLEAR("Auto Clear", true),
    /** Auto Puzzles' etherwarps (Ice Path's Etherwarp Reposition and the other puzzle repositions, {@code AutoPuzzleUtil}). */
    AUTO_PUZZLES("Auto Puzzles", true),
    /** AP3's use nodes when the held item is an etherwarp item. */
    AP3("AP3", true),
    /** Pathfinding's etherwarp hopper ({@code pathfinding/EtherwarpHopper}: Auto Fairy Souls and friends). */
    PATHFINDING("Pathfinding Hopper", true);

    private final String label;
    private final boolean cheatOnly;

    ZpewSource(String label, boolean cheatOnly) {
        this.label = label;
        this.cheatOnly = cheatOnly;
    }

    public String label() {
        return label;
    }

    public boolean cheatOnly() {
        return cheatOnly;
    }
}
