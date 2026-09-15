package com.killer560.hub.autopuzzles;

import com.killer560.hub.puzzlesolvers.TicTacToeSolverConfig;
import com.killer560.hub.puzzlesolvers.TicTacToeSolverFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Auto Tic Tac Toe - port of QUOI {@code TicTacToeSolver.kt}'s {@code auto}: whenever the solver has a best move and
 * it is within reach (eye distance squared &lt;= 30), interact that board cell, at most every 500ms. QUOI re-clicks
 * every 500ms until the board changes; this caps it at 3 attempts per move (safety addition) and never clicks while
 * sneaking or with a screen open.
 */
final class AutoTicTacToe {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autopuzzles");
    private static final String ROOM = "Tic Tac Toe";
    private static final double REACH_SQ = 30.0;
    private static final long CLICK_GAP_MS = 500L;
    private static final int MAX_ATTEMPTS = 3;

    private static final AutoGuard GUARD = new AutoGuard("Auto Tic Tac Toe", "Tic Tac Toe Solver");

    private static BlockPos attemptPos = null;
    private static int attempts = 0;
    private static long lastClickMs = 0L;
    private static boolean wasInRoom = false;

    private AutoTicTacToe() {
    }

    static void levelChanged() {
        GUARD.levelChanged();
        reset();
    }

    static void tick(Minecraft client, String roomName) {
        BlockPos best = TicTacToeSolverFeature.getBestMove();
        GUARD.observe(best == null);
        AutoPuzzlesConfig cfg = AutoPuzzlesConfig.getInstance();
        if (!cfg.isAutoTicTacToeEnabled() || !ROOM.equals(roomName)) {
            if (wasInRoom) {
                reset();
                GUARD.leftRoom();
            }
            wasInRoom = false;
            return;
        }
        wasInRoom = true;
        if (!GUARD.solverOn(TicTacToeSolverConfig.getInstance().isEnabled()) || best == null || !GUARD.fresh()) {
            return;
        }
        LocalPlayer player = client.player;
        long now = System.currentTimeMillis();
        if (client.screen != null || player.isShiftKeyDown()
                || player.getEyePosition().distanceToSqr(Vec3.atCenterOf(best)) > REACH_SQ
                || now - lastClickMs < CLICK_GAP_MS) {
            return;
        }
        if (!best.equals(attemptPos)) {
            attemptPos = best;
            attempts = 0;
        }
        if (attempts >= MAX_ATTEMPTS) {
            return;
        }
        attempts++;
        lastClickMs = now;
        if (!AutoPuzzleUtil.interactBlock(client, best)) {
            LOGGER.warn("[AutoPuzzles] TicTacToe: no clickable shape at {} (state={})", best, client.level.getBlockState(best));
            attempts = MAX_ATTEMPTS;
            return;
        }
        LOGGER.info("[AutoPuzzles] TicTacToe: placed at {} (attempt {})", best, attempts);
    }

    private static void reset() {
        attemptPos = null;
        attempts = 0;
        lastClickMs = 0L;
    }
}
