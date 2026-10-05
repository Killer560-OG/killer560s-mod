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
 * it is within reach (eye distance squared &lt;= 30), interact that board cell, at most every 500ms. QUOI re-clicks
 * every 500ms until the board changes; this caps it at 3 attempts per move (safety addition) and never clicks while
 * sneaking or with a screen open.
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
 * <b>Not implemented</b> - "walk out of the room once it is done": this codebase has no verified doorway/exit
 * coordinate for the Tic Tac Toe room (unlike Boulder/Teleport Maze, which both have one in
 * {@code AutoClearUtils.ROOM_OVERRIDES}); inventing one would risk walking into a wall or a void gap, so this was
 * left out rather than guessed - see the mod-wide honesty rule on puzzle coordinates.
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

    private static BlockPos attemptPos = null;
    private static int attempts = 0;
    private static long lastClickMs = 0L;
    private static boolean wasInRoom = false;
    private static boolean roomSpotAttempted = false;
    private static int totalPlaced = 0;
    /** The placement count when the current attempt streak began - see the reset below. */
    private static int attemptPlacedCount = -1;
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
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        if (!roomSpotAttempted && cr != null && McCompat.screen(client) == null) {
            // Pure navigation, tried once per room visit regardless of solver/GUARD state below - same one-shot
            // pattern as Auto Water Board's "start area" walk.
            BlockPos spot = ROOM_SPOT_RELATIVE == null ? null
                    : PuzzleCoords.real(ROOM_SPOT_RELATIVE[0], ROOM_SPOT_RELATIVE[1], ROOM_SPOT_RELATIVE[2], cr);
            if (spot == null || AutoPuzzleUtil.at(client.player, spot)) {
                roomSpotAttempted = true;
            } else if (AutoPuzzleUtil.pathIfMapOn(spot, null)) {
                roomSpotAttempted = true;
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
        // "Finished" is read as: the solver has no move left, and we have placed at least one this visit. The
        // second half matters - the solver also has no move while it cannot read the board at all, on the tick
        // you walk in, and walking out then would leave the puzzle untouched.
        //
        // The destination is not a hand-measured exit. There is no verified doorway coordinate for this room
        // and inventing one walks you into a wall, which is why this was left unbuilt until now;
        // AutoClearUtils.nearestDoorOut asks the live map for the closest door to the room you are in, which
        // works in any room and stands two blocks back from a locked one. Off by default all the same: it is
        // the only auto-puzzle walk whose target nobody has stood on and checked.
        if (best == null && totalPlaced > 0 && !walkOutAttempted && cfg.isTicTacToeWalkOutEnabled()) {
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
        if (now - lastClickMs < CLICK_GAP_MS && !approaching) {
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
        if (now - lastClickMs < CLICK_GAP_MS) {
            return;
        }
        // Reset on a new ROUND as well as a new cell.
        //
        // attempts only reset when the target cell changed, so if a later round's first move happened to be
        // the same cell that had already used up its three attempts, the auto never clicked again. The board
        // state moving on is the signal that this is a different problem, not a retry of the old one.
        if (!best.equals(attemptPos) || totalPlaced != attemptPlacedCount) {
            attemptPos = best;
            attemptPlacedCount = totalPlaced;
            attempts = 0;
        }
        if (attempts >= MAX_ATTEMPTS) {
            waitFor("used all " + MAX_ATTEMPTS + " clicks on " + AutoPuzzleUtil.fmt(best) + " and the board did not change");
            return;
        }
        if (!AutoPuzzleUtil.gateWorldClick()) {
            return; // gate held this tick back - no attempt is burnt and the 500ms gap is untouched
        }
        attempts++;
        lastClickMs = now;
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

    // ------------------------------------------------------------------ chest side trip

    private static void tickChest(Minecraft client, LocalPlayer player, int[] cr) {
        if (McCompat.screen(client) != null) {
            return;
        }
        switch (chestStage) {
            case WALK_TO_CHEST -> {
                if (chestReal == null && !findChest(cr)) {
                    return; // no room identity yet, or gave up (chestStage already moved to DONE)
                }
                if (chestStage != ChestStage.WALK_TO_CHEST) {
                    return; // findChest gave up this tick
                }
                walkChestLeg(player, chestReal, ChestStage.AURA, "the chest");
            }
            case AURA -> auraChest(client, player);
            case WALK_BACK -> {
                BlockPos spot = ROOM_SPOT_RELATIVE == null || cr == null ? null
                        : PuzzleCoords.real(ROOM_SPOT_RELATIVE[0], ROOM_SPOT_RELATIVE[1], ROOM_SPOT_RELATIVE[2], cr);
                walkChestLeg(player, spot, ChestStage.DONE, "the room spot");
            }
            default -> {
            }
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
                LOGGER.warn("[AutoPuzzles] TicTacToe: no chest secret coordinates known for this room - skipping the chest trip");
                ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Auto Tic Tac Toe: "),
                        ModChat.bad("no chest position known"), ModChat.text(" for this room."));
            }
            chestStage = ChestStage.DONE;
            return false;
        }
        RoomEntry.Pos nearest = chests.get(0);
        // PuzzleCoords, not RoomDatabase - the raw call drops the sim's floor shift, so the chest trip walked
        // to Hypixel's height and the aura never had anything in reach.
        chestReal = PuzzleCoords.real(nearest, cr);
        chestLegStartMs = System.currentTimeMillis();
        return true;
    }

    /** Paths issued on the current leg; reset whenever the stage moves on. */
    private static int legPaths = 0;
    /** A leg that has asked for this many paths and is still not there cannot get closer. */
    private static final int MAX_LEG_PATHS = 3;

    private static void advanceChest(ChestStage next, String why) {
        LOGGER.info("[AutoPuzzles] TicTacToe: chest trip {} -> {} ({})", chestStage, next, why);
        chestStage = next;
        chestLegStartMs = System.currentTimeMillis();
        legPaths = 0;
        chestMapOffWarned = false;
    }

    /**
     * One leg of the chest side trip.
     *
     * <p>killer560 (2026-10-04): "It also did the first button for tictactoe but none after that." His log: one
     * click at 18:08:55, then the trip's walk to the chest - two warps that stopped short ("the block itself cannot
     * be reached"), then about seventy "Already there" in four seconds, one more warp, and silence; he played the
     * rest by hand (a Draw at 18:09:19), and the auto never clicked again. The board itself was fine - the click
     * logic simply never ran, because it waits for the trip and the trip could not end: it ended on standing ON the
     * target, on the chest being in reach, or on the 15 s timeout - and that timeout sat BEHIND
     * {@code if (ClearExecutor.isBusy()) return}, so a walk that kept the executor busy kept the trip alive with
     * it, and a walk that ended short re-asked for a path every tick ("Already there" each time) without ever
     * counting as done. Now the timeout is checked first and cancels a walk still running, and a leg that has
     * asked for {@link #MAX_LEG_PATHS} paths and is still not there gives up - it cannot get closer. Each
     * transition is an INFO line. The same on Hypixel: none of this depends on the sim.
     */
    private static void walkChestLeg(LocalPlayer player, BlockPos target, ChestStage nextStage, String label) {
        if (target == null) {
            advanceChest(nextStage, "no target for " + label);
            return;
        }
        if (AutoPuzzleUtil.at(player, target)) {
            advanceChest(nextStage, "standing on " + label);
            return;
        }
        // The chest leg is over once the chest is in aura reach. at() above only matches when standing ON the
        // target block, which for a chest means on its lid, so a walk that stopped beside it waited out the full
        // 15 s timeout before the aura - "walk to the chest timed out" in his 2026-10-04 sim log.
        if (nextStage == ChestStage.AURA
                && com.killer560.hub.util.BlockHits.boxDistanceSq(player.getEyePosition(), target) <= AURA_REACH_SQ) {
            advanceChest(nextStage, "the chest is in reach");
            return;
        }
        if (System.currentTimeMillis() - chestLegStartMs > WALK_TIMEOUT_MS) {
            LOGGER.warn("[AutoPuzzles] TicTacToe: walk to {} timed out - continuing anyway", label);
            if (ClearExecutor.isBusy()) {
                ClearExecutor.cancel();
            }
            advanceChest(nextStage, "timed out");
            return;
        }
        if (ClearExecutor.isBusy()) {
            return;
        }
        if (legPaths >= MAX_LEG_PATHS) {
            advanceChest(nextStage, "cannot get closer to " + label + " after " + legPaths + " path(s)");
            return;
        }
        if (AutoPuzzleUtil.pathIfMapOn(target, null)) {
            legPaths++;
        } else if (!chestMapOffWarned) {
            chestMapOffWarned = true;
            ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Auto Tic Tac Toe needs "), ModChat.value("Interactive Map"),
                    ModChat.text(" on to walk to " + label + "."));
        }
    }

    private static void auraChest(Minecraft client, LocalPlayer player) {
        if (chestAuraAttempts >= MAX_AURA_ATTEMPTS) {
            advanceChest(ChestStage.WALK_BACK, "no chest in reach after " + chestAuraAttempts + " tries");
            return;
        }
        BlockPos target = AutoPuzzleUtil.nearestChest(client, player, AURA_REACH_SQ);
        if (target == null) {
            target = chestReal;
        }
        // To the box, like the picker above - measuring the gate one way and the choice another is how a
        // module ends up clicking at something it cannot reach.
        double distSq = com.killer560.hub.util.BlockHits.boxDistanceSq(player.getEyePosition(), target);
        if (player.isShiftKeyDown() || distSq > AURA_REACH_SQ) {
            chestAuraAttempts++;
            return;
        }
        if (!AutoPuzzleUtil.gateWorldClick()) {
            return;
        }
        chestAuraAttempts++;
        if (!AutoPuzzleUtil.interactBlock(client, target)) {
            LOGGER.warn("[AutoPuzzles] TicTacToe: no clickable shape at {} (attempt {}/{})", target, chestAuraAttempts, MAX_AURA_ATTEMPTS);
            return;
        }
        ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Tic Tac Toe: aura'd the "), ModChat.good("secret chest"),
                ModChat.text("."));
        advanceChest(ChestStage.WALK_BACK, "aura'd the chest");
    }

    private static void reset() {
        stopApproach(Minecraft.getInstance(), "left the room");
        approachFor = null;
        approachTries = 0;
        attemptPos = null;
        attempts = 0;
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
        loggedWait = null;
    }
}
