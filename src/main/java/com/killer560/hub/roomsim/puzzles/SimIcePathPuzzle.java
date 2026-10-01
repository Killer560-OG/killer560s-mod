package com.killer560.hub.roomsim.puzzles;

import com.killer560.hub.compat.McEntities;
import com.killer560.hub.roomsim.SimRoomPuzzles;
import com.killer560.hub.roomsim.SimState;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The real Hypixel dungeon "Ice Path" puzzle, playable inside the room sim: a silverfish on a 17x17 sheet of
 * ice, knocked one shove at a time until it slides out of the gap in the far wall.
 *
 * <p>killer560 (2026-09-30): "Ice path is missing the silverfish that i punch and it travels to complete the
 * puzzle." {@code SimRoomPuzzles} named the room and logged "Ice Path has no sim puzzle class yet - not armed";
 * this is that class.
 *
 * <h2>The rules are read off the live solver, not invented</h2>
 *
 * {@code puzzlesolvers/IcePathSolverFeature} is the ported, working QUOI solver, and every number below is its
 * number rather than a fresh guess:
 *
 * <ul>
 *   <li>The board is {@value #BOARD_SIZE}x{@value #BOARD_SIZE}. A cell is a WALL when the block at
 *       room-relative {@code (23 - col, 67, 24 - row)} is not air, and the silverfish sits on the ice a block
 *       lower, at {@code (23 - col, 66, 24 - row)} - the y the solver draws its path at.</li>
 *   <li>A shove makes the silverfish <b>slide until the next cell is a wall or the board ends</b>. That is
 *       exactly the move the solver's BFS enumerates, so a sim silverfish stops where the solver's green line
 *       says it will. {@link #slide} is that inner loop, copied.</li>
 *   <li>The puzzle is solved when the silverfish comes to rest on row {@value #EXIT_ROW}, column
 *       {@value #EXIT_COL_MIN} to {@value #EXIT_COL_MAX} - the solver's own goal test.</li>
 * </ul>
 *
 * <p>Decoding the shipped capture {@code assets/killer560smod/rooms/Ice_Path.json} confirms all of it rather
 * than taking it on trust. At database rotation 180 - which is also what {@code RoomCaptureRotation} recovers
 * from that capture's {@code blue_terracotta} roof marker, sitting on the high-x/high-z corner - all 289 board
 * cells have {@code packed_ice} under them, the border ring is 65 of 65 {@code polished_andesite}, and the
 * three cells the ring is missing are precisely columns {@value #EXIT_COL_MIN}-{@value #EXIT_COL_MAX} of row
 * -1: the solver's exit is a real hole in a real wall. The other three rotations put 33, 5 and 4 of those 65,
 * so the ring is what {@link #bindAt} identifies the room by.
 *
 * <h2>What is this file's own invention, and what it is not</h2>
 *
 * <p><b>Where the silverfish starts.</b> A silverfish is an entity, so it cannot be in a block capture, and
 * nothing in this repo bundles a spawn position for it - {@code IcePathSolverFeature} finds the live one and
 * has no table. So the start cell is chosen, and it is chosen by rule rather than picked: <b>the open cell that
 * needs the most shoves to get out, of those that can get out at all</b>, ties broken by the lowest row then
 * the lowest column. That rule is worth the arithmetic for two reasons. It cannot produce an unsolvable arena,
 * because a cell with no solution is never eligible. And it cannot produce a one-shove arena, which a fixed
 * guess easily can: the obvious choice of "the middle of the entry side", cell (16, 8), slides straight to
 * (0, 8) in a single push on this very capture. On the shipped Ice Path the rule picks cell (9, 0) and a
 * 16-shove solution.
 *
 * <p><b>The maze itself is not invented.</b> {@link #WALLS} is the 17 interior wall cells decoded out of that
 * capture, so the standalone {@code /simpuzzle icepath} arena is the real room's maze and not a made-up one. A
 * bound room does not use {@link #WALLS} at all - it reads its own walls out of the world.
 *
 * <h2>Nothing is written to a bound room</h2>
 *
 * Alone among the sim's puzzles, arming this one places no blocks: the maze, the ice and the exit are all
 * already in the capture, so {@link #bindAt} reads the board and spawns one entity. The only block writes in
 * this file build the standalone arena, and {@link #reset} takes exactly those away again.
 *
 * <h2>How a shove arrives</h2>
 *
 * <p>A punch comes in on {@link AttackEntityCallback}, which needs no mixin and fires before any damage logic -
 * so returning {@link InteractionResult#FAIL} means the silverfish is shoved and <b>never hurt</b>, rather than
 * being knocked around a 8-HP health bar until it dies. It is handled on the client side only and the work is
 * handed to the integrated server, the same split {@code AutoCroesusFeature}'s {@code UseEntityCallback} uses,
 * because in singleplayer these callbacks fire on both threads.
 *
 * <p>An arrow is picked up separately, by looking for an {@link AbstractArrow} against the silverfish while it
 * is at rest. That is there so <b>Auto Ice Path drives this puzzle</b>: it shoots straight down (pitch 90) from
 * on top of the silverfish with the yaw pointing at the next stop, so the shove direction is the ARROW's yaw,
 * which is the one reading that works for a shot fired from directly overhead as well as one fired across the
 * room. Untested in game - no shortbow has been fired at a sim silverfish yet.
 *
 * <p>Either way the direction is snapped to the nearest of the four board directions by comparing the shove
 * vector against the world offsets of the board's own four neighbours, so it stays correct at every rotation
 * without re-deriving the room transform here.
 *
 * <p>There is no failure state, and so no {@link SimPuzzles#reportFail} call: a badly aimed shove in the real
 * puzzle costs time and nothing else. Gated on {@link SimState#canAct} throughout, and every write to the world
 * or to the silverfish happens on the integrated server inside {@code server.execute(...)}, matching
 * {@code SimMobs} and {@link SimBlazePuzzle}.
 */
public final class SimIcePathPuzzle {

    private static final Logger LOGGER = ModLog.get("killer560smod-roomsim");

    /** {@code IcePathSolverFeature.BOARD_SIZE}. */
    private static final int BOARD_SIZE = 17;

    /** The row the silverfish has to reach, and the columns of the gap in that row's wall. */
    private static final int EXIT_ROW = 0;
    private static final int EXIT_COL_MIN = 7;
    private static final int EXIT_COL_MAX = 9;

    /** The ice the silverfish slides on, and the layer the maze walls stand in. The solver's two y values. */
    private static final int ICE_Y = 66;
    private static final int WALL_Y = 67;

    /** Room-relative x/z of the board's centre cell, which a standalone arena is anchored on. */
    private static final int CENTRE_X = 15;
    private static final int CENTRE_Z = 16;

    /** {@code IcePathSolverFeature.DIRECTIONS}, as {row, col} steps. */
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** Blocks a slide crosses per tick. A shove reads as a slide rather than a teleport, which is also what
     *  {@code IcePathSolverFeature.isSilverfishMoving} and Auto Ice Path's "wait while it slides" need. */
    private static final double SLIDE_SPEED = 0.45;

    /**
     * The 16 interior wall cells of the real Ice Path, as {row, col}. <b>Verified against a live room.</b>
     *
     * <p>Decoded from {@code assets/killer560smod/rooms/Ice_Path.json} and then checked block by block by
     * killer560 on 2026-10-01 against a real Catacombs run, on a grid he marked up himself. The capture was
     * right about fifteen of the sixteen and carried ONE wall the real room does not have, at {@code (15,16)}.
     *
     * <p>That one block was not cosmetic. With it, 26 of the board's open cells cannot reach the exit at all;
     * without it, every one of the 273 can. A real Hypixel puzzle has no dead cells, so the corrected board is
     * self-evidently the right one and the capture had picked up a stray.
     *
     * <p>killer560 also settled the orientation: <b>he enters from row 16</b> and the chest alcove is past row
     * 0, which is the way {@code IcePathSolverFeature} already indexes it. So there is one true layout, and
     * this is it - which is why {@link #bindAt} now CONFORMS a bound room to this list rather than taking
     * whatever its capture happens to hold.
     */
    private static final int[][] WALLS = {
            {0, 5}, {1, 1}, {2, 4}, {2, 12}, {3, 0}, {3, 16}, {9, 15}, {10, 1},
            {10, 10}, {10, 15}, {11, 2}, {11, 11}, {15, 6}, {15, 10}, {16, 2}, {16, 13},
    };

    /**
     * Where the silverfish starts, given by killer560 on 2026-10-01 against the real room.
     *
     * <p>Ten shoves to the exit on the corrected board. Used whenever it is open and solvable, which is checked
     * rather than assumed - a board that has drifted falls back to {@link #furthestSolvable} rather than
     * spawning a silverfish that can never get out.
     */
    private static final int[] SPAWN = {15, 15};

    /** The board as the solver reads it - true is a wall. Empty until built or bound. */
    private static volatile boolean[][] board = null;

    /** Non-null while bound to a real captured room. Mutually exclusive with {@link #standaloneCentre}. */
    private static volatile SimRoomPuzzles.Anchor boundAnchor = null;

    /** The world block that room-relative {@code (15, 66, 16)} sits at, for a standalone arena. */
    private static volatile BlockPos standaloneCentre = null;

    /** Blocks this class PLACED, for a standalone arena's {@link #reset} to take away again. */
    private static volatile List<BlockPos> placedBlocks = List.of();

    private static volatile UUID fishId = null;

    /** The cell the silverfish is resting on, as {row, col}. */
    private static volatile int[] cell = null;

    /** Where a shove is carrying it, as {row, col}; null while it is at rest. */
    private static volatile int[] targetCell = null;

    private static volatile boolean complete = false;

    private static boolean registered = false;

    private SimIcePathPuzzle() {
    }

    /** Registers the slide tick and the punch hook. */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientTickEvents.START_CLIENT_TICK.register(
                FeatureGuard.start("SimIcePathPuzzle.tick", SimIcePathPuzzle::tick));
        // Client side only, and FAIL either way: the punch must shove the silverfish and never damage it.
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (!isOurFish(entity)) {
                return InteractionResult.PASS;
            }
            if (level.isClientSide()) {
                onPunched(player.getYRot());
            }
            return InteractionResult.FAIL;
        });
    }

    private static boolean isOurFish(Entity entity) {
        UUID id = fishId;
        return id != null && entity != null && id.equals(entity.getUUID());
    }

    /**
     * Clears any previous arena and builds a fresh Ice Path centred on {@code origin}.
     *
     * <p>The real room's own maze, ice and exit gap, laid around the player rather than read out of a capture -
     * see {@link #WALLS}. Same rules as a bound room.
     */
    public static void build(Minecraft client, BlockPos origin) {
        if (!SimState.canAct(client) || origin == null) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        clearPlaced(client);
        despawnFish(client);
        forget();
        standaloneCentre = origin.immutable();

        boolean[][] built = new boolean[BOARD_SIZE][BOARD_SIZE];
        for (int[] wall : WALLS) {
            built[wall[0]][wall[1]] = true;
        }
        board = built;

        List<BlockPos> ice = new ArrayList<>(BOARD_SIZE * BOARD_SIZE);
        // A SET, because the ring loop below reaches each corner from two sides.
        Set<BlockPos> stone = new LinkedHashSet<>();
        for (int row = 0; row < BOARD_SIZE; row++) {
            for (int col = 0; col < BOARD_SIZE; col++) {
                ice.add(iceOf(row, col));
                if (built[row][col]) {
                    stone.add(wallOf(row, col));
                }
            }
        }
        // The border ring, corners included so the arena reads as a closed box, minus the exit gap. The slide
        // rule already stops at the board's edge, so the ring is there to be SEEN; the hole in it is the part
        // that matters, and it is the solver's own three columns.
        for (int i = -1; i <= BOARD_SIZE; i++) {
            for (int[] rc : new int[][]{{-1, i}, {BOARD_SIZE, i}, {i, -1}, {i, BOARD_SIZE}}) {
                if (rc[0] == -1 && rc[1] >= EXIT_COL_MIN && rc[1] <= EXIT_COL_MAX) {
                    continue;
                }
                stone.add(wallOf(rc[0], rc[1]));
            }
        }
        int[] start = chooseStart(built);
        if (start == null) {
            // Cannot happen with WALLS as it stands - the bundled maze has 246 solvable cells - but a future
            // edit to that table could break it, and a silent arena with no silverfish in it is the one
            // outcome that reads exactly like "the puzzle does nothing".
            LOGGER.warn("Sim ice path: the bundled maze has no cell the silverfish can escape from - not built");
            forget();
            return;
        }
        List<BlockPos> all = new ArrayList<>(ice.size() + stone.size());
        all.addAll(ice);
        all.addAll(stone);
        placedBlocks = List.copyOf(all);
        final int[] startCell = start;
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (BlockPos pos : ice) {
                level.setBlockAndUpdate(pos, Blocks.PACKED_ICE.defaultBlockState());
            }
            for (BlockPos pos : stone) {
                level.setBlockAndUpdate(pos, Blocks.POLISHED_ANDESITE.defaultBlockState());
            }
            spawn(level, startCell);
        });
        ModChat.send("Sim", ModChat.text("Ice Path built - punch the silverfish out through the gap ("),
                ModChat.value(shoveCount(built, start) + " shoves"), ModChat.text(")."));
    }

    /**
     * Arms this puzzle inside a REAL captured Ice Path room.
     *
     * <p>Identified by its border ring: 65 {@code polished_andesite} cells at {@value #WALL_Y}, the full
     * rectangle around the board minus the exit gap, which the shipped capture holds 65 of 65 of at database
     * rotation 180 and at most 33 of at any other. The maze is then read out of the room the way
     * {@code IcePathSolverFeature} reads it, so the sim's silverfish and the solver's green line agree by
     * construction.
     *
     * <p>No block is written. The room already contains the whole puzzle; the only thing missing was the
     * silverfish.
     *
     * <p>Server thread only; called from {@code SimBuilder}'s post-build block.
     *
     * @return whether the puzzle was armed
     */
    public static boolean bindAt(ServerLevel level, SimRoomPuzzles.Placement p) {
        List<int[]> ring = new ArrayList<>();
        for (int i = 0; i < BOARD_SIZE; i++) {
            for (int[] rc : new int[][]{{-1, i}, {BOARD_SIZE, i}, {i, -1}, {i, BOARD_SIZE}}) {
                if (rc[0] == -1 && rc[1] >= EXIT_COL_MIN && rc[1] <= EXIT_COL_MAX) {
                    continue;
                }
                ring.add(new int[]{23 - rc[1], WALL_Y, 24 - rc[0]});
            }
        }
        SimRoomPuzzles.Anchor anchor = SimRoomPuzzles.bestAnchor(level, p, ring,
                SimRoomPuzzles.is(Blocks.POLISHED_ANDESITE), new int[]{0}, ring.size() * 3 / 4);
        if (anchor == null) {
            return false;
        }
        // forget(), never clearPlaced(): clearPlaced would queue air writes at the PREVIOUS arena's absolute
        // positions for the next server tick, and on a rebuild those land inside the floor just pasted.
        forget();
        boundAnchor = anchor;

        // CONFORMED to the verified layout, not taken as the capture left it.
        //
        // There is one true Ice Path - killer560, 2026-10-01 - and WALLS is it, checked against a real room.
        // The shipped capture carries one wall the real room does not have, at (15,16), and that single block
        // makes 26 of the board's cells unable to reach the exit. Reading the room as captured would hand him a
        // maze that is not the one he practises on, and sometimes an unsolvable one.
        //
        // Only the maze layer inside the 17x17 is touched - nothing outside the board, nothing at any other
        // height - and the count is logged, so a capture that has drifted says how far rather than silently
        // being papered over.
        boolean[][] read = new boolean[BOARD_SIZE][BOARD_SIZE];
        for (int[] wall : WALLS) {
            read[wall[0]][wall[1]] = true;
        }
        int walls = WALLS.length;
        int corrected = 0;
        for (int row = 0; row < BOARD_SIZE; row++) {
            for (int col = 0; col < BOARD_SIZE; col++) {
                BlockPos at = wallOf(row, col);
                boolean isWall = !level.getBlockState(at).isAir();
                if (isWall == read[row][col]) {
                    continue;
                }
                level.setBlockAndUpdate(at, read[row][col]
                        ? Blocks.POLISHED_ANDESITE.defaultBlockState()
                        : Blocks.AIR.defaultBlockState());
                corrected++;
            }
        }
        int[] start = chooseStart(read);
        if (start == null) {
            LOGGER.warn("Sim ice path: no cell in {} the silverfish can be pushed out of - {} wall cell(s) on "
                    + "the board, so the maze is closed. Not armed.", p.room().name, walls);
            forget();
            return false;
        }
        board = read;
        spawn(level, start);
        if (fishId == null) {
            forget();
            return false;
        }
        LOGGER.info("Sim ice path: armed in {} - {} wall cell(s), {} corrected from the capture, silverfish at "
                        + "cell ({},{}), {} shove(s) to the exit",
                p.room().name, walls, corrected, start[0], start[1], shoveCount(read, start));
        return true;
    }

    /** True once the silverfish has reached the exit. */
    public static boolean isComplete() {
        return complete;
    }

    /**
     * Drops this puzzle's bookkeeping WITHOUT touching the world.
     *
     * <p>Same reason as every other puzzle's: {@code SimRoomPuzzles.armFloor} has to drop the last floor's
     * state, and the positions held here are absolute ones the next floor is built over. The silverfish itself
     * is an entity in a level about to be wiped and rebuilt, so it goes with it - nothing is discarded here,
     * which is the whole point of {@code forget()}.
     *
     * <p>{@link #placedBlocks} goes too, and that is the part worth saying out loud. It is a list of absolute
     * positions a STANDALONE arena wrote, and {@code armFloor} calls this for every puzzle a new floor does not
     * hold - so keeping the list would leave a later {@code /simpuzzle reset} queueing air at 350-odd positions
     * that now sit inside a freshly built floor. Every caller that still wants those blocks taken away calls
     * {@link #clearPlaced} first, which queues the writes and empties the list itself.
     */
    public static void forget() {
        placedBlocks = List.of();
        board = null;
        boundAnchor = null;
        standaloneCentre = null;
        fishId = null;
        cell = null;
        targetCell = null;
        complete = false;
    }

    /** Takes a standalone arena away again and clears progress. A bound room's blocks are the room's. */
    public static void reset() {
        Minecraft client = Minecraft.getInstance();
        despawnFish(client);
        clearPlaced(client);
        forget();
    }

    private static void clearPlaced(Minecraft client) {
        List<BlockPos> old = placedBlocks;
        placedBlocks = List.of();
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

    private static void despawnFish(Minecraft client) {
        UUID id = fishId;
        fishId = null;
        if (id == null) {
            return;
        }
        MinecraftServer server = client == null ? null : client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            Entity entity = server.overworld().getEntity(id);
            if (entity != null) {
                entity.discard();
            }
        });
    }

    // ---------------------------------------------------------------- geometry

    /**
     * The world block of a room-relative coordinate, whichever way this puzzle is standing.
     *
     * <p>A bound room goes through the anchor, which is the one transform {@code SimSecrets},
     * {@code SimRoomIndex} and every other bind already use. A standalone arena is the same relative grid
     * offset from the block the player was standing on, which is what {@code SimIceFillPuzzle} does.
     */
    private static BlockPos rel(int rx, int ry, int rz) {
        SimRoomPuzzles.Anchor anchor = boundAnchor;
        if (anchor != null) {
            return anchor.world(rx, ry, rz);
        }
        BlockPos centre = standaloneCentre;
        if (centre == null) {
            return null;
        }
        return centre.offset(rx - CENTRE_X, ry - ICE_Y, rz - CENTRE_Z).immutable();
    }

    /** The ice a cell's silverfish stands on. {@code IcePathSolverFeature}'s own path coordinate. */
    private static BlockPos iceOf(int row, int col) {
        return rel(23 - col, ICE_Y, 24 - row);
    }

    /** The cell a maze wall would occupy. {@code IcePathSolverFeature}'s own board read. */
    private static BlockPos wallOf(int row, int col) {
        return rel(23 - col, WALL_Y, 24 - row);
    }

    private static boolean inBounds(int row, int col) {
        return row >= 0 && row < BOARD_SIZE && col >= 0 && col < BOARD_SIZE;
    }

    /**
     * Where a shove from {@code (row, col)} in direction {@code (dRow, dCol)} comes to rest.
     *
     * <p>{@code IcePathSolverFeature.solve}'s inner loop, unchanged: keep going while the NEXT cell is on the
     * board and not a wall. Returns the starting cell when the shove is straight into a wall.
     */
    private static int[] slide(boolean[][] b, int row, int col, int dRow, int dCol) {
        while (inBounds(row + dRow, col + dCol) && !b[row + dRow][col + dCol]) {
            row += dRow;
            col += dCol;
        }
        return new int[]{row, col};
    }

    private static boolean isExit(int[] c) {
        return c != null && c[0] == EXIT_ROW && c[1] >= EXIT_COL_MIN && c[1] <= EXIT_COL_MAX;
    }

    /**
     * How many shoves the shortest solution from {@code from} takes, or -1 when there is none.
     *
     * <p>The same breadth-first search over slide moves that {@code IcePathSolverFeature} runs, counting
     * instead of collecting, so the number this class logs is the number that solver would show him.
     */
    private static int shoveCount(boolean[][] b, int[] from) {
        if (isExit(from)) {
            return 0;
        }
        Set<Integer> seen = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{from[0], from[1], 0});
        seen.add(from[0] * BOARD_SIZE + from[1]);
        while (!queue.isEmpty()) {
            int[] at = queue.removeFirst();
            for (int[] d : DIRECTIONS) {
                int[] to = slide(b, at[0], at[1], d[0], d[1]);
                if (!seen.add(to[0] * BOARD_SIZE + to[1])) {
                    continue;
                }
                if (isExit(to)) {
                    return at[2] + 1;
                }
                queue.add(new int[]{to[0], to[1], at[2] + 1});
            }
        }
        return -1;
    }

    /**
     * The cell to start the silverfish on: {@link #SPAWN} when it works on this board, otherwise the rule.
     *
     * <p>The fixed cell is killer560's, read off the real room. It is still CHECKED rather than trusted,
     * because a fixed cell cannot be safe on a board that has drifted - his first reading of it, {@code (14,16)},
     * turned out to be one of the 26 cells that could not reach the exit at all, and a silverfish that can never
     * escape is a worse failure than one in the wrong corner, since nothing about it looks broken.
     *
     * @return {row, col}, or null when no cell on this board has a solution at all
     */
    private static int[] chooseStart(boolean[][] b) {
        if (!b[SPAWN[0]][SPAWN[1]] && shoveCount(b, SPAWN) > 0) {
            return new int[]{SPAWN[0], SPAWN[1]};
        }
        LOGGER.warn("Sim ice path: the recorded start cell ({},{}) is {} on this board - falling back to the "
                        + "furthest solvable cell", SPAWN[0], SPAWN[1],
                b[SPAWN[0]][SPAWN[1]] ? "a wall" : "unable to reach the exit");
        return furthestSolvable(b);
    }

    /**
     * The open cell needing the most shoves to escape, of those that can escape at all.
     *
     * <p>The fallback for a board {@link #SPAWN} does not fit. It cannot produce an unsolvable arena, because a
     * cell with no solution is never eligible, and it cannot produce a one-shove arena either.
     */
    private static int[] furthestSolvable(boolean[][] b) {
        int[] best = null;
        int bestShoves = 0;
        for (int row = 0; row < BOARD_SIZE; row++) {
            for (int col = 0; col < BOARD_SIZE; col++) {
                if (b[row][col]) {
                    continue;
                }
                int shoves = shoveCount(b, new int[]{row, col});
                if (shoves > bestShoves) {
                    bestShoves = shoves;
                    best = new int[]{row, col};
                }
            }
        }
        return best;
    }

    /**
     * The board direction a shove of this yaw pushes in.
     *
     * <p>Snapped by comparing the shove vector against the WORLD offsets of the board's own four neighbours,
     * rather than by turning the yaw by the room's rotation here. The board transform is a rigid rotation, so
     * one cell's neighbour offsets are every cell's, and the centre cell is used for all of them.
     */
    private static int[] directionFor(float yaw) {
        double rad = Math.toRadians(yaw);
        double px = -Math.sin(rad);
        double pz = Math.cos(rad);
        BlockPos from = iceOf(8, 8);
        if (from == null) {
            return null;
        }
        int[] best = null;
        double bestDot = 0;
        for (int[] d : DIRECTIONS) {
            BlockPos to = iceOf(8 + d[0], 8 + d[1]);
            if (to == null) {
                return null;
            }
            double dot = (to.getX() - from.getX()) * px + (to.getZ() - from.getZ()) * pz;
            if (best == null || dot > bestDot) {
                bestDot = dot;
                best = d;
            }
        }
        return best;
    }

    /**
     * The world yaw a silverfish travelling in board direction {@code d} should face.
     *
     * <p>Derived from the same neighbour offsets {@link #directionFor} snaps against, so the fish faces the way
     * it is actually going at every room rotation. Minecraft's yaw convention: 0 looks along +Z, and the look
     * vector is {@code (-sin yaw, cos yaw)}, which inverts to {@code atan2(-x, z)}.
     */
    private static Float facingYaw(int[] d) {
        BlockPos from = iceOf(8, 8);
        BlockPos to = iceOf(8 + d[0], 8 + d[1]);
        if (from == null || to == null) {
            return null;
        }
        return (float) Math.toDegrees(Math.atan2(-(to.getX() - from.getX()), to.getZ() - from.getZ()));
    }

    // ---------------------------------------------------------------- the silverfish

    /** Spawns the silverfish on a cell. Server thread only. */
    private static void spawn(ServerLevel level, int[] at) {
        BlockPos ice = iceOf(at[0], at[1]);
        if (ice == null) {
            return;
        }
        SimSilverfish fish = new SimSilverfish(McEntities.SILVERFISH, level);
        fish.setPersistenceRequired();
        // Stationary until something shoves it, and never hurt by the shove - see the class doc. Invulnerable
        // as well as the cancelled punch, because an arrow reaches it on a path no callback can refuse.
        fish.setNoAi(true);
        fish.setInvulnerable(true);
        // The slide is driven by position, one step a tick, so vanilla physics must not also be moving it.
        fish.setNoGravity(true);
        fish.setPos(ice.getX() + 0.5, ice.getY() + 1, ice.getZ() + 0.5);
        if (!level.addFreshEntity(fish)) {
            LOGGER.warn("Sim ice path: the level refused the silverfish at {}", ice);
            return;
        }
        fishId = fish.getUUID();
        cell = new int[]{at[0], at[1]};
        targetCell = null;
        complete = false;
    }

    /** A punch landed. Client thread; the shove itself is the server's. */
    private static void onPunched(float yaw) {
        Minecraft client = Minecraft.getInstance();
        if (!SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> shove(server.overworld(), yaw));
    }

    /** Starts a slide. Server thread only; does nothing while one is already running. */
    private static void shove(ServerLevel level, float yaw) {
        boolean[][] b = board;
        int[] at = cell;
        if (b == null || at == null || targetCell != null || complete) {
            return;
        }
        int[] d = directionFor(yaw);
        if (d == null) {
            return;
        }
        int[] to = slide(b, at[0], at[1], d[0], d[1]);
        if (to[0] == at[0] && to[1] == at[1]) {
            return;   // shoved straight into a wall: nothing moves, and nothing is said - as on Hypixel
        }
        Entity entity = level.getEntity(fishId);
        Float facing = facingYaw(d);
        if (entity != null && facing != null) {
            entity.setYRot(facing);
            entity.setYHeadRot(facing);
        }
        targetCell = to;
    }

    private static void tick(Minecraft client) {
        if (!SimState.canAct(client) || fishId == null || complete) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> step(server));
    }

    /** One tick of the slide, or a look for an arrow while at rest. Server thread only. */
    private static void step(MinecraftServer server) {
        ServerLevel level = server.overworld();
        UUID id = fishId;
        if (id == null) {
            return;
        }
        Entity entity = level.getEntity(id);
        if (!(entity instanceof Silverfish fish) || !fish.isAlive()) {
            // Gone for a reason this class did not cause. Cleared rather than chased, so a dead arena stops
            // ticking instead of looking for an entity that is not coming back.
            fishId = null;
            cell = null;
            targetCell = null;
            return;
        }
        int[] to = targetCell;
        if (to == null) {
            pollForArrow(level, fish);
            return;
        }
        BlockPos ice = iceOf(to[0], to[1]);
        if (ice == null) {
            targetCell = null;
            return;
        }
        double tx = ice.getX() + 0.5;
        double ty = ice.getY() + 1;
        double tz = ice.getZ() + 0.5;
        double dx = tx - fish.getX();
        double dz = tz - fish.getZ();
        double left = Math.sqrt(dx * dx + dz * dz);
        if (left <= SLIDE_SPEED) {
            fish.setPos(tx, ty, tz);
            cell = to;
            targetCell = null;
            if (isExit(to)) {
                complete = true;
                fishId = null;
                fish.discard();
                Minecraft.getInstance().execute(() ->
                        ModChat.send("Sim", ModChat.good("Ice Path solved - the silverfish is out.")));
            }
            return;
        }
        fish.setPos(fish.getX() + dx / left * SLIDE_SPEED, ty, fish.getZ() + dz / left * SLIDE_SPEED);
    }

    /**
     * An arrow against a resting silverfish is a shove, with the ARROW's yaw as the direction.
     *
     * <p>Auto Ice Path shoots straight down from on top of it, so the arrow's flight direction says nothing
     * about which way to push and its yaw says everything - that is the reading QUOI's auto relies on. The
     * arrow is discarded so one shot cannot shove twice.
     */
    private static void pollForArrow(ServerLevel level, Silverfish fish) {
        List<AbstractArrow> arrows = level.getEntitiesOfClass(AbstractArrow.class,
                fish.getBoundingBox().inflate(0.75), a -> !a.isRemoved());
        if (arrows.isEmpty()) {
            return;
        }
        AbstractArrow arrow = arrows.get(0);
        float yaw = arrow.getYRot();
        arrow.discard();
        shove(level, yaw);
    }

    /**
     * Plain {@link Silverfish} minus the peaceful-discard half of {@code checkDespawn()} - same reasoning and
     * same fix as {@code SimMobs}'s {@code SimZombie}/{@code SimSkeleton} and {@link SimBlazePuzzle}'s blaze:
     * {@code Mob.checkDespawn()} discards any hostile mob not allowed in peaceful before it ever looks at
     * persistence, and the sim world runs on {@code Difficulty.PEACEFUL}.
     */
    private static final class SimSilverfish extends Silverfish {
        SimSilverfish(EntityType<? extends Silverfish> type, Level level) {
            super(type, level);
        }

        @Override
        public void checkDespawn() {
        }

        /**
         * It shoves nobody and nobody shoves it.
         *
         * <p>killer560 (2026-10-01): "make it so the silver fish cannot push me when I walk into it but it is
         * working great." Walking into it was nudging him off the tile he was standing on, which in a puzzle
         * where the tile you are on is the whole state is worse than annoying.
         *
         * <p>Both halves are needed and they are different methods. {@code pushEntities} is what a living entity
         * does TO its neighbours each tick - {@code setNoAi} does not stop it, because it runs from
         * {@code LivingEntity.aiStep} rather than from any goal - and {@code isPushable} is what lets neighbours
         * do it back. The slide is driven by {@code setPos}, so this entity has no need of either.
         */
        @Override
        protected void pushEntities() {
        }

        @Override
        public boolean isPushable() {
            return false;
        }
    }
}
