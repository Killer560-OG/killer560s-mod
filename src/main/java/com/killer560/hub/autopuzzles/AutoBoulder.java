package com.killer560.hub.autopuzzles;

import com.killer560.hub.puzzlesolvers.BoulderSolverConfig;
import com.killer560.hub.puzzlesolvers.BoulderSolverFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Auto Boulder. QUOI's {@code BoulderSolver.kt} has NO auto (solver only) and NoammAddons has none either, so this is
 * built on this mod's {@link BoulderSolverFeature} using QUOI's shared click-puzzle pattern (Tic Tac Toe / Water Board
 * {@code AuraManager.interactBlock}): no-rotate interact of the solver's NEXT click position when it is within reach
 * (eye-to-centre distance squared &lt;= 30, QUOI's Tic Tac Toe reach), at most one click per position, spaced by the
 * "Boulder Click Delay". The solver's own {@code useItemOn} TAIL hook removes the step, which advances to the next.
 */
final class AutoBoulder {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autopuzzles");
    private static final String ROOM = "Boulder";
    private static final double REACH_SQ = 30.0;

    private static final AutoGuard GUARD = new AutoGuard("Auto Boulder", "Boulder Solver");

    private static BlockPos pendingPos = null;
    private static long pendingSinceMs = 0L;
    private static BlockPos lastClickedPos = null;
    private static long lastClickMs = 0L;
    private static String lastWaitLog = null;
    private static boolean wasInRoom = false;

    private AutoBoulder() {
    }

    static void levelChanged() {
        GUARD.levelChanged();
        reset();
    }

    static void tick(Minecraft client, String roomName) {
        BlockPos next = BoulderSolverFeature.getNextClick();
        GUARD.observe(next == null);
        AutoPuzzlesConfig cfg = AutoPuzzlesConfig.getInstance();
        if (!cfg.isAutoBoulderEnabled() || !ROOM.equals(roomName)) {
            if (wasInRoom) {
                reset();
                GUARD.leftRoom();
            }
            wasInRoom = false;
            return;
        }
        wasInRoom = true;
        if (!GUARD.solverOn(BoulderSolverConfig.getInstance().isEnabled()) || next == null || !GUARD.fresh()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!next.equals(pendingPos)) {
            pendingPos = next;
            pendingSinceMs = now;
            lastWaitLog = null;
        }
        if (next.equals(lastClickedPos) || client.screen != null) {
            return;
        }
        int delay = cfg.getBoulderDelayMs();
        if (now - pendingSinceMs < delay || now - lastClickMs < delay) {
            return;
        }
        LocalPlayer player = client.player;
        String blocker = null;
        double distSq = player.getEyePosition().distanceToSqr(Vec3.atCenterOf(next));
        if (player.isShiftKeyDown()) {
            blocker = "sneaking";
        } else if (distSq > REACH_SQ) {
            blocker = String.format(java.util.Locale.US, "out of reach (%.2f blocks)", Math.sqrt(distSq));
        }
        if (blocker != null) {
            if (!blocker.equals(lastWaitLog)) {
                lastWaitLog = blocker;
                LOGGER.info("[AutoPuzzles] Boulder: waiting to click {} - {}", next, blocker);
            }
            return;
        }
        lastClickedPos = next;
        lastClickMs = now;
        if (!AutoPuzzleUtil.interactBlock(client, next)) {
            LOGGER.warn("[AutoPuzzles] Boulder: no clickable shape at {} (state={})", next, client.level.getBlockState(next));
            return;
        }
        LOGGER.info("[AutoPuzzles] Boulder: clicked {} ({} step(s) left after this)", next,
                BoulderSolverFeature.getRemainingClicks());
    }

    private static void reset() {
        pendingPos = null;
        pendingSinceMs = 0L;
        lastClickedPos = null;
        lastClickMs = 0L;
        lastWaitLog = null;
    }
}
