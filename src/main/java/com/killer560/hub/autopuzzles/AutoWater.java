package com.killer560.hub.autopuzzles;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.puzzlesolvers.PuzzleCoords;
import com.killer560.hub.puzzlesolvers.WaterSolverConfig;
import com.killer560.hub.puzzlesolvers.WaterSolverFeature;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Auto Water Board - port of QUOI {@code WaterBoardSolver.kt}'s {@code auto} on top of {@link WaterSolverFeature}'s
 * lever timings. Standing on the lever floor (y == 59), the soonest remaining click (QUOI {@code solutionList} order:
 * all 0s clicks first by lever, then by time) is flipped with a no-rotate interact as soon as it is due: the water
 * lever when the water hasn't been opened yet (or its time is up), any other lever when
 * {@code openedWaterTick + time*20 - tick <= 0}. The click is counted by the solver's own {@code useItemOn} hook, which
 * advances to the next one.
 * <p>
 * Positioning: with "Etherwarp Reposition" on it warps to QUOI's lever spot (relative 15,58,z with z = lever z for
 * z 20/15, 9 for z 10/5) before each click and to the chest spot (15,58,22) when done; with it off it clicks from
 * wherever you stand as long as the lever is within reach (distance squared &lt;= 30). Safety additions: a 2-tick gap
 * between clicks, a timed (non-zero) click never fires before the water lever was opened, and if a click isn't
 * counted by the solver the auto stops for this room rather than re-flipping the lever.
 */
final class AutoWater {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autopuzzles");
    private static final String ROOM = "Water Board";
    private static final double REACH_SQ = 30.0;
    private static final long CLICK_GAP_TICKS = 2;

    private static final AutoGuard GUARD = new AutoGuard("Auto Water Board", "Water Board Solver");
    private static final AutoReposition REPOSITION = new AutoReposition("Water");

    private static long lastClickTick = Long.MIN_VALUE / 2;
    private static long lastClickMs = 0L;
    private static boolean atChest = false;
    private static boolean stoppedThisRoom = false;
    private static boolean wasInRoom = false;
    private static String lastWaitLog = null;

    private AutoWater() {
    }

    static void levelChanged(Minecraft client) {
        GUARD.levelChanged();
        reset(client);
    }

    static void tick(Minecraft client, String roomName) {
        WaterSolverFeature.NextClick next = WaterSolverFeature.nextClick();
        GUARD.observe(next == null && WaterSolverFeature.getCountedClicks() == 0);
        AutoPuzzlesConfig cfg = AutoPuzzlesConfig.getInstance();
        if (!cfg.isAutoWaterEnabled() || !ROOM.equals(roomName)) {
            if (wasInRoom) {
                reset(client);
                GUARD.leftRoom();
            }
            wasInRoom = false;
            return;
        }
        wasInRoom = true;
        if (!GUARD.solverOn(WaterSolverConfig.getInstance().isEnabled()) || stoppedThisRoom || !GUARD.fresh()) {
            return;
        }
        LocalPlayer player = client.player;
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        if (cr == null || player.getY() != 59.0 || client.screen != null || atChest) {
            return;
        }
        if (REPOSITION.isActive()) {
            REPOSITION.tick(client);
            return;
        }
        boolean reposition = cfg.isEtherwarpReposition();
        if (next == null) {
            if (WaterSolverFeature.getCountedClicks() > 0) {
                // QUOI chest(): all clicks done - warp to the chest spot and let go of sneak.
                BlockPos chestSpot = PuzzleCoords.real(15, 58, 22, cr);
                if (reposition && !AutoPuzzleUtil.at(player, chestSpot)) {
                    REPOSITION.start(client, chestSpot, false, false, false);
                    return;
                }
                AutoReposition.releaseSneak(client);
                atChest = true;
                LOGGER.info("[AutoPuzzles] Water: all lever clicks done");
                ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Water Board: "), ModChat.good("done"), ModChat.text("."));
            }
            return;
        }

        int z = switch (next.relativeZ()) {
            case 20, 15 -> next.relativeZ();
            case 10, 5 -> 9;
            default -> Integer.MIN_VALUE;
        };
        if (z == Integer.MIN_VALUE) {
            return;
        }
        long now = System.currentTimeMillis();
        if (reposition) {
            BlockPos spot = PuzzleCoords.real(15, 58, z, cr);
            if (!AutoPuzzleUtil.at(player, spot)) {
                if (now - lastClickMs >= 200) {
                    REPOSITION.start(client, spot, false, false, false);
                }
                return;
            }
        }

        long tick = WaterSolverFeature.getTickCounter();
        long opened = WaterSolverFeature.getOpenedWaterTick();
        double remaining = opened - tick + next.time() * 20;
        boolean due = next.water()
                ? (opened == -1 || remaining <= 0)
                : (remaining <= 0 && (next.time() == 0.0 || opened != -1));
        if (!due || tick - lastClickTick < CLICK_GAP_TICKS) {
            return;
        }
        double distSq = player.getEyePosition().distanceToSqr(Vec3.atCenterOf(next.pos()));
        String blocker = player.isShiftKeyDown() ? "sneaking"
                : distSq > REACH_SQ ? String.format(java.util.Locale.US, "out of reach (%.2f blocks)", Math.sqrt(distSq)) : null;
        if (blocker != null) {
            if (!blocker.equals(lastWaitLog)) {
                lastWaitLog = blocker;
                LOGGER.info("[AutoPuzzles] Water: click at {} due but {}", next.pos(), blocker);
            }
            return;
        }
        lastWaitLog = null;
        int countedBefore = WaterSolverFeature.getCountedClicks();
        lastClickTick = tick;
        lastClickMs = now;
        if (!AutoPuzzleUtil.interactBlock(client, next.pos())) {
            LOGGER.warn("[AutoPuzzles] Water: no clickable shape at {} - stopping for this room", next.pos());
            stoppedThisRoom = true;
            return;
        }
        if (WaterSolverFeature.getCountedClicks() <= countedBefore) {
            stoppedThisRoom = true;
            LOGGER.warn("[AutoPuzzles] Water: click at {} was not counted by the solver - stopping for this room", next.pos());
            ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Water Board: a lever click "), ModChat.bad("didn't register"),
                    ModChat.text(" - auto stopped for this room."));
            return;
        }
        LOGGER.info("[AutoPuzzles] Water: clicked {} (water={} time={}s tick={} opened={})", next.pos(), next.water(),
                next.time(), tick, opened);
    }

    private static void reset(Minecraft client) {
        REPOSITION.cancel(client);
        AutoReposition.releaseSneak(client);
        lastClickTick = Long.MIN_VALUE / 2;
        lastClickMs = 0L;
        atChest = false;
        stoppedThisRoom = false;
        lastWaitLog = null;
    }
}
