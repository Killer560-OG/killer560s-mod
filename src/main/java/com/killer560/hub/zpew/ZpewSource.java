package com.killer560.hub.zpew;

/**
 * Who sent an etherwarp. Each sender marks its own use packet with {@link ZeroPingEtherwarp#sending}; a use nobody marked
 * is the player's own click ({@link #MANUAL}). The senders stay apart for the log and the testkit's per-sender counters,
 * but the settings only know their {@link Group} (zpewunify-1111, killer560 2026-11-11: "the only thing i should have to
 * select is auto routes, interactive map, auto puzzles and manual").
 */
public enum ZpewSource {
    /** Your own right click (and anything no sender marked). */
    MANUAL(Group.MANUAL),
    /** Left Click Etherwarp ({@code leftclickew/LeftClickEtherwarp}): his own warp, so Manual. */
    LEFT_CLICK(Group.MANUAL),
    /** Auto Routes' etherwarp nodes and path hops ({@code RouteExecutor.sendHeldItemUse}). */
    AUTO_ROUTES(Group.AUTO_ROUTES),
    /** The Interactive Map's pathing ({@code ClearExecutor}): map presses, Go + Secret, Auto Blood Rush, Auto Routes' Go To
     *  and start-node trips, puzzle walk-ins - every map trip no feature below owns. */
    INTERACTIVE_MAP(Group.INTERACTIVE_MAP),
    /** Auto Secret and Dungeon Autopilot trips (the map's executor while either runs): they run Auto Routes. */
    AUTO_SECRET(Group.AUTO_ROUTES),
    /** Auto Clear's etherwarp trips (the map's executor while Auto Clear runs on its own). */
    AUTO_CLEAR(Group.AUTO_ROUTES),
    /** Auto Puzzles' etherwarps (Ice Path's Etherwarp Reposition and the other puzzle repositions, {@code AutoPuzzleUtil}). */
    AUTO_PUZZLES(Group.AUTO_PUZZLES),
    /** AP3's use nodes with an etherwarp item: marked so they are never taken for his own click, and never predicted or
     *  multiplied (killer560, 2026-11-11: "you cannot use etherwarp in boss on the main server"). */
    AP3(null),
    /** Pathfinding's etherwarp hopper ({@code pathfinding/EtherwarpHopper}: Auto Fairy Souls and friends). */
    PATHFINDING(Group.AUTO_ROUTES);

    /** The four sources the Etherwarp tab offers, in its order. */
    public enum Group {
        AUTO_ROUTES("Auto Routes"),
        INTERACTIVE_MAP("Interactive Map"),
        AUTO_PUZZLES("Auto Puzzles"),
        MANUAL("Manual");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        public String label() { return label; }
    }

    private final Group group;

    ZpewSource(Group group) {
        this.group = group;
    }

    /** The setting this sender answers to, or null for one no setting covers (AP3). */
    public Group group() { return group; }

    public String label() { return name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '); }
}
