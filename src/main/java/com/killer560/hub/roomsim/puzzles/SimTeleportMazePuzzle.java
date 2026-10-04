package com.killer560.hub.roomsim.puzzles;

import com.killer560.hub.roomsim.SimState;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A small standalone practice arena for the real Hypixel dungeon "Teleport Maze" puzzle, playable inside the
 * room sim: stand on the right pad, in sequence, cell after cell, to cross.
 *
 * <p><b>What is real, ported from {@link com.killer560.hub.puzzlesolvers.TeleportMazeSolverFeature}'s own class
 * doc</b>: "The room has 7 cells of 4 end-portal-frame pads plus a start and end pad." So {@link #CELL_COUNT}
 * (7) and {@link #PADS_PER_CELL} (4) are a real, cited fact from this codebase's own real solver, not invented -
 * and the core rule this arena drills, that only one of a cell's pads is the correct one and the other three do
 * not advance you, is the same rule that class exists to detect live off real teleport packets.
 *
 * <p><b>What is this file's own invention</b>, same category as {@link SimBlazePuzzle}'s stand positions: the
 * actual spatial layout - a short line of small walkable platforms spaced out from {@code origin}, instead of
 * the real room's own far-apart fixed 30-coordinate layout (only reachable there by a real teleport packet, not
 * by walking, which is exactly why the real thing needs solving rather than crossing on foot) - and, freshly
 * every time {@link #build} runs, WHICH of a cell's 4 pads is the correct one. TeleportMazeSolverFeature's own
 * doc records that on Hypixel this is only ever found live, by ray-testing the landing rotation after a real
 * teleport; there is no static "pad 2 of cell 5 is always right" table anywhere in this codebase to port,
 * because the real game decides it fresh per room instance.
 *
 * <p>Stepping on any pad other than the current cell's correct one is the one thing that must not just do
 * nothing: it wipes and rebuilds the whole arena (fresh random correct pads, back at the start), the same
 * "getting it wrong costs the whole attempt" feel {@code SimBlazePuzzle#failAndRebuild} gives an out-of-order
 * kill - and a harder consequence than the real room's own "wrong pad just re-teleports you inside the same
 * cell" (see the class doc above). That is a deliberate strengthening for practice, not a claim about how
 * severe the real Hypixel room is.
 *
 * <p>Gated on {@link SimState#canAct} throughout. Every write to the world or the player happens on the
 * integrated server via {@code server.execute(...)}, never the client thread - same rule as the rest of
 * {@code roomsim}, see {@code SimDoors}' class doc; the teleport itself is the server-authoritative pattern
 * {@code SimAbilities#teleport} uses, moving the SERVER's player rather than the client entity, which is why a
 * teleport is safe to do here and nowhere else this mod touches Hypixel.
 */
public final class SimTeleportMazePuzzle {

    private static final int CELL_COUNT = 7; // real fact - see class doc
    private static final int PADS_PER_CELL = 4; // real fact - see class doc
    /** Invented spacing between cells - just far enough that no cell's platform touches its neighbour's. */
    private static final int CELL_SPACING = 6;

    /** Pad offsets within a cell, relative to that cell's own entry tile - this file's own invented layout. */
    private static final int[][] PAD_OFFSETS = {{-1, 0, 3}, {1, 0, 3}, {-1, 0, 4}, {1, 0, 4}};

    /** Every (dx, 0, dz) tile that makes up one cell's walkable floor - the entry plus the run up to the pads. */
    private static final int[][] FLOOR_OFFSETS = buildFloorOffsets();

    private static int[][] buildFloorOffsets() {
        List<int[]> out = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = 0; dz <= 4; dz++) {
                out.add(new int[]{dx, 0, dz});
            }
        }
        return out.toArray(new int[0][]);
    }

    /**
     * The real room's own pads, copied verbatim from {@code TeleportMazeSolverFeature.PADS} (which took them
     * from QUOI's {@code TeleportMazeSolver.kt}): 28 cell pads in cell order, four to a cell, then the END pad
     * and the START pad. Room-relative, y=69, the same frame every solver in this mod works in.
     *
     * <p>Only {@link #bindAt} uses these - {@link #build}'s standalone arena keeps its own invented layout,
     * because the real coordinates are spread across a 33-block room and are only reachable by teleport.
     */
    private static final int[][] REAL_PADS = {
            {4, 69, 14}, {10, 69, 14}, {10, 69, 20}, {4, 69, 20},
            {4, 69, 12}, {4, 69, 6}, {10, 69, 6}, {10, 69, 12},
            {12, 69, 28}, {12, 69, 22}, {18, 69, 22}, {18, 69, 28},
            {26, 69, 14}, {20, 69, 20}, {20, 69, 14}, {26, 69, 20},
            {26, 69, 28}, {26, 69, 22}, {20, 69, 28}, {20, 69, 22},
            {10, 69, 22}, {10, 69, 28}, {4, 69, 28}, {4, 69, 22},
            {20, 69, 6}, {20, 69, 12}, {26, 69, 12}, {26, 69, 6},
            {15, 69, 14}, // end
            {15, 69, 12}, // start
    };

    /** Which pad (0-3) is correct for each cell, chosen fresh every {@link #build}. */
    private static volatile int[] correctPad = new int[0];

    /** The cell number {@link #PAD_INDEX} files the START pad under - not a cell, the door into cell one. */
    private static final int ENTRY_CELL = -1;

    /**
     * How far over the block below a maze teleport lands the player.
     *
     * <p>1.5, not 1, and the extra half block is not cosmetic. {@code TeleportMazeSolverFeature} only treats a
     * position packet as a maze teleport when it lands on {@code y 69.5} - room-relative, which is what every
     * real Hypixel maze teleport does - and it works out which pad is the exit from the yaw of exactly those
     * packets. Landing on a whole y meant the sim's own teleports did not look like maze teleports to it, so
     * standing on the right pad moved him and taught his solver nothing. Half a block of fall onto the chamber
     * floor is what the real room does too.
     */
    private static final double LANDING_HEIGHT = 1.5;

    /**
     * Feet-level position -> {cell, padIndex}, rebuilt every {@link #build} and every {@link #bindAt}.
     *
     * <p><b>A pad is indexed at TWO positions, and that is the bug killer560 reported on 2026-09-30</b> ("for
     * teleport maze the pads are not working"). The bind found all 30 pads correctly; what it got wrong was
     * which block the player is standing in while on one. {@code EndPortalFrameBlock}'s collision shape is
     * {@code column(16, 0, 13)} - checked with {@code javap} on 26.1.2's {@code SHAPE_EMPTY}, 13/16 of a block
     * - so a player on an eyeless frame sits at {@code y + 0.8125} and {@code blockPosition()} floors to the
     * FRAME'S OWN y, not one above it. The bound room indexed only {@code pad.above()}, so
     * {@code PAD_INDEX.get(player.blockPosition())} could never match and standing on a pad did nothing at
     * all. The standalone arena hid it: its pads are full-height gold blocks, where {@code above()} is right.
     * Both positions are indexed now, so neither block height can miss - and the chamber floor around the
     * pads is a full block, so nothing else in the room lands on either key.
     */
    private static final Map<BlockPos, int[]> PAD_INDEX = new HashMap<>();

    /** Every block position this session has placed, for {@link #reset} to clear. */
    private static volatile List<BlockPos> builtBlocks = List.of();

    /** Anchor for cell {@code c}'s floor/pads: the block one below where the player stands entering that cell. */
    private static volatile BlockPos[] cellAnchor = new BlockPos[0];

    private static volatile int currentCell = 0;
    private static volatile boolean complete = false;
    private static volatile boolean built = false;
    private static volatile BlockPos storedOrigin = null;

    private static boolean registered = false;

    private SimTeleportMazePuzzle() {
    }

    /** Registers the progress-polling tick hook. */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientTickEvents.START_CLIENT_TICK.register(
                FeatureGuard.start("SimTeleportMazePuzzle.tick", SimTeleportMazePuzzle::tick));
    }

    /** Clears any previous arena and builds a fresh one, with fresh random correct pads, at {@code origin}. */
    public static void build(Minecraft client, BlockPos origin) {
        if (!SimState.canAct(client) || origin == null) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        clearBlocks(client);
        storedOrigin = origin;
        boundAnchor = null;   // a standalone arena, not a bind to a captured room
        BlockPos anchor = origin.below();

        BlockPos[] anchors = new BlockPos[CELL_COUNT + 1]; // +1: the final landing spot past the last cell
        for (int c = 0; c <= CELL_COUNT; c++) {
            anchors[c] = anchor.offset(0, 0, c * CELL_SPACING);
        }
        cellAnchor = anchors;

        int[] chosen = new int[CELL_COUNT];
        for (int c = 0; c < CELL_COUNT; c++) {
            chosen[c] = ThreadLocalRandom.current().nextInt(PADS_PER_CELL);
        }
        correctPad = chosen;

        PAD_INDEX.clear();
        List<BlockPos> toPlace = new ArrayList<>();
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> painted = new HashMap<>();
        for (int c = 0; c <= CELL_COUNT; c++) {
            BlockPos cellAnchorPos = anchors[c];
            for (int[] off : FLOOR_OFFSETS) {
                BlockPos pos = cellAnchorPos.offset(off[0], off[1], off[2]).immutable();
                toPlace.add(pos);
                painted.put(pos, Blocks.SMOOTH_STONE.defaultBlockState());
            }
            if (c == CELL_COUNT) {
                // The landing platform past the last cell has no pads to choose - just a marker to stand on.
                BlockPos endMarker = cellAnchorPos.offset(0, 0, 1).immutable();
                painted.put(endMarker, Blocks.EMERALD_BLOCK.defaultBlockState());
                continue;
            }
            for (int p = 0; p < PADS_PER_CELL; p++) {
                int[] off = PAD_OFFSETS[p];
                BlockPos padPos = cellAnchorPos.offset(off[0], off[1], off[2]).immutable();
                painted.put(padPos, Blocks.GOLD_BLOCK.defaultBlockState());
                PAD_INDEX.put(padPos.above(), new int[]{c, p});
            }
        }
        builtBlocks = List.copyOf(toPlace);
        currentCell = 0;
        complete = false;
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (Map.Entry<BlockPos, net.minecraft.world.level.block.state.BlockState> e : painted.entrySet()) {
                level.setBlockAndUpdate(e.getKey(), e.getValue());
            }
        });
        built = true;
        ModChat.send("Sim", ModChat.text("Teleport Maze built - find the right pad, "),
                ModChat.value(CELL_COUNT + " cells"), ModChat.text(" to cross."));
    }

    /**
     * Arms this puzzle on a REAL captured Teleport Maze room, using the room's own end-portal-frame pads.
     *
     * <p>Nothing is placed. All 30 of {@link #REAL_PADS} land on an {@code end_portal_frame} in the shipped
     * capture - measured 30 of 30 at database rotation 0, against 1, 0 and 1 at the other three - so the pads,
     * the seven chambers and the iron bars between them are the room's own blocks. Which of a cell's four pads
     * is the correct one is still drawn fresh here, exactly as {@link #build} does and for the reason that
     * class doc gives: the real game decides it per room instance and there is no table to port.
     *
     * <p>A correct pad teleports to the CENTRE of the next chamber - the midpoint of its four corner pads -
     * rather than onto one of its pads, because landing on a pad would be read as a choice on arrival.
     *
     * <p>Server thread only; called from {@code SimBuilder}'s post-build block.
     *
     * @return whether the puzzle was armed
     */
    public static boolean bindAt(net.minecraft.server.level.ServerLevel level,
                                 com.killer560.hub.roomsim.SimRoomPuzzles.Placement p) {
        List<int[]> rels = List.of(REAL_PADS);
        com.killer560.hub.roomsim.SimRoomPuzzles.Anchor anchor =
                com.killer560.hub.roomsim.SimRoomPuzzles.bestAnchor(level, p, rels,
                        com.killer560.hub.roomsim.SimRoomPuzzles.is(Blocks.END_PORTAL_FRAME),
                        new int[]{0}, 24);
        if (anchor == null) {
            return false;
        }
        // In-memory only, NOT clearBlocks(): that queues air writes at the PREVIOUS arena's positions for the
        // next server tick, which on a rebuild of the same room would be inside the room just pasted. The
        // build's own wipe has already removed the last floor.
        builtBlocks = List.of();
        // The chamber centres, one per cell, plus the end pad as the final landing spot. Worked out from the
        // pads themselves so a room whose chambers are laid out differently still lands the player inside one.
        BlockPos[] anchors = new BlockPos[CELL_COUNT + 1];
        PAD_INDEX.clear();
        for (int c = 0; c < CELL_COUNT; c++) {
            int sx = 0;
            int sz = 0;
            int y = 0;
            for (int i = 0; i < PADS_PER_CELL; i++) {
                int[] rel = REAL_PADS[c * PADS_PER_CELL + i];
                BlockPos pad = anchor.world(rel);
                sx += pad.getX();
                sz += pad.getZ();
                y = pad.getY();
                indexPad(pad, c, i);
            }
            // The pads sit IN the chamber floor - the room's solid floor is the block below them - so a
            // cellAnchor, which every landing adds one to, is the block below the walking level. Same
            // convention build() uses, where the anchor is the floor it places and the player stands on top.
            // floorDiv, not /: the sim's grid is anchored at -185, so these world coordinates are negative and
            // plain integer division truncates towards zero rather than down - a one-block drift in the landing
            // spot that only shows up on the negative side of the map, which is all of it.
            anchors[c] = new BlockPos(Math.floorDiv(sx, PADS_PER_CELL), y - 1,
                    Math.floorDiv(sz, PADS_PER_CELL));
        }
        anchors[CELL_COUNT] = anchor.world(REAL_PADS[28]).below();   // the end pad
        cellAnchor = anchors;
        // THE START PAD IS THE WAY IN, and it was the one pad nothing indexed.
        //
        // killer560 (2026-10-01): "The teleport pads in tpmaze still arent teleporting me." Decoding the room
        // says why plainly. The seven chambers are sealed - the capture holds 240 iron bars and a solid stone
        // brick wall at relative (12,69,13), right between the start pad and chamber one - so the only way into
        // the first chamber is the start pad at (15,69,12), exactly as on Hypixel. This bind indexed
        // REAL_PADS[0..27], the twenty-eight CHOICE pads, and stopped there. So he stood outside a sealed maze
        // with nothing to step on that did anything, and every pad that would have worked was behind a wall.
        indexPad(anchor.world(REAL_PADS[29]), ENTRY_CELL, 0);
        BlockPos[] pads = new BlockPos[REAL_PADS.length];
        BOUND_INDEX.clear();
        for (int i = 0; i < REAL_PADS.length; i++) {
            pads[i] = anchor.world(REAL_PADS[i]).immutable();
            BOUND_INDEX.put(pads[i], i);
            BOUND_INDEX.put(pads[i].above().immutable(), i);
        }
        boundPads = pads;
        bindCentre(level, anchor);
        drawLinks();
        lockedPad = -1;
        settleTicks = 0;
        int[] chosen = new int[CELL_COUNT];
        for (int c = 0; c < CELL_COUNT; c++) {
            chosen[c] = ThreadLocalRandom.current().nextInt(PADS_PER_CELL);
        }
        correctPad = chosen;
        storedOrigin = anchor.world(REAL_PADS[29]);   // the start pad
        boundAnchor = anchor;
        currentCell = 0;
        complete = false;
        built = true;
        return true;
    }

    /** Non-null while this puzzle is bound to a real room rather than to a standalone arena. */
    private static volatile com.killer560.hub.roomsim.SimRoomPuzzles.Anchor boundAnchor = null;

    // ---------------------------------------------------------------- the real room's pad pairing
    //
    // killer560 (2026-10-01): "the pads are teleporting me but make it so the pad teleports me ontop of a pad in
    // another room, that second pad it teleports me onto will take me back to the first but I should have to walk
    // off and back onto it for it to do taht." That is the real room: every pad is one end of a two-way link to a
    // pad in a DIFFERENT chamber, the start pad is linked to one of them, and exactly one pad leads to the end.
    // Nothing fails; a wrong pad just takes you somewhere else. And on every maze teleport Hypixel turns you to
    // face the exit pad - TeleportMazeSolverFeature narrows its candidates by crossing exactly those look rays,
    // so the sim does the same or the solver has nothing to read.

    private static final int START = 29;
    private static final int END = 28;

    /** World position of each of the 30 pads, indexed like {@link #REAL_PADS}. */
    private static volatile BlockPos[] boundPads = new BlockPos[0];
    /** Pad id -> the pad it sends you to. The exit pad maps to {@link #END}. */
    private static volatile int[] link = new int[0];
    /** The one pad that leads to the end. */
    private static volatile int exitPad = -1;
    /** Feet position -> pad id, at both heights a player can stand at on one (see {@link #PAD_INDEX}). */
    private static final Map<BlockPos, Integer> BOUND_INDEX = new HashMap<>();
    /** The pad he was just put on: inert until he has stepped off it. */
    private static volatile int lockedPad = -1;
    /** Ticks before a pad can fire again - the server moves him a tick or two after the client asks. */
    private static volatile int settleTicks = 0;

    private static int chamberOf(int pad) {
        return pad < END ? pad / PADS_PER_CELL : -1;
    }

    /**
     * Draws a fresh pairing: the start linked to one chamber pad, one exit pad, the other 26 paired two by two
     * across different chambers. Redrawn until the exit's chamber can be reached from the start's, walking inside
     * a chamber being free.
     */
    private static void drawLinks() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 500; attempt++) {
            List<Integer> pads = new ArrayList<>();
            for (int i = 0; i < END; i++) {
                pads.add(i);
            }
            java.util.Collections.shuffle(pads, rng);
            int[] l = new int[30];
            java.util.Arrays.fill(l, -1);
            int entry = pads.remove(0);
            int exit = -1;
            for (int i = 0; i < pads.size(); i++) {
                if (chamberOf(pads.get(i)) != chamberOf(entry)) {
                    exit = pads.remove(i);
                    break;
                }
            }
            if (exit < 0) {
                continue;
            }
            l[START] = entry;
            l[entry] = START;
            l[exit] = END;
            boolean ok = true;
            while (!pads.isEmpty() && ok) {
                int a = pads.remove(0);
                int partner = -1;
                for (int i = 0; i < pads.size(); i++) {
                    if (chamberOf(pads.get(i)) != chamberOf(a)) {
                        partner = pads.remove(i);
                        break;
                    }
                }
                if (partner < 0) {
                    ok = false;
                } else {
                    l[a] = partner;
                    l[partner] = a;
                }
            }
            if (!ok || !reachable(l, chamberOf(entry), chamberOf(exit))) {
                continue;
            }
            List<Integer> route = routeToCentre(l);
            if (route == null) {
                continue;
            }
            link = l;
            exitPad = exit;
            logRoute(route);
            return;
        }
        // Never leave the maze dead. An empty link table makes every pad in the room do nothing, which is a
        // worse failure than a predictable maze: chain the chambers in order instead, so the route is long but
        // certain. ~11% of draws fail on pairing alone, so 500 in a row failing should never happen - this is
        // the floor under "one of the pads takes me to the middle", not the normal path.
        int[] l = fallbackLinks();
        link = l;
        exitPad = 27;
        com.killer560.hub.util.ModLog.get("killer560smod-roomsim")
                .warn("Sim teleport maze: no random pairing in 500 tries - using the fixed chamber chain");
        List<Integer> route = routeToCentre(l);
        if (route != null) {
            logRoute(route);
        }
    }

    /**
     * A pairing that always works: start to chamber 0, each chamber's pad 3 to the next chamber's pad 0, and
     * chamber 6's pad 3 to the centre. The spare pads pair across chambers 0-1, 2-3 and 4-5 so every pad still
     * goes somewhere.
     */
    private static int[] fallbackLinks() {
        int[] l = new int[30];
        java.util.Arrays.fill(l, -1);
        l[START] = 0;
        l[0] = START;
        for (int c = 0; c + 1 < CELL_COUNT; c++) {
            int out = c * PADS_PER_CELL + 3;
            int in = (c + 1) * PADS_PER_CELL;
            l[out] = in;
            l[in] = out;
        }
        l[27] = END;
        for (int c = 0; c + 1 < CELL_COUNT - 1; c += 2) {
            for (int i = 1; i <= 2; i++) {
                int a = c * PADS_PER_CELL + i;
                int b = (c + 1) * PADS_PER_CELL + i;
                l[a] = b;
                l[b] = a;
            }
        }
        // Chamber 6's two spares have no chamber left to pair with, so they send him back to the entrance.
        l[6 * PADS_PER_CELL + 1] = START;
        l[6 * PADS_PER_CELL + 2] = START;
        return l;
    }

    /**
     * The pads he steps on, in order, to get from the start pad to the CENTRE (the end pad), or null if there is
     * no way.
     *
     * <p>killer560 (2026-10-02): "make sure one of the pads actually takes me to the middle." The chamber check
     * above already implies this, but it reasons about chambers; this walks the thing he actually does - stand
     * in a chamber, walk to any of its pads (free: a chamber's four pads share one floor, checked against the
     * decoded capture), step on one, land where its link says - and so it is the check that matches the
     * complaint word for word. The start pad stands alone in the entrance chamber and the end pad alone in the
     * centre one, both read off {@code Teleport_Maze.json} at y 69.
     */
    private static List<Integer> routeToCentre(int[] l) {
        // Search over the pad he is standing next to; the "chamber" of START is the entrance (-1 here).
        int[] cameFrom = new int[30];
        java.util.Arrays.fill(cameFrom, -2);
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        // Standing in the entrance: the only pad there is START itself.
        cameFrom[START] = -1;
        q.add(START);
        while (!q.isEmpty()) {
            int stepped = q.poll();
            int landed = l[stepped];
            if (landed < 0) {
                continue;
            }
            if (landed == END) {
                List<Integer> out = new ArrayList<>();
                for (int p = stepped; p >= 0; p = cameFrom[p]) {
                    out.add(0, p);
                }
                return out;
            }
            // From where he landed he can walk to (and step on) any OTHER pad of that chamber - the landing pad
            // is inert until he steps off it, and stepping back on it just undoes the hop.
            int c = landed == START ? -1 : chamberOf(landed);
            List<Integer> next = new ArrayList<>();
            if (c < 0) {
                next.add(START);
            } else {
                for (int i = 0; i < PADS_PER_CELL; i++) {
                    next.add(c * PADS_PER_CELL + i);
                }
            }
            for (int n : next) {
                if (cameFrom[n] == -2) {
                    cameFrom[n] = stepped;
                    q.add(n);
                }
            }
        }
        return null;
    }

    private static void logRoute(List<Integer> route) {
        StringBuilder sb = new StringBuilder();
        for (int pad : route) {
            int[] rel = REAL_PADS[pad];
            sb.append(sb.isEmpty() ? "" : " -> ")
                    .append(pad == START ? "start" : "chamber " + chamberOf(pad))
                    .append(" (").append(rel[0]).append(',').append(rel[2]).append(')');
        }
        com.killer560.hub.util.ModLog.get("killer560smod-roomsim").info(
                "Sim teleport maze: exit pad is relative ({},{}) in chamber {}; shortest way to the centre is {} "
                        + "pad(s): {} -> centre", REAL_PADS[exitPad][0], REAL_PADS[exitPad][2],
                chamberOf(exitPad), route.size(), sb);
    }

    private static boolean reachable(int[] l, int from, int to) {
        boolean[] seen = new boolean[CELL_COUNT];
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        seen[from] = true;
        q.add(from);
        while (!q.isEmpty()) {
            int c = q.poll();
            if (c == to) {
                return true;
            }
            for (int i = 0; i < PADS_PER_CELL; i++) {
                int other = l[c * PADS_PER_CELL + i];
                int oc = other >= 0 ? chamberOf(other) : -1;
                if (oc >= 0 && !seen[oc]) {
                    seen[oc] = true;
                    q.add(oc);
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- the centre: its chest and its way out

    /** Database-relative interior of the centre chamber (walls at x 11/19 and z 13/21, decoded from
     *  Teleport_Maze.json), from its floor at 68 to four blocks of air over it. */
    private static final int[] CENTRE_REL_MIN = {12, 68, 14};
    private static final int[] CENTRE_REL_MAX = {18, 73, 20};
    /** The reward chest, on the centre chamber's floor three blocks in from the end pad. The capture holds no
     *  chest anywhere and the room database lists no secret for this room, so the sim places one. */
    private static final int[] REWARD_CHEST_REL = {15, 69, 17};

    /** Feet block the centre's pad sends him back to; null until bound. */
    private static volatile BlockPos returnSpot = null;
    private static volatile BlockPos rewardChest = null;
    private static volatile BlockPos centreMin = null;
    private static volatile BlockPos centreMax = null;
    private static volatile long lastRefusalMs = 0L;

    /** Where the centre's pad sends him, for Auto Teleport Maze's finish; null when no maze is bound. */
    public static BlockPos returnSpot() {
        return returnSpot;
    }

    /**
     * The block he is put on when the Interactive Map takes him into this room: {@code AutoClearUtils}'
     * "Teleport Maze" spot, relative {@code (15, 68, -2)}, which is the block etherwarped ONTO - so he stands one
     * above it. Read from that table rather than copied, so the two cannot drift. If it cannot be stood on in this
     * build (a single loaded room has nothing past its own doorway), the doorway floor a step further in is used,
     * and the start pad as the last resort.
     */
    private static BlockPos findReturnSpot(ServerLevel level, com.killer560.hub.roomsim.SimRoomPuzzles.Anchor anchor) {
        int[] entry = com.killer560.hub.livemap.autoclear.AutoClearUtils.roomOverride("Teleport Maze");
        int ex = entry == null ? 15 : entry[0];
        int ey = entry == null ? 68 : entry[1];
        int ez = entry == null ? -2 : entry[2];
        int[] feetOffsets = {1, 0, 2, -1, 3};
        for (int dz = 0; dz <= 3; dz++) {
            for (int dy : feetOffsets) {
                BlockPos feet = anchor.world(ex, ey + dy, ez + dz);
                if (!level.getBlockState(feet.below()).isAir()
                        && level.getBlockState(feet).isAir()
                        && level.getBlockState(feet.above()).isAir()) {
                    return feet.immutable();
                }
            }
        }
        return anchor.world(REAL_PADS[START]).above().immutable();
    }

    /** Server thread, from {@link #bindAt}: the way out, the chamber's bounds and the reward chest. */
    private static void bindCentre(ServerLevel level, com.killer560.hub.roomsim.SimRoomPuzzles.Anchor anchor) {
        returnSpot = findReturnSpot(level, anchor);
        BlockPos a = anchor.world(CENTRE_REL_MIN);
        BlockPos b = anchor.world(CENTRE_REL_MAX);
        centreMin = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ()));
        centreMax = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()),
                Math.max(a.getZ(), b.getZ()));
        BlockPos chest = anchor.world(REWARD_CHEST_REL).immutable();
        if (level.getBlockState(chest).isAir() || level.getBlockState(chest).is(Blocks.CHEST)) {
            level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
            rewardChest = chest;
        } else {
            rewardChest = null;
            com.killer560.hub.util.ModLog.get("killer560smod-roomsim").warn(
                    "Sim teleport maze: no air for the reward chest at {} - holds {}", chest,
                    level.getBlockState(chest));
        }
        com.killer560.hub.util.ModLog.get("killer560smod-roomsim").info(
                "Sim teleport maze: reward chest at {}, the centre's pad returns to {}", rewardChest, returnSpot);
    }

    private static boolean insideCentre(BlockPos feet) {
        BlockPos lo = centreMin;
        BlockPos hi = centreMax;
        return lo != null && hi != null
                && feet.getX() >= lo.getX() && feet.getX() <= hi.getX()
                && feet.getY() >= lo.getY() && feet.getY() <= hi.getY()
                && feet.getZ() >= lo.getZ() && feet.getZ() <= hi.getZ();
    }

    /**
     * Refuses the reward chest to anyone not standing in the centre chamber.
     *
     * <p>killer560 (2026-10-04): "Make sure I cannot collect the chest unless I am inside the middle room in tp
     * maze." The chamber's walls are iron bars above the floor, and a click reaches through bars, so the chest
     * could be opened from the next chamber without crossing the maze. FAIL rather than SUCCESS: FAIL sends no
     * packet at all (see {@code AutoRoutesEditInput}), so the chest neither opens nor counts. Both sides check,
     * each against its own copy of the player. Registered ahead of {@code SimMimic}, which counts any chest click
     * as a secret, so a refused click is not counted either.
     */
    public static void registerChestGuard() {
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            BlockPos chest = rewardChest;
            if (chest == null || !SimState.isActive() || !chest.equals(hit.getBlockPos())) {
                return net.minecraft.world.InteractionResult.PASS;
            }
            if (insideCentre(player.blockPosition())) {
                return net.minecraft.world.InteractionResult.PASS;
            }
            if (level.isClientSide()) {
                long now = System.currentTimeMillis();
                if (now - lastRefusalMs > 1000L) {
                    lastRefusalMs = now;
                    ModChat.send("Sim", ModChat.bad("The maze's chest opens only from inside the middle room."));
                }
            }
            return net.minecraft.world.InteractionResult.FAIL;
        });
    }

    /** Pad ticking for the bound room. */
    private static void tickBound(Minecraft client) {
        if (settleTicks > 0) {
            settleTicks--;
            return;
        }
        Integer pad = BOUND_INDEX.get(client.player.blockPosition());
        if (pad == null) {
            lockedPad = -1;   // stepped off: the pad he landed on works again
            return;
        }
        if (pad == lockedPad || link.length == 0) {
            return;
        }
        if (pad == END) {
            // THE CENTRE'S PAD IS THE WAY OUT. killer560 (2026-10-04): "the teleporter at the middle by the chest
            // should take me back right to the same block I would tp to into the room". Landing on it from the
            // exit pad locks it like any other landing, so it fires once he has stepped off it and back on.
            BlockPos back = returnSpot;
            if (back != null) {
                lockedPad = END;
                settleTicks = 5;
                teleport(client, back.getX() + 0.5, back.getY(), back.getZ() + 0.5);
            }
            return;
        }
        int dest = link[pad];
        if (dest < 0) {
            return;
        }
        BlockPos to = boundPads[dest];
        lockedPad = dest;
        settleTicks = 5;
        if (dest == END) {
            complete = true;
            ModChat.send("Sim", ModChat.good("Teleport Maze crossed!"));
        }
        // Face the exit pad, the way Hypixel turns you on every maze teleport.
        BlockPos exit = exitPad >= 0 ? boundPads[exitPad] : to;
        double dx = exit.getX() + 0.5 - (to.getX() + 0.5);
        double dz = exit.getZ() + 0.5 - (to.getZ() + 0.5);
        float yaw = (dx == 0 && dz == 0) ? client.player.getYRot()
                : (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        // x.5 / pad y + 0.5 / z.5: the shape TeleportMazeSolverFeature accepts as a maze teleport.
        teleport(client, to.getX() + 0.5, to.getY() + 0.5, to.getZ() + 0.5, yaw);
    }

    /** True once the last cell's correct pad has sent the player to the final landing spot. */
    public static boolean isComplete() {
        return complete;
    }

    /** Clears the arena and its bookkeeping. Safe with nothing built. */
    /**
     * Drops this puzzle's bookkeeping WITHOUT touching the world.
     *
     * <p>{@link #reset} is the right thing while the arena is still standing: it puts blocks back, un-presses,
     * re-lights. It is the wrong thing when the floor those blocks belonged to no longer exists, which is
     * exactly the case {@code SimRoomPuzzles.armFloor} has to handle - the positions it holds are absolute and
     * the next floor is built over them, so a queued "set it back to air" lands inside the new floor and
     * punches a hole in it. Just as bad the other way: a stale click index left in place makes a click on some
     * unrelated block on the new floor count as a move in a puzzle that is not on it.
     */
    public static void forget() {
        builtBlocks = List.of();
        cellAnchor = new BlockPos[0];
        correctPad = new int[0];
        PAD_INDEX.clear();
        currentCell = 0;
        complete = false;
        built = false;
        storedOrigin = null;
        boundAnchor = null;
        clearBound();
    }

    private static void clearBound() {
        returnSpot = null;
        rewardChest = null;
        centreMin = null;
        centreMax = null;
        boundPads = new BlockPos[0];
        link = new int[0];
        exitPad = -1;
        BOUND_INDEX.clear();
        lockedPad = -1;
        settleTicks = 0;
    }

    public static void reset() {
        Minecraft client = Minecraft.getInstance();
        clearBlocks(client);
        cellAnchor = new BlockPos[0];
        correctPad = new int[0];
        PAD_INDEX.clear();
        currentCell = 0;
        complete = false;
        built = false;
        boundAnchor = null;
        clearBound();
    }

    private static void clearBlocks(Minecraft client) {
        List<BlockPos> old = builtBlocks;
        builtBlocks = List.of();
        if (old.isEmpty() || !SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (BlockPos pos : old) {
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
        });
    }

    private static void tick(Minecraft client) {
        if (!SimState.canAct(client) || !built) {
            return;
        }
        if (boundAnchor != null) {
            // Not stopped by completion: the centre's pad is the way back out, and it is only ever stood on
            // after the maze is crossed.
            tickBound(client);
            return;
        }
        if (complete) {
            return;
        }
        BlockPos feet = client.player.blockPosition();
        int[] hit = PAD_INDEX.get(feet);
        if (hit == null) {
            return;
        }
        int cell = hit[0];
        int padIndex = hit[1];
        if (cell == ENTRY_CELL) {
            // The start pad: it is not a choice, it is the door. Puts him in chamber one and leaves itself
            // indexed, so coming back out to it after a wrong pad works the same way round.
            if (currentCell == 0 && cellAnchor.length > 0 && cellAnchor[0] != null) {
                BlockPos landing = cellAnchor[0];
                teleport(client, landing.getX() + 0.5, landing.getY() + LANDING_HEIGHT, landing.getZ() + 0.5);
                ModChat.send("Sim", ModChat.text("Into the maze - "),
                        ModChat.dim("cell 1 of " + CELL_COUNT + ", one of the four pads is the way on."));
            }
            return;
        }
        if (cell != currentCell) {
            // Only reachable by standing on a pad from a cell the player has no business being on yet/again.
            failAndRebuild(client);
            return;
        }
        // Remove immediately so standing still on the same pad for more than one tick can't re-fire this.
        // Both of the pad's keys go, or the other one fires on the very next tick - see PAD_INDEX's doc.
        forgetPad(feet, cell, padIndex);
        if (padIndex == correctPad[cell]) {
            advanceToNextCell(client);
        } else {
            failAndRebuild(client);
        }
    }

    /** Indexes one pad at both feet positions a player standing on it can have - see {@link #PAD_INDEX}. */
    private static void indexPad(BlockPos pad, int cell, int padIndex) {
        PAD_INDEX.put(pad.immutable(), new int[]{cell, padIndex});
        PAD_INDEX.put(pad.above().immutable(), new int[]{cell, padIndex});
    }

    /** Drops every key that pointed at the pad the player has just stood on. */
    private static void forgetPad(BlockPos feet, int cell, int padIndex) {
        for (BlockPos candidate : new BlockPos[]{feet, feet.above(), feet.below()}) {
            int[] hit = PAD_INDEX.get(candidate);
            if (hit != null && hit[0] == cell && hit[1] == padIndex) {
                PAD_INDEX.remove(candidate);
            }
        }
    }

    private static void advanceToNextCell(Minecraft client) {
        int next = currentCell + 1;
        currentCell = next;
        BlockPos landing = cellAnchor[next];
        teleport(client, landing.getX() + 0.5, landing.getY() + LANDING_HEIGHT, landing.getZ() + 0.5);
        if (next >= CELL_COUNT) {
            complete = true;
            ModChat.send("Sim", ModChat.good("Teleport Maze crossed!"));
        } else {
            ModChat.send("Sim", ModChat.text("Correct pad - cell " + (next + 1) + "."));
        }
    }

    private static void failAndRebuild(Minecraft client) {
        // Tells the Architect's First Draft feature a puzzle failed, so his existing
        // auto-get setting works in here the same as it does on Hypixel.
        // Room name as well as puzzle name - see SimRoomState; this puzzle's room is named for it.
        SimPuzzles.reportFail("Teleport Maze", "Teleport Maze");
        ModChat.send("Sim", ModChat.bad("Wrong pad - resetting the maze."));
        com.killer560.hub.roomsim.SimRoomPuzzles.Anchor bound = boundAnchor;
        if (bound != null) {
            // Bound to a real room: there is no arena to rebuild, so the pads are re-drawn in place and the
            // player is put back on the start pad. Calling build() here would paste the standalone arena
            // INSIDE the captured room, which is exactly what this class was not doing before.
            rearmBound(client, bound);
            return;
        }
        BlockPos origin = storedOrigin;
        if (origin != null) {
            client.execute(() -> build(client, origin));
        }
    }

    /** Fresh correct pads and back to the start, for a maze bound to a real captured room. */
    private static void rearmBound(Minecraft client, com.killer560.hub.roomsim.SimRoomPuzzles.Anchor bound) {
        PAD_INDEX.clear();
        for (int c = 0; c < CELL_COUNT; c++) {
            for (int i = 0; i < PADS_PER_CELL; i++) {
                indexPad(bound.world(REAL_PADS[c * PADS_PER_CELL + i]), c, i);
            }
        }
        // The door back in, same as bindAt - without it a reset leaves him on a start pad that does nothing.
        indexPad(bound.world(REAL_PADS[29]), ENTRY_CELL, 0);
        int[] chosen = new int[CELL_COUNT];
        for (int c = 0; c < CELL_COUNT; c++) {
            chosen[c] = ThreadLocalRandom.current().nextInt(PADS_PER_CELL);
        }
        correctPad = chosen;
        currentCell = 0;
        complete = false;
        BlockPos start = bound.world(REAL_PADS[29]);
        teleport(client, start.getX() + 0.5, start.getY() + LANDING_HEIGHT, start.getZ() + 0.5);
    }

    /**
     * Server-authoritative teleport, same shape as {@code SimAbilities#teleport}: moves the SERVER's player,
     * never {@code client.player.setPos}, so the integrated server (the position source of truth) never
     * disagrees with the client on the very next tick. Relative set is empty so yaw/pitch are kept.
     */
    private static void teleport(Minecraft client, double x, double y, double z) {
        teleport(client, x, y, z, client.player.getYRot());
    }

    private static void teleport(Minecraft client, double x, double y, double z, float yaw) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        UUID uuid = client.player.getUUID();
        float pitch = client.player.getXRot();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp == null) {
                return;
            }
            // level() rather than serverLevel(): ServerPlayer has no serverLevel() accessor in 26.1.2.
            sp.teleportTo((ServerLevel) sp.level(), x, y, z, Set.<Relative>of(), yaw, pitch, false);
        });
    }
}
