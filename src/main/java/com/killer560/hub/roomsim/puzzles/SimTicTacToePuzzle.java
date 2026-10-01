package com.killer560.hub.roomsim.puzzles;

import com.killer560.hub.roomsim.SimState;
import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.killer560.hub.compat.McBlocks;

/**
 * A playable Tic Tac Toe board for the dungeon sim, built fresh at a given origin rather than read off a real
 * captured room.
 *
 * <p>The win rule and the computer's move selection are NOT invented here - {@link #getScore}, {@link #alphaBeta}
 * and {@link #getBestMove} are ported line-for-line from
 * {@code com.killer560.hub.puzzlesolvers.TicTacToeSolverFeature} (itself ported from QUOI's Kotlin solver, which
 * the real puzzle's board colours were read against), including its move-order table and its choice of who moves
 * on an odd/even mark count. That class's own comment records killer560's request that the "safe prediction" it
 * shows only ever points at a cell that cannot lose - the working assumption here, since nothing in this codebase
 * states the real puzzle's win/loss criterion directly, is that the practice failure condition is exactly what
 * that comment implies: letting the computer (X) complete three in a row. A draw or an O win both count as
 * solving it. If Hypixel's real completion rule differs, this is the place to correct it.
 *
 * <p><b>What is NOT from the real puzzle:</b> the real board is 9 map item frames in fixed room-relative
 * coordinates (x=8, y 70-72, z 15-17) read as pixel colours, with separate physical buttons in front of some
 * cells (see that class's {@code drawCell}). This sim has no captured room to anchor those coordinates to, so it
 * substitutes 9 directly-clickable concrete blocks (white = empty, lime = O/player, red = X/computer) built
 * relative to whatever origin {@link #build} is given. The board still sits on one fixed X plane with the grid in
 * Y (rows) and Z (columns), mirroring the real board's orientation even though the exact offsets are not real
 * capture data.
 *
 * <p>Gated on {@link SimState#canAct} throughout, and every block write happens on the integrated server thread
 * via {@code server.execute(...)} - same rule as the rest of {@code roomsim}, see {@code SimDoors}' class doc.
 */
public final class SimTicTacToePuzzle {

    private static final char EMPTY = '\0';
    private static final char PLAYER = 'O';
    private static final char COMPUTER = 'X';

    /** Ported verbatim from TicTacToeSolverFeature: centre, then corners, then edges - alpha-beta prunes far
     *  better exploring the strong moves first. */
    private static final int[] MOVE_ORDER = {4, 0, 2, 6, 8, 1, 3, 5, 7};
    private static final int[] WIN_SETS = {
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            0, 3, 6, 1, 4, 7, 2, 5, 8,
            0, 4, 8, 2, 4, 6
    };

    /** Cell index (row*3+col) -> the block that is CLICKED. Empty until {@link #build}. */
    private static volatile BlockPos[] cellPos = null;

    /**
     * THE MARK GOES ON THE CELL, and an unplayed cell is a button. (Kept as a field name for the standalone
     * arena's sake; in a bound room it is always null now.)
     *
     * <p>killer560 (2026-10-01): "tictactoe still is missing its bottom right button. Also the solver isnt
     * working there either." Two reports, one cause, and it is this.
     *
     * <p>Decoding the capture gives the real board: eight {@code stone_button} at capture {@code x=23},
     * {@code y 70..72}, {@code z 14..16}, each attached to an {@code iron_block} wall one step further in at
     * {@code x=24}, with air in front at {@code x=22}. In the database's own coordinates the button is at
     * {@code x=8} - which is {@code TicTacToeSolverFeature}'s cell coordinate, and {@code 31 - 23 = 8} agrees -
     * and the wall at {@code x=7}.
     *
     * <p>The mark used to be painted on the WALL at {@code x=7}, to keep the button standing. That is the one
     * place {@code TicTacToeSolverFeature} never looks. It reads the board at {@code x=8} and nowhere else, so
     * every mark was invisible to it and to Auto Tic Tac Toe with it. On the real board a played cell has no
     * button and shows its mark AT {@code x=8} (a map in an item frame, which is an entity and so can never be
     * in a capture) - so the mark goes on the cell, exactly where the solver reads, and an unplayed cell is
     * simply its button again. Painting and un-buttoning are now the same write instead of two that had to
     * agree.
     */
    private static volatile BlockPos[] paintPos = null;
    /** Reverse lookup for the click hook, same shape as SimDoors' BLOCK_INDEX. */
    private static final Map<BlockPos, Integer> CELL_INDEX = new ConcurrentHashMap<>();

    private static final char[] board = new char[9];
    private static volatile boolean built = false;
    private static volatile boolean complete = false;

    private SimTicTacToePuzzle() {
    }

    /** Hooks the click that places the player's O. Call once from {@code Killer560ModClient#onInitializeClient},
     *  alongside the other {@code roomsim} {@code register()} calls (wiring not done here - see the class doc of
     *  the caller in the handoff, and this file's own restriction on which files it may touch). */
    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            Minecraft client = Minecraft.getInstance();
            // level.isClientSide(): this event also fires server-side; without this check a real click would
            // try to play twice - the same double-fire guard SimDoors uses for its own UseBlockCallback.
            if (!level.isClientSide() || !SimState.canAct(client) || player != client.player) {
                return InteractionResult.PASS;
            }
            Integer index = CELL_INDEX.get(hitResult.getBlockPos());
            if (index == null || !built || complete || board[index] != EMPTY) {
                return InteractionResult.PASS;
            }
            onPlayerClick(client, index);
            // PASS for the room's own button, so vanilla still presses it - the depress and the click are what
            // make a cell feel played, and SUCCESS threw both away. A standalone arena's cell is concrete with
            // no interaction of its own, so consuming that one costs nothing and stops a stray right-click
            // doing anything else with it. Same split SimQuizPuzzle's pillar buttons use.
            return level.getBlockState(hitResult.getBlockPos())
                    .getBlock() instanceof net.minecraft.world.level.block.ButtonBlock
                    ? InteractionResult.PASS : InteractionResult.SUCCESS;
        });
    }

    /**
     * Builds a fresh board with cell (row, col) at {@code origin.offset(0, -row, col)} - row 0 is the top row,
     * at {@code origin}'s own height. The computer (X) makes the opening move immediately, matching
     * TicTacToeSolverFeature's own "even mark count = computer's turn" rule applied to an empty board.
     */
    public static void build(Minecraft client, BlockPos origin) {
        if (!SimState.canAct(client) || origin == null) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        reset();
        BlockPos[] positions = new BlockPos[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                positions[row * 3 + col] = origin.offset(0, -row, col).immutable();
            }
        }
        cellPos = positions;
        boundAnchor = null;   // a standalone board, not a bind to a captured room
        for (int i = 0; i < 9; i++) {
            CELL_INDEX.put(positions[i], i);
        }
        Arrays.fill(board, EMPTY);
        built = true;
        complete = false;

        // Computer opens on an empty board - identical call shape to TicTacToeSolverFeature.getBestMove(board,
        // false) on a fresh board, which always returns MOVE_ORDER's first entry (the centre) since alphaBeta
        // scores every empty-board reply as a draw.
        Integer opening = getBestMove(board, false);
        if (opening != null) {
            board[opening] = COMPUTER;
        }
        paintAll(server);
        ModChat.send("Sim", ModChat.text("Tic Tac Toe built. You are "), ModChat.good("O"),
                ModChat.text(" - click a square."));
    }

    /**
     * Arms this puzzle on a REAL captured Tic Tac Toe room, on the room's own board.
     *
     * <p>The nine cells are {@code TicTacToeSolverFeature}'s own: room-relative {@code x=8}, {@code y 70..72},
     * {@code z 15..17}, with {@code row = 72 - y} and {@code col = 17 - z} - the exact indexing that class uses
     * to read the real board, so a cell index here means the same cell it means there. Measured against the
     * shipped capture, 8 of those 9 positions already hold a {@code stone_button} at database rotation 180
     * (0 of 9 at every other rotation), which is how the board is found; the ninth is missing because that
     * cell had already been played when the room was walked.
     *
     * <p>The real MARKS are map item frames read by pixel colour. An item frame is an entity, so it can never
     * be in a capture and there is nothing to bind to - the concrete blocks {@link #build} uses stand in, and
     * they go ON the nine cells, replacing the buttons. That is the one write this bind makes, and it is the
     * board's state rather than a second arena: a real cell that has been played has no button either.
     *
     * <p>Server thread only; called from {@code SimBuilder}'s post-build block.
     *
     * @return whether the puzzle was armed
     */
    public static boolean bindAt(net.minecraft.server.level.ServerLevel level,
                                 com.killer560.hub.roomsim.SimRoomPuzzles.Placement p) {
        java.util.List<int[]> rels = new java.util.ArrayList<>(9);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                rels.add(new int[]{8, 72 - row, 17 - col});
            }
        }
        com.killer560.hub.roomsim.SimRoomPuzzles.Anchor anchor =
                com.killer560.hub.roomsim.SimRoomPuzzles.bestAnchor(level, p, rels,
                        com.killer560.hub.roomsim.SimRoomPuzzles.is(Blocks.STONE_BUTTON), new int[]{0}, 6);
        if (anchor == null) {
            return false;
        }
        // forget(), not reset(): reset() queues an AIR write at the PREVIOUS board's nine positions for the
        // next server tick, and on a rebuild of the same room those are the nine about to be painted - it
        // would blank the board this method just built.
        forget();
        BlockPos[] positions = new BlockPos[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                positions[row * 3 + col] = anchor.world(8, 72 - row, 17 - col).immutable();
            }
        }
        cellPos = positions;
        // No separate paint target in a bound room - the mark IS the cell. See paintPos.
        paintPos = null;
        boundAnchor = anchor;
        for (int i = 0; i < 9; i++) {
            CELL_INDEX.put(positions[i], i);
        }
        // THE NINTH BUTTON. killer560 (2026-10-01): "When I went into tictactoe the bottom right button wwas
        // missing." It is missing from the CAPTURE - the room was walked after that cell had been played, so
        // only eight of the nine are in the file (the gap is at capture (23,70,16)). A board you can only play
        // eight squares of is not the puzzle.
        //
        // The replacement is COPIED from one of the eight that are there rather than built from a literal,
        // because a button carries a facing and which way it faces depends on how the room was turned - and
        // the eight present ones are already correct at this rotation by construction.
        BlockState existing = null;
        for (BlockPos pos : positions) {
            if (level.getBlockState(pos).is(Blocks.STONE_BUTTON)) {
                existing = level.getBlockState(pos);
                break;
            }
        }
        // REMEMBERED, not re-read later. By the end of a game every cell has been played and none of the nine
        // still holds a button, so a restart that went looking for one to copy would find nothing and put
        // none back - a board that can only be played once.
        buttonState = existing;
        if (existing != null) {
            int replaced = 0;
            for (BlockPos pos : positions) {
                if (!level.getBlockState(pos).is(Blocks.STONE_BUTTON)) {
                    level.setBlockAndUpdate(pos, existing);
                    replaced++;
                }
            }
            if (replaced > 0) {
                com.killer560.hub.util.ModLog.get("killer560smod-roomsim").info(
                        "Sim tic tac toe: {} button(s) the capture is missing put back", replaced);
            }
        }
        Arrays.fill(board, EMPTY);
        built = true;
        complete = false;
        Integer opening = getBestMove(board, false);
        if (opening != null) {
            board[opening] = COMPUTER;
        }
        // Straight onto the level, not through paintAll's server.execute: this method already runs on the
        // server thread, from SimRoomPuzzles.armFloor.
        for (int i = 0; i < 9; i++) {
            BlockState state = stateFor(board[i]);
            if (state != null) {
                level.setBlockAndUpdate(positions[i], state);
            }
        }
        return true;
    }

    /** Whether the last game finished without the computer winning (a draw or an O win). False before any game
     *  finishes, and false again after a loss resets the board. */
    public static boolean isComplete() {
        return complete;
    }

    /** Clears the board (blocks and bookkeeping) if a session is still open, and always clears the in-memory
     *  state. Safe to call with no board built, and safe to call after the sim session has already ended. */
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
    /** Non-null while this board is bound to a real captured room rather than a standalone arena. */
    private static volatile com.killer560.hub.roomsim.SimRoomPuzzles.Anchor boundAnchor = null;

    /**
     * A fresh game on the board already standing in a captured room.
     *
     * <p>The buttons come BACK, because a played cell's button is now removed - without this, a second game
     * would start on a board with only the unplayed squares still clickable, and the third with fewer again.
     * The opening move the computer makes keeps its cell buttonless, same as any other played cell.
     */
    private static void restartBound() {
        Arrays.fill(board, EMPTY);
        complete = false;
        built = true;
        Integer opening = getBestMove(board, false);
        if (opening != null) {
            board[opening] = COMPUTER;
        }
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) {
            // One write per cell: an empty cell gets its button back, the computer's opening gets its mark.
            // That used to be three passes (restore every button, paint every cell, then take one button away
            // again) and the order they landed in decided whether the opening cell ended up a mark or a button.
            paintAll(server);
        }
    }

    /** The room's own button, as it was at bind time, so a restart can put nine of them back. */
    private static volatile BlockState buttonState = null;

    /**
     * The block a cell should hold for this mark, or null when there is nothing to write.
     *
     * <p>In a BOUND room an empty cell is the room's own stone button - which is both what the real board looks
     * like and the thing that makes the cell clickable - and a played cell is its mark. In a standalone arena
     * there are no buttons at all, so an empty cell is white concrete and the mark replaces it. Null only when a
     * bound room never managed to record a button to copy, in which case leaving the cell alone beats putting
     * something invented there.
     */
    private static BlockState stateFor(char mark) {
        if (mark != EMPTY) {
            return colourFor(mark);
        }
        if (boundAnchor == null) {
            return McBlocks.WHITE_CONCRETE.defaultBlockState();
        }
        return buttonState;
    }

    public static void forget() {
        boundAnchor = null;
        cellPos = null;
        paintPos = null;
        buttonState = null;
        CELL_INDEX.clear();
        Arrays.fill(board, EMPTY);
        built = false;
        complete = false;
    }

    public static void reset() {
        // ONLY a standalone arena's blocks. cellPos now holds the captured room's own stone buttons, and
        // paintPos its iron wall, so airing either out in a bound room would tear a hole in the room and take
        // the buttons with it. A bound board just goes back to white on the next paint.
        BlockPos[] positions = boundAnchor == null ? cellPos : null;
        Minecraft client = Minecraft.getInstance();
        if (positions != null && SimState.canAct(client)) {
            MinecraftServer server = client.getSingleplayerServer();
            if (server != null) {
                server.execute(() -> {
                    ServerLevel level = server.overworld();
                    for (BlockPos pos : positions) {
                        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    }
                });
            }
        }
        cellPos = null;
        paintPos = null;
        boundAnchor = null;
        CELL_INDEX.clear();
        Arrays.fill(board, EMPTY);
        built = false;
        complete = false;
    }

    /** Player clicked an empty cell: place O, then - unless that already ended the game - let the computer
     *  reply once. Both block colour changes are painted in a single server tick. */
    private static void onPlayerClick(Minecraft client, int index) {
        board[index] = PLAYER;
        int score = getScore(board);
        boolean full = isFull(board);
        Integer replyIndex = null;
        if (score == 0 && !full) {
            replyIndex = getBestMove(board, false);
            if (replyIndex != null) {
                board[replyIndex] = COMPUTER;
            }
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server != null) {
            // Painting the mark IS taking the button away - they are the same block now.
            paintCell(server, index, PLAYER);
            if (replyIndex != null) {
                paintCell(server, replyIndex, COMPUTER);
            }
        }
        finishIfOver();
    }

    private static void finishIfOver() {
        int score = getScore(board);
        boolean full = isFull(board);
        if (score == 0 && !full) {
            return;
        }
        if (score < 0) {
            // The computer got three in a row - the one outcome the real puzzle's "safe prediction" logic
            // exists to make impossible. Treated as a fail: reset, not a silent pass.
            // Tells the Architect's First Draft feature a puzzle failed, so his existing auto-get
            // setting works in here the same as it does on Hypixel.
            // Room name as well as puzzle name - see SimRoomState; this puzzle's room is named for it.
            SimPuzzles.reportFail("Tic Tac Toe", "Tic Tac Toe");
            if (boundAnchor != null) {
                // Bound to a real room: start a fresh game on the same board. reset() here would set the nine
                // cells to AIR, which in a captured room means nine holes punched in its wall and no board
                // left to play on - and "build again to retry" is not something he can do to a generated floor.
                ModChat.send("Sim", ModChat.bad("Computer got three in a row - new game."));
                restartBound();
            } else {
                ModChat.send("Sim",
                        ModChat.bad("Computer got three in a row - resetting. Build again to retry."));
                reset();
            }
        } else {
            complete = true;
            ModChat.send("Sim", ModChat.good(score > 0 ? "You won!" : "Draw - the computer never got three in a row."));
        }
    }

    /** The block a cell's colour is written to - the cell itself, which is also where its button is. */
    private static BlockPos paintTarget(int index) {
        BlockPos[] walls = paintPos;
        if (walls != null && index >= 0 && index < walls.length && walls[index] != null) {
            return walls[index];
        }
        BlockPos[] cells = cellPos;
        return cells == null || index < 0 || index >= cells.length ? null : cells[index];
    }

    private static void paintAll(MinecraftServer server) {
        BlockPos[] targets = new BlockPos[9];
        for (int i = 0; i < 9; i++) {
            targets[i] = paintTarget(i);
        }
        char[] snapshot = board.clone();
        BlockState[] states = new BlockState[9];
        for (int i = 0; i < 9; i++) {
            states[i] = stateFor(snapshot[i]);
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (int i = 0; i < 9; i++) {
                if (targets[i] != null && states[i] != null) {
                    level.setBlockAndUpdate(targets[i], states[i]);
                }
            }
        });
    }

    private static void paintCell(MinecraftServer server, int index, char mark) {
        BlockPos pos = paintTarget(index);
        BlockState state = stateFor(mark);
        if (pos == null || state == null) {
            return;
        }
        server.execute(() -> server.overworld().setBlockAndUpdate(pos, state));
    }

    private static BlockState colourFor(char mark) {
        return switch (mark) {
            case PLAYER -> McBlocks.LIME_CONCRETE.defaultBlockState();
            case COMPUTER -> McBlocks.RED_CONCRETE.defaultBlockState();
            default -> McBlocks.WHITE_CONCRETE.defaultBlockState();
        };
    }

    private static boolean isFull(char[] b) {
        for (char c : b) {
            if (c == EMPTY) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Ported verbatim from com.killer560.hub.puzzlesolvers.TicTacToeSolverFeature - the real win rule and the
    // real move-selection algorithm this puzzle has to honour. Not re-derived, not approximated.
    // ---------------------------------------------------------------------------------------------------------

    private static Integer getBestMove(char[] b, boolean isPlayerTurn) {
        int bestScore = isPlayerTurn ? -1000 : 1000;
        Integer bestIndex = null;
        for (int i : MOVE_ORDER) {
            if (b[i] != EMPTY) {
                continue;
            }
            b[i] = isPlayerTurn ? PLAYER : COMPUTER;
            int score = alphaBeta(b, 0, -1000, 1000, !isPlayerTurn);
            b[i] = EMPTY;
            if (isPlayerTurn && score > bestScore) {
                bestScore = score;
                bestIndex = i;
            } else if (!isPlayerTurn && score < bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private static int alphaBeta(char[] b, int depth, int alpha, int beta, boolean maximising) {
        int score = getScore(b);
        if (score != 0) {
            return score + (score > 0 ? -depth : depth);
        }
        if (isFull(b)) {
            return 0;
        }
        int a = alpha;
        int bBound = beta;
        int best = maximising ? -1000 : 1000;
        for (int i : MOVE_ORDER) {
            if (b[i] != EMPTY) {
                continue;
            }
            b[i] = maximising ? PLAYER : COMPUTER;
            int s = alphaBeta(b, depth + 1, a, bBound, !maximising);
            b[i] = EMPTY;
            if (maximising) {
                best = Math.max(best, s);
                a = Math.max(a, best);
            } else {
                best = Math.min(best, s);
                bBound = Math.min(bBound, best);
            }
            if (bBound <= a) {
                break;
            }
        }
        return best;
    }

    private static int getScore(char[] b) {
        for (int i = 0; i < WIN_SETS.length; i += 3) {
            char c = b[WIN_SETS[i]];
            if (c != EMPTY && c == b[WIN_SETS[i + 1]] && c == b[WIN_SETS[i + 2]]) {
                return c == COMPUTER ? -10 : 10;
            }
        }
        return 0;
    }
}
