package com.killer560.hub.autopuzzles;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.livemap.autoclear.AutoClearUtils;
import com.killer560.hub.livemap.autoclear.ClearExecutor;
import com.killer560.hub.puzzlesolvers.BoulderSolverConfig;
import com.killer560.hub.puzzlesolvers.BoulderSolverFeature;
import com.killer560.hub.puzzlesolvers.PuzzleCoords;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.compat.McCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto Boulder: push the boxes the Boulder Solver names, then walk to the reward chest and aura it.
 *
 * <p>History. killer560 (2026-09-27) had it redone as "pathfind to above the chest, then aura the chest", with no box
 * pushing. That cannot work in the room as it is built: the chest sits in the alcove past the far edge of the box
 * grid, under a barrier ceiling, and the only way to it is across the floor once the boxes are out of the way - which
 * is the puzzle. The 2026-10-04 93-solve run showed it: the "standing spot" (chest + 3 up, 3 back) is the air over
 * the barrier roof, "not etherwarpable", and etherwarp is refused inside Boulder anyway, so the Interactive Map walk
 * failed and the auto stopped with the boxes untouched. So now:
 * <ol>
 *   <li>Find the chest: the room database's chest secret if it lists one, otherwise the chest BLOCK found by scanning
 *   the room ({@link AutoPuzzleUtil#chestsInRoom}) - the database has no Boulder chest on Hypixel either.</li>
 *   <li>If he is up on the roof (the doorway is at the roof's height, relative y 69), walk to the edge of one of the
 *   holes in it in front of the grid and step in - down to the floor (relative feet y 64).</li>
 *   <li>Push: for each of {@link BoulderSolverFeature}'s remaining clicks, walk into reach of that button
 *   ({@link MazeWalk} round the boxes) and press it with a no-rotate interact. The solver drops a step when it is
 *   clicked (its own useItemOn hook), and the next button only exists once the box before it has moved, so a step is
 *   pressed only once its button is in the world.</li>
 *   <li>Walk into reach of the chest across the now-open floor and aura it - its own deliberate
 *   {@link AutoPuzzleUtil#interactBlock}, never {@code SecretAuraFeature}, so it works with Secret Aura off.</li>
 *   <li>Ask the Interactive Map to walk back to the doorway spot, once; where etherwarp is refused that simply logs.</li>
 * </ol>
 * Every walk is the camera turned towards the next point and the forward key held - one discrete key, nothing
 * written to position or velocity. Every stop and every refusal is an INFO line. Identical on Hypixel: the room, the
 * solver and the chest scan are the same, only the chest's real position there is still unverified.
 */
final class AutoBoulder {

    private static final Logger LOGGER = ModLog.get("killer560smod-autopuzzles");
    private static final String ROOM = "Boulder";
    /** The measured block reach, squared. */
    private static final double AURA_REACH_SQ = AutoPuzzleUtil.BLOCK_REACH_SQ;
    /** Where a walk aims to end: eye within this of the target's box, a little inside the 4.5 limit. */
    private static final double WALK_EYE = 4.0;
    private static final int MAX_AURA_ATTEMPTS = 3;
    private static final long PRESS_GAP_MS = 350L;
    /** A step's button must turn up within this after the press before it; otherwise the auto stops. */
    private static final long BUTTON_WAIT_MS = 3000L;
    /** How long to wait for the solver to read the arrangement before going for the chest anyway. */
    private static final long SOLVER_WAIT_MS = 4000L;
    private static final int MAX_WALKS_PER_TARGET = 3;

    private enum Stage { NEED_CHEST, DESCEND, STEP_IN, PUSH, TO_CHEST, AURA, WALK_TO_EXIT, DONE }

    private static Stage stage = Stage.NEED_CHEST;
    private static BlockPos chestReal = null;
    private static BlockPos exitReal = null;
    private static int floorY = Integer.MIN_VALUE;
    /** Relative (15, 64, 7): open floor in front of the grid's near row, which a hole's landing must walk to. */
    private static BlockPos gridFront = null;
    private static int auraAttempts = 0;
    private static long stageStartMs = 0L;
    private static long lastPressMs = 0L;
    private static int presses = 0;
    private static boolean noChestWarned = false;
    private static boolean wasInRoom = false;
    private static int scanCooldown = 0;
    private static String loggedWait = null;

    private static final MazeWalk WALK = new MazeWalk();
    private static boolean walking = false;
    private static long walkStartMs = 0L;
    private static long walkTimeoutMs = 0L;
    private static BlockPos walkFor = null;
    private static int walksForTarget = 0;
    /** The hole in the roof he steps into, and the roof block beside it he walks to first. */
    private static BlockPos holeFeet = null;
    private static BlockPos holeEdge = null;

    /** How often the room is scanned for its chest while none has been found, in client ticks. */
    private static final int SCAN_EVERY_TICKS = 20;
    /** The room-relative box scanned: a 1x1 room's 31 blocks plus one either side, and a band around its floor
     *  (the boxes stand on relative y 64..66 and the far alcove is at 66). Heights are relative, so PuzzleCoords
     *  shifts them in the sim. */
    private static final int SCAN_MIN_REL = -1;
    private static final int SCAN_MAX_REL = 31;
    private static final int SCAN_MIN_Y = 60;
    private static final int SCAN_MAX_Y = 75;

    private AutoBoulder() {
    }

    static void levelChanged() {
        reset();
        doneChest = null;
    }

    /** The chest already aura'd in this world, so walking out and back in does not run the room again. */
    private static BlockPos doneChest = null;

    static void tick(Minecraft client, String roomName) {
        AutoPuzzlesConfig cfg = AutoPuzzlesConfig.getInstance();
        if (!cfg.isAutoBoulderEnabled() || !ROOM.equals(roomName)) {
            if (wasInRoom) {
                reset();
            }
            wasInRoom = false;
            return;
        }
        wasInRoom = true;
        LocalPlayer player = client.player;
        if (stage == Stage.DONE) {
            return;
        }
        if (McCompat.screen(client) != null) {
            stopWalk(client, "a screen opened");
            return;
        }
        if (ClearExecutor.isBusy() && stage != Stage.WALK_TO_EXIT) {
            stopWalk(client, "the Interactive Map is moving him");
            waitFor("the Interactive Map is moving him");
            return;
        }
        switch (stage) {
            case NEED_CHEST -> findChest(client);
            case DESCEND -> descend(client, player);
            case STEP_IN -> stepIn(client, player);
            case PUSH -> push(client, player);
            case TO_CHEST -> {
                if (walkInto(client, player, chestReal, "the reward chest")) {
                    advance(Stage.AURA, "the chest is in reach");
                }
            }
            case AURA -> aura(client, player);
            case WALK_TO_EXIT -> walkToExit(player);
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ the chest

    private static void findChest(Minecraft client) {
        RoomEntry entry = LiveMapFeature.currentRoomEntry();
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        if (entry == null || cr == null) {
            waitFor("the live map has no rotation for the room yet");
            return;
        }
        RoomEntry.Pos chestRel = null;
        String source = null;
        var chests = entry.secretCoords == null ? null : entry.secretCoords.chest;
        if (chests != null && !chests.isEmpty()) {
            // Back-most known chest (largest relative z) - the doorway is at negative z.
            chestRel = chests.get(0);
            for (RoomEntry.Pos p : chests) {
                if (p.z > chestRel.z) {
                    chestRel = p;
                }
            }
            source = "room database";
        } else {
            if (scanCooldown > 0) {
                scanCooldown--;
                return;
            }
            scanCooldown = SCAN_EVERY_TICKS;
            BlockPos best = null;
            BlockPos bestRel = null;
            for (BlockPos real : AutoPuzzleUtil.chestsInRoom(client.level, cr, SCAN_MIN_REL, SCAN_MIN_Y, SCAN_MIN_REL,
                    SCAN_MAX_REL, SCAN_MAX_Y, SCAN_MAX_REL)) {
                BlockPos rel = PuzzleCoords.relative(real, cr);
                if (bestRel == null || rel.getZ() > bestRel.getZ()) {
                    best = real;
                    bestRel = rel;
                }
            }
            if (best == null) {
                if (!noChestWarned) {
                    noChestWarned = true;
                    LOGGER.info("[AutoPuzzles] Boulder: no chest secret in the room database and no chest block in "
                            + "the room yet - looking again every {} ticks", SCAN_EVERY_TICKS);
                }
                return;
            }
            chestRel = new RoomEntry.Pos();
            chestRel.x = bestRel.getX();
            chestRel.y = bestRel.getY();
            chestRel.z = bestRel.getZ();
            source = "chest block found in the room";
        }
        chestReal = PuzzleCoords.real(chestRel, cr);
        if (chestReal.equals(doneChest)) {
            advance(Stage.DONE, "this room's chest was already aura'd");
            return;
        }
        // The floor the boxes stand on: relative feet y 64 (the boxes are y 64..66), through PuzzleCoords so it
        // carries the sim's shift. Never a bare height.
        floorY = PuzzleCoords.real(15, 64, 15, cr).getY();
        gridFront = PuzzleCoords.real(15, 64, 7, cr);
        int[] exitRel = AutoClearUtils.roomOverride(ROOM);
        exitReal = exitRel == null ? null : PuzzleCoords.real(exitRel[0], exitRel[1], exitRel[2], cr);
        LOGGER.info("[AutoPuzzles] Boulder: chest at {} (relative {}, {}, {}) from the {}; floor feet y {}",
                AutoPuzzleUtil.fmt(chestReal), chestRel.x, chestRel.y, chestRel.z, source, floorY);
        if (BlockHitsReach.inReach(client.player, chestReal)) {
            advance(Stage.AURA, "the chest is already in reach");
            return;
        }
        advance(onFloor(client.player) ? Stage.PUSH : Stage.DESCEND, onFloor(client.player)
                ? "on the floor" : String.format(java.util.Locale.US, "feet at y %.2f, above the floor", client.player.getY()));
    }

    private static boolean onFloor(LocalPlayer player) {
        return player.onGround() && player.getY() < floorY + 1.5;
    }

    // ------------------------------------------------------------------ getting down off the roof

    /**
     * Walks to the edge of a hole in the roof whose fall lands on the floor, then steps in. The hole is found in the
     * world, not from a table: an air column at his own level whose first solid block below puts his feet on the
     * floor height, next to a roof block he can walk to.
     */
    private static void descend(Minecraft client, LocalPlayer player) {
        if (onFloor(player)) {
            stopWalk(client, "on the floor");
            advance(Stage.PUSH, "on the floor");
            return;
        }
        if (!walking) {
            if (!player.onGround()) {
                return;
            }
            if (holeEdge != null && AutoPuzzleUtil.at(player, holeEdge.below())) {
                advance(Stage.STEP_IN, "at the hole's edge");
                return;
            }
            if (walksForTarget >= MAX_WALKS_PER_TARGET) {
                giveUp("could not get down off the roof after " + walksForTarget + " walk(s)");
                return;
            }
            walksForTarget++;
            BlockPos[] drop = findDrop(client.level, player.position());
            if (drop == null) {
                giveUp("no hole in the roof near him drops onto the floor");
                return;
            }
            holeEdge = drop[0];
            holeFeet = drop[1];
            startWalk(client, player, holeEdge, "the roof's edge at " + AutoPuzzleUtil.fmt(holeFeet));
            return;
        }
        if (tickWalk(client)) {
            return;
        }
        advance(Stage.STEP_IN, "walked to the hole's edge");
    }

    /** Holds forward into the hole until he has dropped below the roof, then waits to land. */
    private static void stepIn(Minecraft client, LocalPlayer player) {
        if (onFloor(player)) {
            client.options.keyUp.setDown(false);
            advance(Stage.PUSH, "dropped onto the floor");
            return;
        }
        if (System.currentTimeMillis() - stageStartMs > 3000L) {
            client.options.keyUp.setDown(false);
            walksForTarget = Math.max(walksForTarget, 1);
            advance(Stage.DESCEND, "stepping into the hole timed out");
            return;
        }
        if (holeFeet == null || player.getY() < holeFeet.getY() - 1.0) {
            client.options.keyUp.setDown(false); // falling: let go so he lands under the hole
            return;
        }
        float[] dir = AutoPuzzleUtil.direction(player.getEyePosition(), Vec3.atCenterOf(holeFeet));
        AutoPuzzleUtil.rotateCamera(player, dir[0], 0f);
        client.options.keyUp.setDown(true);
    }

    /** {roof block he walks to (feet), hole (feet)} nearest him by walk, or null. */
    private static BlockPos[] findDrop(Level level, Vec3 from) {
        int roofY = (int) Math.floor(from.y + 0.01);
        int px = (int) Math.floor(from.x);
        int pz = (int) Math.floor(from.z);
        List<double[]> found = new ArrayList<>();
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int dx = -24; dx <= 24; dx++) {
            for (int dz = -24; dz <= 24; dz++) {
                int x = px + dx;
                int z = pz + dz;
                if (!clear(level, x, roofY, z) || !clear(level, x, roofY + 1, z) || !clear(level, x, roofY - 1, z)) {
                    continue;
                }
                double landing = Double.NaN;
                for (int y = roofY - 2; y >= floorY - 2; y--) {
                    if (!clear(level, x, y, z)) {
                        landing = MazeWalk.standHeight(level, x, y + 1, z);
                        break;
                    }
                }
                if (Double.isNaN(landing) || Math.abs(landing - floorY) > 0.6) {
                    continue;
                }
                for (int[] s : sides) {
                    double edge = MazeWalk.standHeight(level, x + s[0], roofY, z + s[1]);
                    if (!Double.isNaN(edge) && Math.abs(edge - from.y) < 0.6) {
                        found.add(new double[]{Math.hypot(x + s[0] + 0.5 - from.x, z + s[1] + 0.5 - from.z),
                                x + s[0], z + s[1], x, z});
                    }
                }
            }
        }
        found.sort((a, b) -> Double.compare(a[0], b[0]));
        int tried = 0;
        for (double[] f : found) {
            if (++tried > 12) {
                break;
            }
            BlockPos edge = new BlockPos((int) f[1], roofY, (int) f[2]);
            // The landing must lead to the boxes: a hole into a side passage that does not join the floor in
            // front of the grid is no use.
            Vec3 land = new Vec3((int) f[3] + 0.5, floorY, (int) f[4] + 0.5);
            if (gridFront != null && !new MazeWalk().plan(level, land, gridFront)) {
                continue;
            }
            if (WALK.plan(level, from, edge)) {
                return new BlockPos[]{edge, new BlockPos((int) f[3], roofY, (int) f[4])};
            }
        }
        return null;
    }

    private static boolean clear(Level level, int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        VoxelShape shape = level.getBlockState(p).getCollisionShape(level, p);
        return shape.isEmpty();
    }

    // ------------------------------------------------------------------ pushing

    private static long buttonMissingSinceMs = 0L;
    private static long pushStartMs = 0L;
    private static boolean sawSolution = false;

    private static void push(Minecraft client, LocalPlayer player) {
        if (pushStartMs == 0L) {
            pushStartMs = System.currentTimeMillis();
        }
        BlockPos next = BoulderSolverFeature.getNextClick();
        if (next != null) {
            sawSolution = true;
        }
        if (next == null) {
            if (!BoulderSolverConfig.getInstance().isEnabled()) {
                waitFor("Boulder Solver is off - it names the buttons; going for the chest as the boxes stand");
                advance(Stage.TO_CHEST, "no solver");
                return;
            }
            if (sawSolution || System.currentTimeMillis() - pushStartMs > SOLVER_WAIT_MS) {
                stopWalk(client, "no presses left");
                advance(Stage.TO_CHEST, sawSolution ? "every box the solver named was pushed (" + presses
                        + " press(es))" : "the solver read no known arrangement in " + SOLVER_WAIT_MS + " ms");
                return;
            }
            waitFor("the solver has not read the arrangement yet");
            return;
        }
        if (!(client.level.getBlockState(next).getBlock() instanceof net.minecraft.world.level.block.ButtonBlock)) {
            // The step's button is only laid once the box before it has moved.
            if (buttonMissingSinceMs == 0L) {
                buttonMissingSinceMs = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - buttonMissingSinceMs > BUTTON_WAIT_MS) {
                giveUp("the button at " + AutoPuzzleUtil.fmt(next) + " never appeared ("
                        + client.level.getBlockState(next).getBlock() + " there)");
                return;
            }
            waitFor("the button at " + AutoPuzzleUtil.fmt(next) + " is not there yet");
            return;
        }
        buttonMissingSinceMs = 0L;
        if (!BlockHitsReach.inReach(player, next)) {
            if (walkInto(client, player, next, "button " + AutoPuzzleUtil.fmt(next))) {
                return; // walk ended in reach - pressed next tick
            }
            return;
        }
        stopWalk(client, "button " + AutoPuzzleUtil.fmt(next) + " is in reach");
        if (player.isShiftKeyDown()) {
            waitFor("sneaking - a sneak-click would place the held item");
            return;
        }
        if (System.currentTimeMillis() - lastPressMs < PRESS_GAP_MS || !AutoPuzzleUtil.gateWorldClick()) {
            return;
        }
        lastPressMs = System.currentTimeMillis();
        if (!AutoPuzzleUtil.interactBlock(client, next)) {
            giveUp("no clickable shape at " + AutoPuzzleUtil.fmt(next));
            return;
        }
        presses++;
        walksForTarget = 0;
        LOGGER.info("[AutoPuzzles] Boulder: pressed {} ({} left)", AutoPuzzleUtil.fmt(next),
                BoulderSolverFeature.getRemainingClicks());
    }

    // ------------------------------------------------------------------ walking

    /**
     * Walks until {@code target}'s box is within {@link #WALK_EYE} of his eye.
     * @return true once it is within the server's reach (the caller acts); false while walking or when no walk
     *         could be planned (logged, and given up after {@link #MAX_WALKS_PER_TARGET})
     */
    private static boolean walkInto(Minecraft client, LocalPlayer player, BlockPos target, String label) {
        if (target == null) {
            giveUp("no position for " + label);
            return false;
        }
        if (!target.equals(walkFor)) {
            stopWalk(client, "new target " + label);
            walkFor = target;
            walksForTarget = 0;
        }
        if (walking) {
            if (BlockHitsReach.within(player, target, WALK_EYE)) {
                stopWalk(client, label + " is in reach");
                return true;
            }
            if (tickWalk(client)) {
                return false;
            }
        }
        if (BlockHitsReach.inReach(player, target)) {
            return true;
        }
        if (!player.onGround()) {
            return false;
        }
        if (walksForTarget >= MAX_WALKS_PER_TARGET) {
            giveUp("could not walk into reach of " + label + " after " + walksForTarget + " walk(s)");
            return false;
        }
        walksForTarget++;
        BlockPos spot = MazeWalk.planToSpot(client.level, player.position(), target, 6,
                eye -> com.killer560.hub.util.BlockHits.boxDistanceSq(eye, target) <= WALK_EYE * WALK_EYE, WALK);
        if (spot == null) {
            LOGGER.info("[AutoPuzzles] Boulder: no walkable spot within 6 blocks of {} has it in reach (try {}/{})",
                    label, walksForTarget, MAX_WALKS_PER_TARGET);
            return false;
        }
        startWalk(client, player, spot, label);
        return false;
    }

    private static void startWalk(Minecraft client, LocalPlayer player, BlockPos goal, String label) {
        walking = true;
        walkStartMs = System.currentTimeMillis();
        double len = WALK.length(player.position());
        walkTimeoutMs = 2500L + (long) (len / 3.5 * 1000.0);
        LOGGER.info("[AutoPuzzles] Boulder: walking to {} for {} - {} leg(s), {} blocks", AutoPuzzleUtil.fmt(goal),
                label, WALK.legs(), String.format(java.util.Locale.US, "%.1f", len));
        tickWalk(client);
    }

    /** @return true while the walk is still going */
    private static boolean tickWalk(Minecraft client) {
        if (!walking) {
            return false;
        }
        if (System.currentTimeMillis() - walkStartMs > walkTimeoutMs) {
            stopWalk(client, "walk timed out after " + walkTimeoutMs + " ms");
            return false;
        }
        if (WALK.tick(client)) {
            return true;
        }
        stopWalk(client, "walk finished");
        return false;
    }

    private static void stopWalk(Minecraft client, String why) {
        if (!walking) {
            return;
        }
        walking = false;
        client.options.keyUp.setDown(false);
        LOGGER.info("[AutoPuzzles] Boulder: stopped walking - {}", why);
    }

    // ------------------------------------------------------------------ the chest, and out

    /** Its own single, deliberate chest interact - never {@code SecretAuraFeature} - so it fires "even if secret
     *  aura is off". */
    private static void aura(Minecraft client, LocalPlayer player) {
        if (auraAttempts >= MAX_AURA_ATTEMPTS) {
            LOGGER.warn("[AutoPuzzles] Boulder: gave up auraing the chest after {} attempts - walking to the exit anyway",
                    MAX_AURA_ATTEMPTS);
            advance(Stage.WALK_TO_EXIT, "aura gave up");
            return;
        }
        BlockPos target = AutoPuzzleUtil.nearestChest(client, player, AURA_REACH_SQ);
        if (target == null) {
            target = chestReal;
        }
        double distSq = com.killer560.hub.util.BlockHits.boxDistanceSq(player.getEyePosition(), target);
        if (player.isShiftKeyDown() || distSq > AURA_REACH_SQ) {
            LOGGER.info("[AutoPuzzles] Boulder: chest aura blocked ({}), {} blocks to the box (limit {}) - attempt {} of {}",
                    player.isShiftKeyDown() ? "sneaking" : "out of reach",
                    String.format(java.util.Locale.US, "%.2f", Math.sqrt(distSq)),
                    String.format(java.util.Locale.US, "%.2f", Math.sqrt(AURA_REACH_SQ)),
                    auraAttempts + 1, MAX_AURA_ATTEMPTS);
            auraAttempts++;
            if (distSq > AURA_REACH_SQ) {
                walksForTarget = 0;
                advance(Stage.TO_CHEST, "the chest is out of reach again");
            }
            return;
        }
        if (!AutoPuzzleUtil.gateWorldClick()) {
            return; // gate held this tick back - not burnt, retried next tick
        }
        auraAttempts++;
        if (!AutoPuzzleUtil.interactBlock(client, target)) {
            LOGGER.warn("[AutoPuzzles] Boulder: no clickable shape at {} (attempt {}/{})", target, auraAttempts, MAX_AURA_ATTEMPTS);
            return;
        }
        ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Boulder: aura'd the "), ModChat.good("reward chest"),
                ModChat.text("."));
        LOGGER.info("[AutoPuzzles] Boulder: aura'd the chest at {} after {} press(es)", AutoPuzzleUtil.fmt(target), presses);
        doneChest = chestReal;
        advance(Stage.WALK_TO_EXIT, "aura'd the chest");
    }

    /** One Interactive Map walk back to the doorway spot ("walk back to the exit so it can etherwarp again"). */
    private static void walkToExit(LocalPlayer player) {
        if (exitReal == null || AutoPuzzleUtil.at(player, exitReal)) {
            advance(Stage.DONE, exitReal == null ? "no exit spot known" : "at the exit");
            return;
        }
        if (ClearExecutor.isBusy()) {
            return;
        }
        if (System.currentTimeMillis() - stageStartMs > 15_000L) {
            advance(Stage.DONE, "the walk to the exit timed out");
            return;
        }
        if (AutoPuzzleUtil.pathIfMapOn(exitReal, null)) {
            advance(Stage.DONE, "asked the Interactive Map for the exit");
        } else {
            waitFor("the Interactive Map is off or busy - not walking to the exit");
        }
    }

    // ------------------------------------------------------------------ bookkeeping

    private static void advance(Stage next, String why) {
        LOGGER.info("[AutoPuzzles] Boulder: {} -> {} ({})", stage, next, why);
        stage = next;
        stageStartMs = System.currentTimeMillis();
        loggedWait = null;
    }

    private static void giveUp(String why) {
        stopWalk(Minecraft.getInstance(), "giving up");
        LOGGER.info("[AutoPuzzles] Boulder: stopped for this room - {}", why);
        ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Auto Boulder: "), ModChat.bad(why), ModChat.text("."));
        stage = Stage.DONE;
    }

    private static void waitFor(String why) {
        if (!why.equals(loggedWait)) {
            loggedWait = why;
            LOGGER.info("[AutoPuzzles] Boulder: waiting - {}", why);
        }
    }

    private static void reset() {
        stopWalk(Minecraft.getInstance(), "left the room");
        stage = Stage.NEED_CHEST;
        chestReal = null;
        exitReal = null;
        floorY = Integer.MIN_VALUE;
        gridFront = null;
        auraAttempts = 0;
        stageStartMs = 0L;
        lastPressMs = 0L;
        presses = 0;
        noChestWarned = false;
        scanCooldown = 0;
        loggedWait = null;
        walkFor = null;
        walksForTarget = 0;
        holeEdge = null;
        holeFeet = null;
        buttonMissingSinceMs = 0L;
        pushStartMs = 0L;
        sawSolution = false;
    }

    /** Eye-to-box reach tests, standing eye as the client reports it. */
    private static final class BlockHitsReach {
        static boolean inReach(LocalPlayer player, BlockPos pos) {
            return com.killer560.hub.util.BlockHits.boxDistanceSq(player.getEyePosition(), pos) <= AURA_REACH_SQ;
        }

        static boolean within(LocalPlayer player, BlockPos pos, double blocks) {
            return com.killer560.hub.util.BlockHits.boxDistanceSq(player.getEyePosition(), pos) <= blocks * blocks;
        }
    }
}
