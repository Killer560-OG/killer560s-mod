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
import com.killer560.hub.util.ModLog;
import com.killer560.hub.compat.McCompat;

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
 * <p>
 * killer560, 2026-09-27: "if i enter the room then it will auto etherwarp pathfind to the start area." On a fresh
 * room visit (once, before any lever logic) this walks you to {@code AutoClearUtils}'s own "Water Board" room
 * spot (relative 15,58,9 - the same doorway-side spot "Interactive Map" itself already uses to walk a player
 * INTO this room), via {@link AutoPuzzleUtil#pathIfMapOn} - so it only runs while Interactive Map is on, the same
 * engine every other auto-walk in this mod already depends on. This is separate from "Etherwarp Reposition"
 * above (a short in-room blink onto one lever), so it works even with that toggle off.
 */
final class AutoWater {

    private static final Logger LOGGER = ModLog.get("killer560smod-autopuzzles");
    private static final String ROOM = "Water Board";
    /** Measured block reach, squared - was 30.0 (5.48 blocks) to the centre. */
    private static final double REACH_SQ = AutoPuzzleUtil.BLOCK_REACH_SQ;
    private static final long CLICK_GAP_TICKS = 2;
    /** AutoClearUtils' own "Water Board" room override - the doorway-side spot, not a guess. */
    private static final int[] START_SPOT_RELATIVE = com.killer560.hub.livemap.autoclear.AutoClearUtils.roomOverride(ROOM);

    private static final AutoGuard GUARD = new AutoGuard("Auto Water Board", "Water Board Solver");
    /** Held from the first reposition warp until the chest spot (see {@link FreeCam}). */
    private static final FreeCam CAMERA = new FreeCam("Water");
    private static final AutoReposition REPOSITION = new AutoReposition("Water", CAMERA);

    private static long lastClickTick = Long.MIN_VALUE / 2;
    private static long lastClickMs = 0L;
    private static boolean atChest = false;
    private static boolean stoppedThisRoom = false;
    private static boolean wasInRoom = false;
    /** Whether the one-shot "walk to the start area" has been attempted for this room visit yet. */
    private static boolean startAreaAttempted = false;

    private AutoWater() {
    }

    static void levelChanged(Minecraft client) {
        GUARD.levelChanged();
        CAMERA.drop();
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
        CAMERA.keep(client.player);
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        if (!startAreaAttempted && cr != null && McCompat.screen(client) == null) {
            // Pure navigation, no solver data involved - tried once per room visit regardless of the solver/GUARD
            // state below, same as walking OUT of Boulder/Teleport Maze doesn't wait on their solvers either.
            BlockPos start = START_SPOT_RELATIVE == null ? null
                    : PuzzleCoords.real(START_SPOT_RELATIVE[0], START_SPOT_RELATIVE[1], START_SPOT_RELATIVE[2], cr);
            if (start == null) {
                startAreaAttempted = true; // no known spot for this room name - nothing to walk to, don't retry forever
            } else if (AutoPuzzleUtil.at(client.player, start)) {
                startAreaAttempted = true;
            } else if (AutoPuzzleUtil.pathIfMapOn(start, null)) {
                startAreaAttempted = true;
            }
            // else: Interactive Map is off, or another path is already running - leave startAreaAttempted false
            // and just try again next tick; cheap to check.
        }
        if (com.killer560.hub.livemap.autoclear.ClearExecutor.isBusy()) {
            return; // still walking to the start area (ours or someone else's) - the levers can wait
        }
        if (!GUARD.solverOn(WaterSolverConfig.getInstance().isEnabled()) || stoppedThisRoom || !GUARD.fresh()) {
            CAMERA.release(client.player);
            return;
        }
        LocalPlayer player = client.player;
        // 59 IS A ROOM-RELATIVE HEIGHT, and in the sim the whole floor moves. Water Board's lever floor is
        // relative y 58 (AutoClearUtils' own override for this room is {15, 58, 9}), so standing on it is 59 -
        // on Hypixel, where relative and absolute y are the same number. The sim shifts every room by one
        // offset, so the literal 59 could never be true in there and Auto Water declined every click without
        // saying anything. Same fault, same shape, as Auto Creeper Beams' "y == 75".
        if (cr == null || McCompat.screen(client) != null || atChest) {
            return;
        }
        if (player.getY() != 59.0 + com.killer560.hub.livemap.DungeonLayout.simYOffset() && !REPOSITION.isActive()) {
            waitFor(String.format(java.util.Locale.US, "not on the lever floor (feet y %.2f, the floor is %d)",
                    player.getY(), 59 + com.killer560.hub.livemap.DungeonLayout.simYOffset()));
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
                CAMERA.release(player);
                atChest = true;
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
            waitFor("next lever " + AutoPuzzleUtil.fmt(next.pos()) + " has an unknown relative z " + next.relativeZ());
            return;
        }
        long now = System.currentTimeMillis();
        if (reposition && !inReach(player.getEyePosition(), next.pos())) {
            // Only warp when the lever is NOT already in reach from where he stands - and then onto a spot from
            // which it IS. QUOI's spot (15,58,z) is the board's centre line, five blocks from every side lever:
            // eye-to-box 4.52-4.54, past the 4.5 measured on Hypixel (2026-10-04 93-solve: "4.516 vs 4.5", the
            // lever at z 10 never clicked and nothing was logged). See leverSpot.
            BlockPos spot = leverSpot(client, cr, next.pos(), z);
            if (spot == null) {
                waitFor("no standable spot on the lever floor has " + AutoPuzzleUtil.fmt(next.pos()) + " in reach");
                return;
            }
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
        // To the BOX, not the centre. The limit is 4.5 to the box; measuring to the centre reads up to half
        // a block further, so pairing the two made this stricter than the server is and the auto
        // refused reachable blocks. Caught 2026-09-29 on Auto Water Board, which opened the water and
        // then stalled on every other lever at a measured 5.08-5.2 to centre.
        double distSq = com.killer560.hub.util.BlockHits.boxDistanceSq(player.getEyePosition(), next.pos());
        String blocker = player.isShiftKeyDown() ? "sneaking"
                : distSq > REACH_SQ ? String.format(java.util.Locale.US, "out of reach (%.2f blocks)", Math.sqrt(distSq)) : null;
        if (blocker != null) {
            waitFor("not clicking " + AutoPuzzleUtil.fmt(next.pos()) + " - " + blocker);
            return;
        }
        if (!AutoPuzzleUtil.gateWorldClick()) {
            return; // gate held this tick back - the lever stays due and nothing is marked clicked/stopped
        }
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
    }

    /** Standing eye to the lever's box, with a little margin under the 4.5 the server enforces. */
    private static final double SPOT_REACH = 4.3;

    private static boolean inReach(Vec3 eye, BlockPos lever) {
        return com.killer560.hub.util.BlockHits.boxDistanceSq(eye, lever) <= REACH_SQ;
    }

    /**
     * Where to stand for a lever: the standable block on the lever floor (relative y 58, standing on it) nearest
     * QUOI's own spot {@code (15, 58, quoiZ)} whose STANDING eye is within {@link #SPOT_REACH} of the lever's box
     * and onto which an etherwarp can aim. Read off the world, so it is the same answer on Hypixel and in the sim.
     */
    private static BlockPos leverSpot(Minecraft client, int[] cr, BlockPos lever, int quoiZ) {
        java.util.List<Object[]> found = new java.util.ArrayList<>();
        for (int rx = 11; rx <= 19; rx++) {
            for (int rz = quoiZ - 3; rz <= quoiZ + 3; rz++) {
                BlockPos floor = PuzzleCoords.real(rx, 58, rz, cr);
                double stand = MazeWalk.standHeight(client.level, floor.getX(), floor.getY() + 1, floor.getZ());
                if (Double.isNaN(stand) || Math.abs(stand - (floor.getY() + 1)) > 1.0e-6) {
                    continue; // he must end on the floor itself: the auto clicks only at that exact height
                }
                Vec3 eye = new Vec3(floor.getX() + 0.5, stand + AutoPuzzleUtil.EYE_STANDING, floor.getZ() + 0.5);
                if (com.killer560.hub.util.BlockHits.boxDistanceSq(eye, lever) > SPOT_REACH * SPOT_REACH) {
                    continue;
                }
                found.add(new Object[]{Math.abs(rx - 15) + Math.abs(rz - quoiZ) * 1.01, floor});
            }
        }
        found.sort((a, b) -> Double.compare((Double) a[0], (Double) b[0]));
        for (Object[] f : found) {
            BlockPos floor = (BlockPos) f[1];
            if (AutoPuzzleUtil.at(client.player, floor)
                    || AutoPuzzleUtil.etherwarpAim(client.level, client.player, floor) != null) {
                return floor;
            }
        }
        return null;
    }

    /** What the auto last said it was waiting for, so a refusal is one INFO line per change, not one a tick. */
    private static String loggedWait = null;

    private static void waitFor(String why) {
        if (!why.equals(loggedWait)) {
            loggedWait = why;
            LOGGER.info("[AutoPuzzles] Water: waiting - {}", why);
        }
    }

    private static void reset(Minecraft client) {
        loggedWait = null;
        REPOSITION.cancel(client);
        AutoReposition.releaseSneak(client);
        CAMERA.release(client.player);
        lastClickTick = Long.MIN_VALUE / 2;
        lastClickMs = 0L;
        atChest = false;
        stoppedThisRoom = false;
        startAreaAttempted = false;
    }
}
