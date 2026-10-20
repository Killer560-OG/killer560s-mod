package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;

/**
 * Hypixel dungeon "Tic Tac Toe" solver, ported from QUOI {@code puzzlesolvers/impl/TicTacToeSolver.kt} (itself
 * modified Skyblocker {@code TicTacToe.java}/{@code TicTacToeUtils.java}). The 3x3 board is the map item frames at
 * room-relative x=8, y 70..72, z 15..17; each map's pixel 8256 is colour 114 (X, the computer) or 33 (O, the player).
 * When an odd number of marks is on the board it is the player's turn and alpha-beta minimax (move order centre,
 * corners, edges) picks the best empty cell, outlined green. Optional "prediction" outlines (yellow), while it is the
 * computer's turn, the square HE clicks next - his best move after the computer's best reply ({@link #safePrediction}).
 * Never clicks - see AutoPuzzles for the auto. Prevent Misclicks fails his click on any other board square.
 */
public final class TicTacToeSolverFeature {

    private static final String ROOM = "Tic Tac Toe";
    private static final char EMPTY = '\0';
    private static final int[] MOVE_ORDER = {4, 0, 2, 6, 8, 1, 3, 5, 7};
    private static final int[] WIN_SETS = {
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            0, 3, 6, 1, 4, 7, 2, 5, 8,
            0, 4, 8, 2, 4, 6
    };

    private static RoomEntry lastRoomEntry = null;
    private static int lastBoardHash = 0;
    private static BlockPos bestMove = null;
    private static BlockPos predictedMove = null;
    /**
     * The game on this board has ended (a line of three, or all nine squares marked) since he walked into the room.
     * Cleared only by a room change. Auto Tic Tac Toe's walk-out waits for THIS, not for "no best move": on Hypixel
     * the computer answers seconds after his click, and the board in between (his mark down, no reply yet) has no
     * move for him either - 2026-10-14 17:26:00, his Mod Only Test log, the walk-out fired on that gap after the
     * first click and took him out of the room.
     */
    private static boolean gameOver = false;

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-puzzlesolvers");
    /** Logging only: last room state logged ("none" outside the room), so lines are written on CHANGE, never per tick. */
    private static String roomLogKey = "none";

    private static void logRoomState(String key, String detail) {
        if (key.equals(roomLogKey)) {
            return;
        }
        roomLogKey = key;
        LOGGER.info("[TicTacToeSolver] {}", detail);
    }

    /** Logging only: the board as 9 chars, row by row from the top ('X' computer, 'O' player, '.' empty). */
    private static String boardString(char[] board) {
        StringBuilder sb = new StringBuilder(11);
        for (int i = 0; i < 9; i++) {
            if (i > 0 && i % 3 == 0) {
                sb.append('/');
            }
            sb.append(board[i] == EMPTY ? '.' : board[i]);
        }
        return sb.toString();
    }

    private TicTacToeSolverFeature() {
    }

    public static void register() {
        // Shared solver highlight pipelines must exist before the level renderer precompiles them.
        SolverEspRender.init();
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("TicTacToeSolverFeature.tick", com.killer560.hub.util.FeatureBits.register("TicTacToe Solver tick", () -> TicTacToeSolverConfig.getInstance().isEnabledRaw()), TicTacToeSolverFeature::tick));
        // After translucent TERRAIN, not features: water is drawn after the features pass, so a highlight
        // drawn there ended up painted over by any water behind/around it (killer560, 2026-09-21).
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(com.killer560.hub.util.FeatureGuard.afterTerrain(
                com.killer560.hub.util.FeatureBits.register("TicTacToe Solver render", () -> TicTacToeSolverConfig.getInstance().isEnabledRaw()), TicTacToeSolverFeature::onWorldRender));
    }

    /** The cell to place in right now (player's turn), or null. */
    public static BlockPos getBestMove() {
        return bestMove;
    }

    /** True once the game in this room has ended (win, loss or a full board), until he leaves the room. */
    public static boolean isGameOver() {
        return gameOver;
    }

    /** The world position of board square {@code i} (0..8, row by row from the top) for a room's clay/rotation. */
    public static BlockPos cellPos(int i, int[] clayAndRotation) {
        return indexToPos(i, clayAndRotation);
    }

    private static void tick(Minecraft client) {
        tickInner(client);
    }

    private static void tickInner(Minecraft client) {
        if (!TicTacToeSolverConfig.getInstance().isEnabled() || !DungeonState.isInDungeon() || LiveMapFeature.isInBoss()
                || client.level == null) {
            if (!"none".equals(roomLogKey)) {
                logRoomState("none", "solver idle: enabled=" + TicTacToeSolverConfig.getInstance().isEnabled()
                        + " inDungeon=" + DungeonState.isInDungeon() + " inBoss=" + LiveMapFeature.isInBoss()
                        + " level=" + (client.level != null) + " - board cleared");
            }
            lastRoomEntry = null;
            gameOver = false;
            reset();
            return;
        }
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        if (current != lastRoomEntry) {
            lastRoomEntry = current;
            boardCr = null;
            gameOver = false;
            reset();
        }
        if (current == null || !ROOM.equals(current.name)) {
            if (!"none".equals(roomLogKey)) {
                logRoomState("none", "left Tic Tac Toe: now in " + (current == null ? "no known room" : current.name)
                        + " (gameOver was reset)");
            }
            return;
        }
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        if (cr == null) {
            logRoomState("wait", "in Tic Tac Toe but clay/rotation not known yet - board not read, waiting for the map");
            return;
        }
        String roomKey = "room " + cr[0] + "," + cr[1] + " rot " + cr[2];
        if (!roomKey.equals(roomLogKey)) {
            logRoomState(roomKey, "Tic Tac Toe detected: clay=" + cr[0] + "," + cr[1] + " rotation=" + cr[2]
                    + " top-left square " + indexToPos(0, cr).toShortString() + " bottom-right "
                    + indexToPos(8, cr).toShortString() + " preventMisclicks="
                    + TicTacToeSolverConfig.getInstance().isPreventMisclicks() + " prediction="
                    + TicTacToeSolverConfig.getInstance().isShowPrediction());
        }
        boardCr = cr;
        ClientLevel level = client.level;
        AABB searchBox = AABB.ofSize(Vec3.atCenterOf(PuzzleCoords.real(8, 71, 16, cr)), 12.0, 12.0, 12.0);
        List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class, searchBox,
                f -> f.getItem().getItem() instanceof MapItem && f.getItem().has(DataComponents.MAP_ID));

        char[] board = new char[9];
        int validFrames = 0;
        for (ItemFrame frame : frames) {
            BlockPos rel = PuzzleCoords.relative(frame.blockPosition(), cr);
            int row = 72 - rel.getY();
            int col = 17 - rel.getZ();
            if (row < 0 || row > 2 || col < 0 || col > 2) {
                continue;
            }
            ItemStack stack = frame.getItem();
            MapId mapId = stack.get(DataComponents.MAP_ID);
            if (mapId == null) {
                continue;
            }
            MapItemSavedData data = level.getMapData(mapId);
            if (data == null || data.colors == null || data.colors.length <= 8256) {
                continue;
            }
            int colour = data.colors[8256] & 0xFF;
            if (colour == 114 || colour == 33) {
                board[row * 3 + col] = colour == 114 ? 'X' : 'O';
                validFrames++;
            }
        }

        boolean fromFrames = validFrames > 0; // logging only: which medium the board was read from
        if (validFrames == 0) {
            // THE SIM HAS NO MAPS. The real board's nine marks are maps in item frames, and an item frame is an
            // entity - a room capture holds blocks and nothing else, so the dungeon sim has no way to paste them
            // and this scan finds nothing at all in there. Its board writes its marks as concrete on the same
            // nine cells instead (SimTicTacToePuzzle), which is the one thing this scan can be taught to read
            // without a second notion of where the board is. Only reached when there were no frames, so a real
            // dungeon never takes this path.
            validFrames = readSimBoard(level, cr, board);
        }
        if (getScore(board) != 0 || validFrames == 9) {
            if (!gameOver) {
                int score = getScore(board);
                LOGGER.info("[TicTacToeSolver] game over: board {} ({} marks, read from {}) - {}", boardString(board),
                        validFrames, fromFrames ? "map frames" : "sim concrete",
                        score > 0 ? "player (O) has three in a row" : score < 0 ? "computer (X) has three in a row"
                                : "board full, draw");
            }
            gameOver = true;
            reset();
            return;
        }
        int hash = Arrays.hashCode(board);
        if (hash == lastBoardHash) {
            return;
        }
        lastBoardHash = hash;

        if (validFrames % 2 != 0) {
            predictedMove = null;
            Integer best = getBestMove(board, true);
            bestMove = best == null ? null : indexToPos(best, cr);
            LOGGER.info("[TicTacToeSolver] board changed: {} ({} marks, {} item frame(s) near, read from {}) - player's turn,"
                            + " best move {}", boardString(board), validFrames, frames.size(),
                    fromFrames ? "map frames" : "sim concrete",
                    best == null ? "none (no empty square)" : "square " + best + " at " + bestMove.toShortString());
        } else if (TicTacToeSolverConfig.getInstance().isShowPrediction()) {
            bestMove = null;
            Integer safe = safePrediction(board);
            predictedMove = safe == null ? null : indexToPos(safe, cr);
            LOGGER.info("[TicTacToeSolver] board changed: {} ({} marks, {} item frame(s) near, read from {}) - computer's"
                            + " turn, prediction {}", boardString(board), validFrames, frames.size(),
                    fromFrames ? "map frames" : "sim concrete",
                    safe == null ? "none (no square is safe after every best reply)"
                            : "square " + safe + " at " + predictedMove.toShortString());
        } else {
            bestMove = null;
            predictedMove = null;
            LOGGER.info("[TicTacToeSolver] board changed: {} ({} marks, {} item frame(s) near, read from {}) - computer's"
                            + " turn, nothing shown (prediction off)", boardString(board), validFrames, frames.size(),
                    fromFrames ? "map frames" : "sim concrete");
        }
    }

    /**
     * The square HE clicks next, worked out while it is the computer's turn (killer560, 2026-10-09: "the prediction
     * shows where the server is going to go; it needs to show where I should be clicking ... he just went, then the
     * server must go here, which means it is 100% safe to click this next button no matter what").
     *
     * <p>The computer's reply is not guessed: every reply with the computer's best outcome (minimax from its side) is
     * taken. A square qualifies only if the computer takes none of them - so it is still empty when his click lands -
     * and it is one of his best replies (same outcome: win, draw or loss) after EACH of them. Usually the computer has
     * one best reply (a block, a fork), so this is simply his best move on the board after it. The old rule skipped the
     * computer's own square when testing it, so the square it was about to take always qualified and was what got
     * shown. Null when no square is safe whichever best reply comes.
     */
    static Integer safePrediction(char[] board) {
        List<Integer> replies = new java.util.ArrayList<>();
        int[] values = new int[9];
        int bestForComputer = Integer.MAX_VALUE;
        for (int c : MOVE_ORDER) {
            if (board[c] != EMPTY) {
                continue;
            }
            board[c] = 'X';
            int v = alphaBeta(board, 0, -1000, 1000, true);
            board[c] = EMPTY;
            values[c] = v;
            bestForComputer = Math.min(bestForComputer, v);
        }
        // Every reply with the computer's best OUTCOME (win, draw or loss), not only the one the search ranks first:
        // nothing says which of two equally good squares Hypixel's bot takes, so the square shown must survive all.
        for (int c : MOVE_ORDER) {
            if (board[c] == EMPTY && Integer.signum(values[c]) == Integer.signum(bestForComputer)) {
                replies.add(c);
            }
        }
        if (replies.isEmpty()) {
            return null;
        }
        for (int s : MOVE_ORDER) {
            if (board[s] != EMPTY || replies.contains(s)) {
                continue;
            }
            boolean safe = true;
            for (int c : replies) {
                board[c] = 'X';
                if (getScore(board) != 0) {
                    safe = false; // the computer wins outright: nothing he clicks matters
                } else {
                    int best = Integer.MIN_VALUE;
                    int mine = Integer.MIN_VALUE;
                    for (int r = 0; r < 9; r++) {
                        if (board[r] != EMPTY) {
                            continue;
                        }
                        board[r] = 'O';
                        int outcome = Integer.signum(alphaBeta(board, 0, -1000, 1000, false));
                        board[r] = EMPTY;
                        best = Math.max(best, outcome);
                        if (r == s) {
                            mine = outcome;
                        }
                    }
                    safe = mine == best;
                }
                board[c] = EMPTY;
                if (!safe) {
                    break;
                }
            }
            if (safe) {
                return s;
            }
        }
        return null;
    }

    /** For the testkit: the prediction on a board given as 9 chars ('X', 'O', anything else empty), row by row. */
    public static Integer predictionFor(String cells) {
        return safePrediction(parse(cells));
    }

    /** For the testkit: his best move (his turn) on a board given the same way, or null. */
    public static Integer bestMoveFor(String cells) {
        return getBestMove(parse(cells), true);
    }

    private static char[] parse(String cells) {
        char[] board = new char[9];
        for (int i = 0; i < 9; i++) {
            char ch = cells.charAt(i);
            board[i] = ch == 'X' || ch == 'O' ? ch : EMPTY;
        }
        return board;
    }

    // ---------------------------------------------------------------- Prevent Misclicks

    /** The board's clay corner and rotation while he is in the room, for {@link #shouldBlockClick}. */
    private static int[] boardCr = null;
    private static int blockedClicks;

    /** The square a click should land on right now: his move on his turn, else the shown prediction; null for none. */
    public static BlockPos currentTarget() {
        if (bestMove != null) {
            return bestMove;
        }
        return TicTacToeSolverConfig.getInstance().isShowPrediction() ? predictedMove : null;
    }

    /**
     * Prevent Misclicks (killer560, 2026-10-09: "add a prevent missclicks for tictactoe"): true for a click on one of
     * the nine board squares other than {@link #currentTarget()} while one is shown. The mixin then fails the click on
     * the client, so nothing is sent. Sneaking lets it through (the same override Simon Says has).
     */
    public static boolean shouldBlockClick(BlockPos pos, boolean sneaking) {
        TicTacToeSolverConfig cfg = TicTacToeSolverConfig.getInstance();
        int[] cr = boardCr;
        BlockPos target = currentTarget();
        if (!cfg.isEnabled() || !cfg.isPreventMisclicks() || sneaking || cr == null || target == null || pos == null
                || pos.equals(target)) {
            if (sneaking && cfg.isEnabled() && cfg.isPreventMisclicks() && cr != null && target != null && pos != null
                    && !pos.equals(target)) {
                for (int i = 0; i < 9; i++) {
                    if (pos.equals(indexToPos(i, cr))) {
                        LOGGER.info("[TicTacToeSolver] misclick let through: square {} at {} while the target is {} -"
                                + " sneaking overrides Prevent Misclicks", i, pos.toShortString(), target.toShortString());
                        break;
                    }
                }
            } else if (pos != null && target != null && pos.equals(target) && cfg.isEnabled()) {
                LOGGER.info("[TicTacToeSolver] click on the target square {} ({})", pos.toShortString(),
                        target.equals(bestMove) ? "best move" : "prediction");
            }
            return false;
        }
        for (int i = 0; i < 9; i++) {
            if (pos.equals(indexToPos(i, cr))) {
                blockedClicks++;
                LOGGER.info("[TicTacToeSolver] misclick blocked: clicked square {} at {}, expected {} ({}) - click failed"
                                + " on the client, nothing sent ({} blocked so far)", i, pos.toShortString(),
                        target.toShortString(), target.equals(bestMove) ? "best move" : "prediction", blockedClicks);
                return true;
            }
        }
        return false;
    }

    /** How many clicks Prevent Misclicks has stopped (the testkit reads it). */
    public static int blockedClicks() {
        return blockedClicks;
    }

    /** The prediction square, for the testkit. */
    public static BlockPos getPredictedMove() {
        return predictedMove;
    }

    private static BlockPos indexToPos(int i, int[] cr) {
        return PuzzleCoords.real(8, 72 - (i / 3), 17 - (i % 3), cr);
    }

    /**
     * The dungeon sim's board, read off the nine cells themselves.
     *
     * <p>Same nine coordinates, same {@code row = 72 - y} / {@code col = 17 - z} indexing, same two marks - only
     * the medium differs: red concrete is the computer's X and lime concrete is the player's O, which is what
     * {@code SimTicTacToePuzzle} writes. An unplayed cell is the room's own stone button and counts as empty.
     *
     * @return how many cells are marked, which is what the turn test needs
     */
    private static int readSimBoard(ClientLevel level, int[] cr, char[] board) {
        int marks = 0;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                // The cell, or the wall one block behind it (x=7): the sim paints a played cell's mark on the wall
                // and takes the button away (killer560, 2026-10-01: "have them back one and just delete the
                // button").
                char mark = simMark(level.getBlockState(PuzzleCoords.real(8, 72 - row, 17 - col, cr)));
                if (mark == EMPTY) {
                    mark = simMark(level.getBlockState(PuzzleCoords.real(7, 72 - row, 17 - col, cr)));
                }
                if (mark != EMPTY) {
                    board[row * 3 + col] = mark;
                    marks++;
                }
            }
        }
        return marks;
    }

    private static char simMark(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(com.killer560.hub.compat.McBlocks.RED_CONCRETE) ? 'X'
                : state.is(com.killer560.hub.compat.McBlocks.LIME_CONCRETE) ? 'O' : EMPTY;
    }

    private static Integer getBestMove(char[] board, boolean isPlayer) {
        int bestScore = isPlayer ? -1000 : 1000;
        Integer bestIndex = null;
        for (int i : MOVE_ORDER) {
            if (board[i] != EMPTY) {
                continue;
            }
            board[i] = isPlayer ? 'O' : 'X';
            int score = alphaBeta(board, 0, -1000, 1000, !isPlayer);
            board[i] = EMPTY;
            if (isPlayer && score > bestScore) {
                bestScore = score;
                bestIndex = i;
            } else if (!isPlayer && score < bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private static int alphaBeta(char[] board, int depth, int alpha, int beta, boolean maximising) {
        int score = getScore(board);
        if (score != 0) {
            return score + (score > 0 ? -depth : depth);
        }
        boolean full = true;
        for (char c : board) {
            if (c == EMPTY) {
                full = false;
                break;
            }
        }
        if (full) {
            return 0;
        }
        int a = alpha;
        int b = beta;
        int best = maximising ? -1000 : 1000;
        for (int i : MOVE_ORDER) {
            if (board[i] != EMPTY) {
                continue;
            }
            board[i] = maximising ? 'O' : 'X';
            int s = alphaBeta(board, depth + 1, a, b, !maximising);
            board[i] = EMPTY;
            if (maximising) {
                best = Math.max(best, s);
                a = Math.max(a, best);
            } else {
                best = Math.min(best, s);
                b = Math.min(b, best);
            }
            if (b <= a) {
                break;
            }
        }
        return best;
    }

    /** Only the clickable thing, like Simon Says (killer560, 2026-09-21: "for tictactoe I only want the button
     *  highlighted so follow the same logic used for simon says"): a button there -> its real shape; else the item
     *  frame on that cell -> the frame's own thin box; never the whole block. Fill or outline per the setting. */
    private static void drawCell(LevelRenderContext context, BlockPos pos, float r, float g, float b, float thickness) {
        Minecraft client = Minecraft.getInstance();
        AABB box = null;
        if (client.level != null) {
            var shape = client.level.getBlockState(pos).getShape(client.level, pos);
            if (!shape.isEmpty()) {
                box = shape.bounds().move(pos);
            } else {
                for (ItemFrame frame : client.level.getEntitiesOfClass(ItemFrame.class, new AABB(pos))) {
                    if (frame.blockPosition().equals(pos)) {
                        box = frame.getBoundingBox();
                        break;
                    }
                }
            }
        }
        if (box == null) {
            box = new AABB(pos);
        }
        if (TicTacToeSolverConfig.getInstance().isFill()) {
            SolverEspRender.renderFilledBox(com.killer560.hub.puzzlesolvers.SolverEspConfig.Solver.TIC_TAC_TOE, context, box, r, g, b, 0.5f);
        } else {
            SolverEspRender.renderOutlineBox(com.killer560.hub.puzzlesolvers.SolverEspConfig.Solver.TIC_TAC_TOE, context, box, r, g, b, 0.9f, thickness);
        }
    }

    private static int getScore(char[] board) {
        for (int i = 0; i < WIN_SETS.length; i += 3) {
            char c = board[WIN_SETS[i]];
            if (c != EMPTY && c == board[WIN_SETS[i + 1]] && c == board[WIN_SETS[i + 2]]) {
                return c == 'X' ? -10 : 10;
            }
        }
        return 0;
    }

    private static void onWorldRender(LevelRenderContext context) {
        if (!TicTacToeSolverConfig.getInstance().isEnabled()) {
            return;
        }
        BlockPos best = bestMove;
        if (best != null) {
            drawCell(context, best, 0.33f, 1.0f, 0.33f, 3f);
        }
        BlockPos predicted = predictedMove;
        if (predicted != null && TicTacToeSolverConfig.getInstance().isShowPrediction()) {
            drawCell(context, predicted, 1.0f, 1.0f, 0.33f, 2f);
        }
    }

    private static void reset() {
        lastBoardHash = 0;
        bestMove = null;
        predictedMove = null;
    }
}
