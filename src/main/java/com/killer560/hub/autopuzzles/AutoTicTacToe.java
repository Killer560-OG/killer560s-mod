package com.killer560.hub.autopuzzles;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.livemap.autoclear.AutoClearUtils;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.autoclear.DungeonMapPathfinder;
import com.killer560.hub.livemap.autoclear.ClearExecutor;
import com.killer560.hub.puzzlesolvers.PuzzleCoords;
import com.killer560.hub.puzzlesolvers.TicTacToeSolverConfig;
import com.killer560.hub.puzzlesolvers.TicTacToeSolverFeature;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.List;
import com.killer560.hub.compat.McCompat;

/**
 * Auto Tic Tac Toe - port of QUOI {@code TicTacToeSolver.kt}'s {@code auto}: whenever the solver has a best move and
 * it is within reach (eye distance squared &lt;= 30), interact that board cell. QUOI re-clicks every 500ms until the
 * board changes; this caps it at 3 attempts per move (safety addition) and never clicks while sneaking or with a screen
 * open. Since 2026-10-15 only a RETRY of the same square waits those 500 ms: a new move goes the tick it is read.
 * <p>
 * killer560, 2026-09-27: "have it pathfind to the room, have it aura the first click for the puzzle, then walk
 * towards that chest and get close enough to aura it as an option as well. Same secret concept. Then have it walk
 * back over and do the tictactoe again."
 * <ul>
 *   <li>"Pathfind to the room": on a fresh visit, walks (Interactive-Map-gated) to {@code AutoClearUtils}'s own
 *   "Tic Tac Toe" room spot (relative 11,68,16 - the same spot "Interactive Map" already uses to walk a player
 *   INTO this room), same one-shot pattern as Auto Water Board's "start area" walk.</li>
 *   <li>"Aura the first click" is just the existing per-move click logic below, unchanged - it already no-rotate
 *   interacts every move, first included.</li>
 *   <li>"Walk towards that chest and get close enough to aura it as an option" ({@link AutoPuzzlesConfig#isTicTacToeAuraChestEnabled()},
 *   default off): after the FIRST successful placement of a fresh room visit, walks to the nearest secret this
 *   room's real database entry ({@link RoomEntry#secretCoords}) knows about, auras it if it's a chest (its own
 *   deliberate interact - never {@code SecretAuraFeature}), then walks back to the room spot above so clicking can
 *   resume ("walk back over and do the tictactoe again" - nothing further needed for that: the click logic below
 *   simply keeps going once the trip is over, for as many rounds as the room actually needs).</li>
 * </ul>
 * <p>2026-10-15 (killer560): it etherwarps onto the block right in front of the board ({@link #boardSpot}) rather than
 * walking, the chest trip is "Grab Secret" - one etherwarp next to the secret, Secret Aura's click, one etherwarp back -
 * and Walk Out When Done waits for the game to be over ({@link TicTacToeSolverFeature#isGameOver}), not for a board on
 * which the computer has yet to reply.
 */
final class AutoTicTacToe {

    private static final Logger LOGGER = ModLog.get("killer560smod-autopuzzles");
    private static final String ROOM = "Tic Tac Toe";
    /** Measured block reach, squared - was 30.0 (5.48 blocks) to the centre. */
    private static final double REACH_SQ = AutoPuzzleUtil.BLOCK_REACH_SQ;
    private static final long CLICK_GAP_MS = 500L;
    private static final int MAX_ATTEMPTS = 3;
    /** The measured block reach, squared - was 36.0 (6.0 blocks) measured to the centre. */
    private static final double AURA_REACH_SQ = AutoPuzzleUtil.BLOCK_REACH_SQ;
    private static final long WALK_TIMEOUT_MS = 15_000L;
    private static final int MAX_AURA_ATTEMPTS = 3;
    /** AutoClearUtils' own "Tic Tac Toe" room spot - the interior standing spot, not a guess. */
    private static final int[] ROOM_SPOT_RELATIVE = AutoClearUtils.roomOverride(ROOM);

    private static final AutoGuard GUARD = new AutoGuard("Auto Tic Tac Toe", "Tic Tac Toe Solver");
    /** Held while one of this auto's own warps turns him (see {@link FreeCam}). */
    private static final FreeCam CAMERA = new FreeCam("TicTacToe");
    /** This auto's own etherwarps: onto the board spot, next to the secret, and back (killer560, 2026-10-15). */
    private static final AutoReposition WARP = new AutoReposition("TicTacToe", CAMERA);
    /** Every board square within this of his standing eye's box distance - a little inside the 4.5 limit. */
    private static final double BOARD_REACH = 4.2;
    /**
     * From where Secret Aura takes the secret chest: ITS rule exactly - his standing eye to the chest's box within his
     * Aura Range (capped at the measured 4.5), with no line-of-sight test (SecretAuraFeature picks by box distance and
     * clicks through walls, as the server allows) - less a hair for where the landing settles. killer560 (2026-10-15):
     * "it should aura the secret".
     */
    private static double chestSpotReach() {
        return Math.min(com.killer560.hub.cheatutils.CheatUtilsConfig.getInstance().getAuraRange(),
                com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_REACH) - 0.05;
    }
    /** Ticks Secret Aura gets to take the chest once he stands in reach of it, before the auto clicks it itself. */
    private static final int AURA_TICKS = 10;

    private static BlockPos attemptPos = null;
    private static int attempts = 0;
    /** The solver's move as last seen, and the square last clicked: a click on the same square again is a retry. */
    private static BlockPos seenBest = null;
    private static BlockPos lastClickedPos = null;
    private static long lastClickMs = 0L;
    private static boolean wasInRoom = false;
    private static boolean roomSpotAttempted = false;
    private static int totalPlaced = 0;
    /** One attempt per room visit, so a board that reads as finished for many ticks queues a single walk. */
    private static boolean walkOutAttempted = false;

    private enum ChestStage { NONE, WALK_TO_CHEST, AURA, WALK_BACK, DONE }

    private static ChestStage chestStage = ChestStage.NONE;
    private static BlockPos chestReal = null;
    private static long chestLegStartMs = 0L;
    private static int chestAuraAttempts = 0;
    private static boolean noChestWarned = false;
    private static boolean chestMapOffWarned = false;

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
        if (approaching || chestStage != ChestStage.NONE && chestStage != ChestStage.DONE) {
            AutoPuzzleActivity.note("Tic Tac Toe");
        }
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        CAMERA.keep(client.player);
        if (WARP.isActive()) {
            WARP.tick(client);
            if (WARP.isActive()) {
                AutoPuzzleActivity.note("Tic Tac Toe");
                return;
            }
            // Landed this tick. Onto the board spot with a move to make: the body turns onto it now, under the camera, and
            // the click goes next tick (the landing's sneak is let go in this one) - no turn tick of the click's own, no
            // 2-tick landing wait. Anywhere else (by the secret, where Secret Aura clicks with its own turn) the camera
            // goes back now, as before: a hold of ours would read to the aura's turn as somebody else turning him.
            boolean onBoard = chestStage == ChestStage.NONE || chestStage == ChestStage.DONE
                    || chestStage == ChestStage.WALK_BACK;
            if (onBoard && best != null && com.killer560.hub.util.BlockHits.boxDistanceSq(new Vec3(client.player.getX(),
                    client.player.getY() + AutoPuzzleUtil.EYE_STANDING, client.player.getZ()), best) <= REACH_SQ) {
                AutoPuzzleUtil.aimBodyAt(client.player, best, CAMERA);
            } else {
                CAMERA.release(client.player);
            }
        }
        if (!roomSpotAttempted && cr != null && McCompat.screen(client) == null && !ClearExecutor.isBusy()) {
            // RIGHT IN FRONT OF THE BOARD (killer560, 2026-10-15: "It should just etherwarp right infront of the board
            // by default"): the standable block, nearest the Interactive Map's spot for this room, from which every
            // square is in reach - one etherwarp of the auto's own when there is a line onto it, else the Interactive
            // Map, asked for that exact block. Tried once per visit, whatever the solver says.
            if (allSquaresInReach(client.player.getEyePosition(), cr)) {
                roomSpotAttempted = true;
            } else {
                BlockPos spot = boardSpot(client, cr);
                if (spot == null || AutoPuzzleUtil.at(client.player, spot)) {
                    roomSpotAttempted = true;
                } else if (AutoPuzzlesConfig.getInstance().isEtherwarpReposition()
                        && AutoPuzzleUtil.etherwarpAim(client.level, client.player, spot) != null
                        && WARP.start(client, spot, false, false, false)) {
                    roomSpotAttempted = true;
                    LOGGER.info("[AutoPuzzles] TicTacToe: etherwarping in front of the board onto {}",
                            AutoPuzzleUtil.fmt(spot));
                    return;
                } else {
                    // Pathing or the Interactive Map off (nothing else refuses with no path running): once only.
                    roomSpotAttempted = true;
                    if (AutoPuzzleUtil.pathIfMapOn(spot, null, true)) {
                        LOGGER.info("[AutoPuzzles] TicTacToe: Interactive Map to {} in front of the board",
                                AutoPuzzleUtil.fmt(spot));
                    }
                }
            }
        }
        if (chestStage != ChestStage.NONE && chestStage != ChestStage.DONE) {
            stopApproach(client, "the chest trip");
            tickChest(client, client.player, cr);
            return; // the click logic below waits its turn until the side trip is over
        }
        if (ClearExecutor.isBusy()) {
            stopApproach(client, "the Interactive Map is moving him");
            return; // walking (room spot, or our own chest trip's ClearExecutor leg)
        }
        // WALK OUT WHEN THE BOARD IS DONE.
        //
        // "Finished" is the game being over (see below), and at least one placed this visit.
        //
        // The destination is not a hand-measured exit. There is no verified doorway coordinate for this room
        // and inventing one walks you into a wall, which is why this was left unbuilt until now;
        // AutoClearUtils.nearestDoorOut asks the live map for the closest door to the room you are in, which
        // works in any room and stands two blocks back from a locked one. Off by default all the same: it is
        // the only auto-puzzle walk whose target nobody has stood on and checked.
        // "Done" is the GAME being over - the solver saw a line of three or a full board, or the tab list reads the
        // puzzle solved or failed. It used to be "no move for him and one placed", which is also the board between
        // his click and the computer's reply: on Hypixel the reply takes a second or two, and his log of 2026-10-14
        // 17:26 shows the walk-out firing right after the FIRST click, two warps out of the room, then the auto
        // walking him back in to carry on (killer560: "it etherwapred out, then walked back into the room").
        char tab = com.killer560.hub.scorecalc.ScoreCalculatorFeature.puzzleState(ROOM);
        boolean gameOver = TicTacToeSolverFeature.isGameOver() || tab == '✔' || tab == '✖';
        if (gameOver && totalPlaced > 0 && !walkOutAttempted && cfg.isTicTacToeWalkOutEnabled()) {
            DungeonLayout layout = DungeonLayout.current();
            int door = layout == null ? -1 : AutoClearUtils.nearestDoorOut(layout);
            if (door >= 0) {
                BlockPos exit = DungeonMapPathfinder.getDoorPos(layout, layout.currentRoom(), door);
                if (exit != null && AutoPuzzleUtil.pathIfMapOn(exit, null)) {
                    walkOutAttempted = true;
                }
            } else if (layout != null) {
                // Said once, not every tick: no layout means the map has nothing to path with.
                walkOutAttempted = true;
                LOGGER.info("[AutoPuzzles] TicTacToe: board done but the map has no door to walk out to");
            }
        }
        if (approaching && best == null) {
            stopApproach(client, "no move left");
        }
        if (!GUARD.solverOn(TicTacToeSolverConfig.getInstance().isEnabled()) || best == null || !GUARD.fresh()) {
            return;
        }
        LocalPlayer player = client.player;
        long now = System.currentTimeMillis();
        if (McCompat.screen(client) != null || player.isShiftKeyDown()) {
            if (approaching) {
                stopApproach(client, McCompat.screen(client) != null ? "a screen opened" : "sneaking");
            }
            return;
        }
        if (!java.util.Objects.equals(best, seenBest)) {
            // A new move (the computer replied, or a new round): a fresh problem - no retry gap, a fresh set of attempts.
            seenBest = best;
            lastClickedPos = null;
            attemptPos = best;
            attempts = 0;
        }
        // CLICK_GAP_MS paces a RETRY of the same square only (QUOI re-clicks every 500 ms until the board changes). A new
        // move goes the tick it is read: main held every move 500 ms after the last click, which on Hypixel (a reply a
        // second or two later) was mostly hidden and in the sim (a reply 5 ticks later) never was.
        boolean retry = best.equals(lastClickedPos);
        if (retry && now - lastClickMs < CLICK_GAP_MS && !approaching) {
            return;
        }
        // To the BOX, matching the limit - see the note in AutoWater.
        if (com.killer560.hub.util.BlockHits.boxDistanceSq(player.getEyePosition(), best) > REACH_SQ) {
            if (approach(client, player, best)) {
                return; // walking closer
            }
            waitFor("next move " + AutoPuzzleUtil.fmt(best) + " is out of reach from "
                    + AutoPuzzleUtil.fmt(player.blockPosition()) + String.format(java.util.Locale.US, " (%.2f blocks)",
                    Math.sqrt(com.killer560.hub.util.BlockHits.boxDistanceSq(player.getEyePosition(), best))));
            return;
        }
        if (approaching) {
            stopApproach(client, "the move at " + AutoPuzzleUtil.fmt(best) + " is in reach");
        }
        if (retry && now - lastClickMs < CLICK_GAP_MS) {
            return;
        }
        // Attempts reset on a new ROUND as well as a new cell: the move changing at all (above, through "no move" while
        // the computer replies) is the signal that this is a different problem, not a retry of the old one. It used to
        // compare totalPlaced, which every click moves, so a retry always looked new and MAX_ATTEMPTS never applied.
        if (attempts >= MAX_ATTEMPTS) {
            waitFor("used all " + MAX_ATTEMPTS + " clicks on " + AutoPuzzleUtil.fmt(best) + " and the board did not change");
            return;
        }
        if (!AutoPuzzleUtil.gateWorldClick(client, best)) {
            return; // gate held this tick back (or the body was turned to it) - nothing burnt, gap untouched
        }
        attempts++;
        lastClickMs = now;
        lastClickedPos = best;
        if (!AutoPuzzleUtil.interactBlock(client, best)) {
            LOGGER.warn("[AutoPuzzles] TicTacToe: no clickable shape at {} (state={})", best, client.level.getBlockState(best));
            attempts = MAX_ATTEMPTS;
            return;
        }
        totalPlaced++;
        LOGGER.info("[AutoPuzzles] TicTacToe: clicked {} ({}) holding {}, attempt {}/{}", best,
                client.level.getBlockState(best),
                player.getMainHandItem().getHoverName().getString(), attempts, MAX_ATTEMPTS);
        if (totalPlaced == 1 && cfg.isTicTacToeAuraChestEnabled()) {
            advanceChest(ChestStage.WALK_TO_CHEST, "first move placed");
            chestAuraAttempts = 0;
            // In this same tick: the warp toward the secret turns him and puts sneak down now, after the click, and its use
            // goes next tick (the click's own tick reports the new look, so the use is not read as the click's tail).
            tickChest(client, player, cr);
        }
        if (!WARP.isActive()) {
            CAMERA.release(player); // a body readied for this click under the camera goes back under his view
        }
    }

    // ------------------------------------------------------------------ walking into reach

    /**
     * Walking closer to a move that is out of reach.
     *
     * <p>The Interactive Map's spot for this room (relative 11,68,16) is where a walk INTO the room ends, not a
     * spot from which the whole board is in reach: on the 93-solve run of 2026-10-04 the last move was ~5.5 blocks
     * from there and the auto only ever said "out of reach" (Tic Tac Toe flaky, 1 of 4). So when the next move is
     * out of reach this picks the nearest spot on his own floor whose eye is within {@link #APPROACH_EYE} of the
     * button's box, plans a walk round anything in the way ({@link MazeWalk}) and walks it with the camera and
     * the forward key only - one discrete key, nothing written to position or velocity. Etherwarp is not used:
     * the board is a few steps away and an AOTV right-click on the board would press a button. Identical on
     * Hypixel, where the room and the Interactive Map spot are the same.
     */
    private static final MazeWalk APPROACH = new MazeWalk();
    /** A little inside the 4.5 the server enforces, so the spot is still in reach after the walk's overshoot. */
    private static final double APPROACH_EYE = 4.0;
    private static final int MAX_APPROACHES = 3;
    private static boolean approaching = false;
    private static long approachStartMs = 0L;
    private static long approachTimeoutMs = 0L;
    private static BlockPos approachFor = null;
    private static int approachTries = 0;

    /** @return true while a walk towards {@code target} is running (the caller waits) */
    private static boolean approach(Minecraft client, LocalPlayer player, BlockPos target) {
        if (!target.equals(approachFor)) {
            if (approaching) {
                stopApproach(client, "the move changed");
            }
            approachFor = target;
            approachTries = 0;
        }
        if (approaching) {
            if (System.currentTimeMillis() - approachStartMs > approachTimeoutMs) {
                stopApproach(client, "timed out after " + approachTimeoutMs + " ms");
                return false;
            }
            if (APPROACH.tick(client)) {
                return true;
            }
            stopApproach(client, "walk finished");
            return false;
        }
        if (!AutoPuzzlesConfig.getInstance().isAutoPuzzlePathingEnabled()) {
            waitFor("move " + AutoPuzzleUtil.fmt(target) + " is out of reach and auto-puzzle pathing is off");
            return false;
        }
        if (approachTries >= MAX_APPROACHES || !player.onGround()) {
            return false;
        }
        approachTries++;
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        BlockPos front = cr == null ? null : boardSpot(client, cr);
        if (approachTries == 1 && front != null && !AutoPuzzleUtil.at(player, front)
                && AutoPuzzlesConfig.getInstance().isEtherwarpReposition()
                && WARP.start(client, front, false, false, false)) {
            LOGGER.info("[AutoPuzzles] TicTacToe: move {} is out of reach - etherwarping in front of the board onto {}",
                    AutoPuzzleUtil.fmt(target), AutoPuzzleUtil.fmt(front));
            return true;
        }
        BlockPos spot = MazeWalk.planToSpot(client.level, player.position(), target, 6,
                eye -> com.killer560.hub.util.BlockHits.boxDistanceSq(eye, target) <= APPROACH_EYE * APPROACH_EYE,
                APPROACH);
        if (spot == null) {
            LOGGER.info("[AutoPuzzles] TicTacToe: move {} is out of reach and no walkable spot within {} blocks of it"
                    + " is in reach (try {}/{})", AutoPuzzleUtil.fmt(target), APPROACH_EYE, approachTries,
                    MAX_APPROACHES);
            return false;
        }
        approaching = true;
        approachStartMs = System.currentTimeMillis();
        approachTimeoutMs = 2000L + (long) (APPROACH.length(player.position()) / 3.5 * 1000.0);
        LOGGER.info("[AutoPuzzles] TicTacToe: move {} is out of reach - walking to {} ({} leg(s), {} blocks, try {}/{})",
                AutoPuzzleUtil.fmt(target), AutoPuzzleUtil.fmt(spot), APPROACH.legs(),
                String.format(java.util.Locale.US, "%.1f", APPROACH.length(player.position())), approachTries,
                MAX_APPROACHES);
        return APPROACH.tick(client);
    }

    private static void stopApproach(Minecraft client, String why) {
        if (!approaching) {
            return;
        }
        approaching = false;
        client.options.keyUp.setDown(false);
        LOGGER.info("[AutoPuzzles] TicTacToe: stopped walking - {}", why);
    }

    /** What the auto last said it was waiting for, so a reason is logged once per change. */
    private static String loggedWait = null;

    private static void waitFor(String why) {
        if (!why.equals(loggedWait)) {
            loggedWait = why;
            LOGGER.info("[AutoPuzzles] TicTacToe: waiting - {}", why);
        }
    }

    // ------------------------------------------------------------------ the board spot

    /** Every board square within {@link #BOARD_REACH} of {@code eye}. */
    private static boolean allSquaresInReach(Vec3 eye, int[] cr) {
        for (int i = 0; i < 9; i++) {
            if (com.killer560.hub.util.BlockHits.boxDistanceSq(eye, TicTacToeSolverFeature.cellPos(i, cr))
                    > BOARD_REACH * BOARD_REACH) {
                return false;
            }
        }
        return true;
    }

    /**
     * The block in front of the board to stand on: standable, etherwarpable, every square within
     * {@link #BOARD_REACH} of both his standing and sneaking eye, nearest the Interactive Map's own spot for the room
     * (relative 11, 68, 16 - on Hypixel that spot itself qualifies). One he can warp onto from here first. Null when no
     * block qualifies.
     */
    private static BlockPos boardSpot(Minecraft client, int[] cr) {
        List<BlockPos> squares = new java.util.ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            squares.add(TicTacToeSolverFeature.cellPos(i, cr));
        }
        Vec3 prefer = ROOM_SPOT_RELATIVE == null ? null : Vec3.atCenterOf(PuzzleCoords.real(ROOM_SPOT_RELATIVE[0],
                ROOM_SPOT_RELATIVE[1], ROOM_SPOT_RELATIVE[2], cr));
        List<BlockPos> spots = AutoPuzzleUtil.reachSpots(client, squares, BOARD_REACH, false, prefer);
        for (BlockPos b : spots) {
            if (AutoPuzzleUtil.at(client.player, b) || AutoPuzzleUtil.etherwarpAim(client.level, client.player, b) != null) {
                return b;
            }
        }
        return spots.isEmpty() ? null : spots.get(0);
    }

    // ------------------------------------------------------------------ grab the secret (chest side trip)
    //
    // killer560 (2026-10-15): "a grab secret option where it etherwarps as close to the secret as it can for secret aura
    // to grab it. It should do this right after the first click on the tictactoe board if the option is on." The old trip
    // asked the Interactive Map for the CHEST block itself, which is no block to land on - his log of 2026-10-14 17:26:00
    // has "is not etherwarpable ... Nothing searched" three times, then "no chest in reach", and the trip ended without
    // the secret. Now: one etherwarp onto the nearest block that has the chest in reach and in sight, Secret Aura's
    // click (or this auto's own when the aura will not take it), and one etherwarp back in front of the board.

    private static int auraTicks = 0;
    /** Walking the last blocks to the secret (no warp line in reach of it). */
    private static boolean chestWalking = false;
    private static boolean walkTried = false;
    /** Two-warp detours taken on this leg (see {@link AutoPuzzleUtil#hopToward}). */
    private static int hops = 0;
    private static final java.util.Set<BlockPos> chestSpotsTried = new java.util.HashSet<>();
    /** {@code util/ChestWindows.count} as the chest stage began: a chest window in the AURA stage is the secret's, shown
     *  or not (Auto Close Chest cancels the screen, so the window check below never saw it). */
    private static int chestStageWindows = 0;

    private static void tickChest(Minecraft client, LocalPlayer player, int[] cr) {
        AutoPuzzleActivity.note("Tic Tac Toe");
        if (McCompat.screen(client) != null) {
            if (chestStage == ChestStage.AURA) {
                advanceChest(ChestStage.WALK_BACK, "the chest's window opened");
            }
            return;
        }
        if (chestStage == ChestStage.AURA && com.killer560.hub.util.ChestWindows.since(chestStageWindows)) {
            advanceChest(ChestStage.WALK_BACK, "the chest's window arrived (not shown)");
            tickChest(client, player, cr); // the warp back starts this tick, as for an open lid
            return;
        }
        if (System.currentTimeMillis() - chestLegStartMs > WALK_TIMEOUT_MS) {
            LOGGER.warn("[AutoPuzzles] TicTacToe: grab secret {} timed out - carrying on", chestStage);
            if (ClearExecutor.isBusy()) {
                ClearExecutor.cancel();
            }
            advanceChest(chestStage == ChestStage.WALK_BACK ? ChestStage.DONE : ChestStage.WALK_BACK, "timed out");
            return;
        }
        switch (chestStage) {
            case WALK_TO_CHEST -> {
                if (chestReal == null && !findChest(cr)) {
                    return;
                }
                if (chestStage != ChestStage.WALK_TO_CHEST) {
                    return;
                }
                if (chestWalking) {
                    if (com.killer560.hub.util.BlockHits.boxDistanceSq(player.getEyePosition(), chestReal)
                            > chestSpotReach() * chestSpotReach() && APPROACH.tick(client)) {
                        return;
                    }
                    chestWalking = false;
                    client.options.keyUp.setDown(false);
                }
                if (com.killer560.hub.util.BlockHits.boxDistanceSq(player.getEyePosition(), chestReal)
                        <= chestSpotReach() * chestSpotReach() && player.onGround()) {
                    auraTicks = 0;
                    advanceChest(ChestStage.AURA, "the secret is in reach");
                    return;
                }
                warpNear(client, player, chestReal, "the secret");
            }
            case AURA -> auraChest(client, player);
            case WALK_BACK -> {
                BlockPos spot = cr == null ? null : boardSpot(client, cr);
                if (spot == null || (cr != null && allSquaresInReach(player.getEyePosition(), cr))) {
                    advanceChest(ChestStage.DONE, "back in front of the board");
                    return;
                }
                if (AutoPuzzleUtil.at(player, spot)) {
                    advanceChest(ChestStage.DONE, "on the board spot");
                    return;
                }
                if (ClearExecutor.isBusy()) {
                    return;
                }
                if (AutoPuzzleUtil.etherwarpAim(client.level, player, spot) != null
                        && WARP.start(client, spot, false, false, false)) {
                    LOGGER.info("[AutoPuzzles] TicTacToe: etherwarping back in front of the board onto {}",
                            AutoPuzzleUtil.fmt(spot));
                    return;
                }
                if (legPaths < MAX_LEG_PATHS && AutoPuzzleUtil.pathIfMapOn(spot, null, true)) {
                    legPaths++;
                } else if (legPaths >= MAX_LEG_PATHS) {
                    advanceChest(ChestStage.DONE, "cannot get back to the board spot");
                }
            }
            default -> {
            }
        }
    }

    /** One etherwarp onto the best block that has {@code target} in reach and in sight; the Interactive Map when none
     *  has a line from here. */
    private static void warpNear(Minecraft client, LocalPlayer player, BlockPos target, String label) {
        if (ClearExecutor.isBusy()) {
            return;
        }
        // Every block his STANDING eye has it within Secret Aura's reach from, walls or not, nearest the chest first.
        List<BlockPos> spots = AutoPuzzleUtil.reachSpots(client, List.of(target), chestSpotReach(), false,
                Vec3.atCenterOf(target), true);
        if (spots.isEmpty()) {
            advanceChest(ChestStage.WALK_BACK, "no block to stand on has " + label + " in reach");
            return;
        }
        for (BlockPos spot : spots) {
            if (chestSpotsTried.contains(spot) || AutoPuzzleUtil.etherwarpAim(client.level, player, spot) == null) {
                continue;
            }
            chestSpotsTried.add(spot);
            if (WARP.start(client, spot, false, false, false)) {
                LOGGER.info("[AutoPuzzles] TicTacToe: etherwarping onto {} for {} at {} ({} spot(s) in reach of it)",
                        AutoPuzzleUtil.fmt(spot), label, AutoPuzzleUtil.fmt(target), spots.size());
                return;
            }
        }
        // No warp line onto a spot in reach of it (a covered alcove): the last few blocks on foot, on his own floor,
        // before any detour - as close as a warp gets is where he already stands.
        if (!walkTried && player.onGround()) {
            BlockPos spot = MazeWalk.planToSpot(client.level, player.position(), target, 8,
                    eye -> com.killer560.hub.util.BlockHits.boxDistanceSq(eye, target)
                            <= chestSpotReach() * chestSpotReach(), APPROACH);
            walkTried = true;
            if (spot == null) {
                LOGGER.info("[AutoPuzzles] TicTacToe: no walk on his floor to a spot in reach of {}", label);
            }
            if (spot != null) {
                chestWalking = true;
                LOGGER.info("[AutoPuzzles] TicTacToe: no warp line in reach of {} - walking the last {} block(s) to {}",
                        label, String.format(java.util.Locale.ROOT, "%.1f", APPROACH.length(player.position())),
                        AutoPuzzleUtil.fmt(spot));
                APPROACH.tick(client);
                return;
            }
        }
        // No line onto any of them from here (a covered alcove): one warp onto a block that has a line onto one.
        if (hops < 2) {
            BlockPos hop = AutoPuzzleUtil.hopToward(client, player, spots);
            if (hop != null && !AutoPuzzleUtil.at(player, hop) && WARP.start(client, hop, false, false, false)) {
                hops++;
                LOGGER.info("[AutoPuzzles] TicTacToe: no warp line onto a spot by {} - warping to {} first, which has one",
                        label, AutoPuzzleUtil.fmt(hop));
                return;
            }
        }
        if (legPaths >= MAX_LEG_PATHS) {
            // Name the cause once: what stands over the secret and where the spots in reach of it are.
            StringBuilder around = new StringBuilder();
            for (int dy = 1; dy <= 4; dy++) {
                around.append(" y+").append(dy).append('=').append(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(client.level.getBlockState(target.above(dy)).getBlock()).getPath());
            }
            LOGGER.info("[AutoPuzzles] TicTacToe: no way into reach of {} at {}:{}; {} spot(s) in reach, nearest {}",
                    label, AutoPuzzleUtil.fmt(target), around, spots.size(),
                    spots.subList(0, Math.min(3, spots.size())).stream().map(AutoPuzzleUtil::fmt).toList());
            advanceChest(ChestStage.WALK_BACK, "cannot get within reach of " + label + " after " + legPaths + " path(s)");
            return;
        }
        if (AutoPuzzleUtil.pathIfMapOn(spots.get(0), null, true)) {
            legPaths++;
        } else if (!chestMapOffWarned) {
            chestMapOffWarned = true;
            ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Auto Tic Tac Toe has no warp line to " + label
                    + " and needs "), ModChat.value("Interactive Map"), ModChat.text(" on to walk there."));
        }
    }

    /** @return false if it isn't resolved yet and the caller should just retry next tick. */
    private static boolean findChest(int[] cr) {
        RoomEntry entry = LiveMapFeature.currentRoomEntry();
        if (entry == null || cr == null) {
            return false;
        }
        List<RoomEntry.Pos> chests = entry.secretCoords == null ? null : entry.secretCoords.chest;
        if (chests == null || chests.isEmpty()) {
            if (!noChestWarned) {
                noChestWarned = true;
                LOGGER.warn("[AutoPuzzles] TicTacToe: no chest secret coordinates known for this room - skipping the secret");
                ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Auto Tic Tac Toe: "),
                        ModChat.bad("no secret position known"), ModChat.text(" for this room."));
            }
            chestStage = ChestStage.DONE;
            return false;
        }
        // PuzzleCoords, not RoomDatabase - the raw call drops the sim's floor shift.
        chestReal = PuzzleCoords.real(chests.get(0), cr);
        LOGGER.info("[AutoPuzzles] TicTacToe: secret chest at {}", AutoPuzzleUtil.fmt(chestReal));
        return true;
    }

    /** Paths issued on the current leg; reset whenever the stage moves on. */
    private static int legPaths = 0;
    /** A leg that has asked for this many paths and is still not there cannot get closer. */
    private static final int MAX_LEG_PATHS = 3;

    private static void advanceChest(ChestStage next, String why) {
        LOGGER.info("[AutoPuzzles] TicTacToe: grab secret {} -> {} ({})", chestStage, next, why);
        chestStage = next;
        chestStageWindows = com.killer560.hub.util.ChestWindows.count();
        chestLegStartMs = System.currentTimeMillis();
        legPaths = 0;
        hops = 0;
        walkTried = false;
        if (chestWalking) {
            chestWalking = false;
            Minecraft.getInstance().options.keyUp.setDown(false);
        }
        chestMapOffWarned = false;
        chestSpotsTried.clear();
    }

    private static boolean chestTaken(Minecraft client) {
        if (chestReal == null) {
            return false;
        }
        if (com.killer560.hub.cheatutils.SecretAuraFeature.isDone(chestReal)) {
            return true;
        }
        var be = client.level.getBlockEntity(chestReal);
        return be instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest && chest.getOpenNess(0f) > 0f;
    }

    /** Secret Aura takes it when it will; otherwise (or when it has not in {@link #AURA_TICKS}) the auto clicks it. */
    private static void auraChest(Minecraft client, LocalPlayer player) {
        if (chestTaken(client)) {
            advanceChest(ChestStage.WALK_BACK, "the chest is open");
            tickChest(client, player, LiveMapFeature.currentRoomClayAndRotation()); // the warp back starts this tick
            return;
        }
        double dist = Math.sqrt(com.killer560.hub.util.BlockHits.boxDistanceSq(player.getEyePosition(), chestReal));
        boolean aura = com.killer560.hub.cheatutils.SecretAuraFeature.chestRefusal(client, dist) == null;
        if (aura && auraTicks++ < AURA_TICKS) {
            return; // Secret Aura clicks a chest in reach on its next tick
        }
        if (chestAuraAttempts >= MAX_AURA_ATTEMPTS) {
            advanceChest(ChestStage.WALK_BACK, "the chest did not open after " + chestAuraAttempts + " click(s)");
            return;
        }
        if (player.isShiftKeyDown() || dist > Math.sqrt(AURA_REACH_SQ)) {
            AutoReposition.releaseSneak(client);
            return;
        }
        if (!AutoPuzzleUtil.gateWorldClick(client, chestReal)) {
            return;
        }
        chestAuraAttempts++;
        auraTicks = 0;
        if (!AutoPuzzleUtil.interactBlock(client, chestReal)) {
            LOGGER.warn("[AutoPuzzles] TicTacToe: no clickable shape at {}", chestReal);
            advanceChest(ChestStage.WALK_BACK, "no clickable chest");
            return;
        }
        LOGGER.info("[AutoPuzzles] TicTacToe: clicked the secret chest at {} ({} from his eye{})",
                AutoPuzzleUtil.fmt(chestReal), String.format(java.util.Locale.ROOT, "%.2f", dist),
                aura ? ", Secret Aura had not" : ", Secret Aura will not take it");
    }

    private static void reset() {
        stopApproach(Minecraft.getInstance(), "left the room");
        approachFor = null;
        approachTries = 0;
        attemptPos = null;
        attempts = 0;
        seenBest = null;
        lastClickedPos = null;
        lastClickMs = 0L;
        roomSpotAttempted = false;
        totalPlaced = 0;
        walkOutAttempted = false;
        chestStage = ChestStage.NONE;
        chestReal = null;
        chestLegStartMs = 0L;
        chestAuraAttempts = 0;
        noChestWarned = false;
        chestMapOffWarned = false;
        legPaths = 0;
        auraTicks = 0;
        chestSpotsTried.clear();
        WARP.cancel(Minecraft.getInstance());
        CAMERA.release(Minecraft.getInstance().player);
        loggedWait = null;
    }
}
