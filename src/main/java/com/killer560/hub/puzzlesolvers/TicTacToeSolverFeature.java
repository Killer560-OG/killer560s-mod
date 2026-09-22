package com.killer560.hub.puzzlesolvers;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Hypixel dungeon "Tic Tac Toe" solver, ported from QUOI {@code puzzlesolvers/impl/TicTacToeSolver.kt} (itself
 * modified Skyblocker {@code TicTacToe.java}/{@code TicTacToeUtils.java}). The 3x3 board is the map item frames at
 * room-relative x=8, y 70..72, z 15..17; each map's pixel 8256 is colour 114 (X, the computer) or 33 (O, the player).
 * When an odd number of marks is on the board it is the player's turn and alpha-beta minimax (move order centre,
 * corners, edges) picks the best empty cell, outlined green. Optional "prediction" outlines (yellow) the reply to the
 * computer's most likely next move while it's the computer's turn. Never clicks - see AutoPuzzles for the auto.
 */
public final class TicTacToeSolverFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-puzzles");
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
    private static String lastLoggedState = null;

    private TicTacToeSolverFeature() {
    }

    public static void register() {
        // Shared solver highlight pipelines must exist before the level renderer precompiles them.
        SolverEspRender.init();
        ClientTickEvents.END_CLIENT_TICK.register(TicTacToeSolverFeature::tick);
        // After translucent TERRAIN, not features: water is drawn after the features pass, so a highlight
        // drawn there ended up painted over by any water behind/around it (killer560, 2026-09-21).
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(TicTacToeSolverFeature::onWorldRender);
    }

    /** The cell to place in right now (player's turn), or null. */
    public static BlockPos getBestMove() {
        return bestMove;
    }

    private static void tick(Minecraft client) {
        tickInner(client);
        String state = bestMove == null && predictedMove == null ? "idle" : "best=" + bestMove + " predicted=" + predictedMove;
        if (!state.equals(lastLoggedState)) {
            lastLoggedState = state;
            LOGGER.info("[TicTacToeSolver] State: {}", state);
        }
    }

    private static void tickInner(Minecraft client) {
        if (!TicTacToeSolverConfig.getInstance().isEnabled() || !DungeonState.isInDungeon() || LiveMapFeature.isInBoss()
                || client.level == null) {
            lastRoomEntry = null;
            reset();
            return;
        }
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        if (current != lastRoomEntry) {
            lastRoomEntry = current;
            reset();
        }
        if (current == null || !ROOM.equals(current.name)) {
            return;
        }
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        if (cr == null) {
            return;
        }
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

        if (getScore(board) != 0 || validFrames == 9) {
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
        } else if (TicTacToeSolverConfig.getInstance().isShowPrediction()) {
            bestMove = null;
            Integer safe = safePrediction(board);
            predictedMove = safe == null ? null : indexToPos(safe, cr);
        } else {
            bestMove = null;
            predictedMove = null;
        }
    }

    /**
     * The prediction, only when it is certain (killer560, 2026-09-21: "only as the prediction if it is 100% safe to
     * click that square no matter what the bot will play ... so I should be able to spam click it"): a cell that is
     * one of your best replies to EVERY move the computer could make next (best = the same outcome - win, draw or
     * loss - as your best reply there). If the computer takes that cell itself your click on it simply does
     * nothing, so it is still safe to spam. Null when no cell is safe against every move.
     */
    private static Integer safePrediction(char[] board) {
        Integer found = null;
        for (int s : MOVE_ORDER) {
            if (board[s] != EMPTY) {
                continue;
            }
            boolean safe = true;
            boolean anyReply = false;
            for (int c = 0; c < 9 && safe; c++) {
                if (board[c] != EMPTY || c == s) {
                    continue;
                }
                board[c] = 'X';
                if (getScore(board) == 0) {
                    anyReply = true;
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
                } else {
                    safe = false; // the computer could win outright instead - nothing is safe then
                }
                board[c] = EMPTY;
            }
            if (safe && anyReply) {
                found = s;
                break;
            }
        }
        return found;
    }

    private static BlockPos indexToPos(int i, int[] cr) {
        return PuzzleCoords.real(8, 72 - (i / 3), 17 - (i % 3), cr);
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
            SolverEspRender.renderFilledBox(context, box, r, g, b, 0.5f);
        } else {
            SolverEspRender.renderOutlineBox(context, box, r, g, b, 0.9f, thickness);
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
