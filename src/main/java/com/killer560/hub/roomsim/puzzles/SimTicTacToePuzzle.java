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

    /** Cell index (row*3+col) -> world position. Empty until {@link #build}. */
    private static volatile BlockPos[] cellPos = null;
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
            return InteractionResult.SUCCESS;
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

    /** Whether the last game finished without the computer winning (a draw or an O win). False before any game
     *  finishes, and false again after a loss resets the board. */
    public static boolean isComplete() {
        return complete;
    }

    /** Clears the board (blocks and bookkeeping) if a session is still open, and always clears the in-memory
     *  state. Safe to call with no board built, and safe to call after the sim session has already ended. */
    public static void reset() {
        BlockPos[] positions = cellPos;
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
            ModChat.send("Sim", ModChat.bad("Computer got three in a row - resetting. Build again to retry."));
            reset();
        } else {
            complete = true;
            ModChat.send("Sim", ModChat.good(score > 0 ? "You won!" : "Draw - the computer never got three in a row."));
        }
    }

    private static void paintAll(MinecraftServer server) {
        BlockPos[] positions = cellPos;
        char[] snapshot = board.clone();
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (int i = 0; i < 9; i++) {
                level.setBlockAndUpdate(positions[i], colourFor(snapshot[i]));
            }
        });
    }

    private static void paintCell(MinecraftServer server, int index, char mark) {
        BlockPos pos = cellPos[index];
        server.execute(() -> server.overworld().setBlockAndUpdate(pos, colourFor(mark)));
    }

    private static BlockState colourFor(char mark) {
        return switch (mark) {
            case PLAYER -> Blocks.LIME_CONCRETE.defaultBlockState();
            case COMPUTER -> Blocks.RED_CONCRETE.defaultBlockState();
            default -> Blocks.WHITE_CONCRETE.defaultBlockState();
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
