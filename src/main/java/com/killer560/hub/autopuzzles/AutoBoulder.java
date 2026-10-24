package com.killer560.hub.autopuzzles;

import com.killer560.hub.cheatutils.CheatUtilsConfig;
import com.killer560.hub.cheatutils.SecretAuraFeature;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.livemap.autoclear.ClearExecutor;
import com.killer560.hub.puzzlesolvers.BoulderSolverConfig;
import com.killer560.hub.puzzlesolvers.BoulderSolverFeature;
import com.killer560.hub.puzzlesolvers.PuzzleCoords;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.util.BlockHits;
import com.killer560.hub.util.BodyAim;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ServerCorrections;
import com.killer560.hub.util.ViewFreeze;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;

/**
 * Auto Boulder: get the Boulder room's reward chest, then walk back out so he can etherwarp again.
 *
 * <p>killer560 (2026-10-06): "first detect if i have secret aura on or not, if i do then once I interactive map to the
 * room or walk into it then it should walk straight forward toward those iron bars at the back ... it can just run on
 * the barrier blocks and it should be able to reach the chest if it runs all the way forward ... If i dont have secret
 * auras on then it needs to pathfind to the buttons that it actually needs ... that pathfinding needs to look legit ...
 * after it gets both it needs to run back out of the room such that I am able to etherwarp again."
 *
 * <h2>Starting</h2>
 * It starts when he walks into the room (the live map names it), or when an Interactive Map path ends at Boulder's
 * doorway spot (relative 15,68,-2, which the map files under the tile next door) - the {@code ClearExecutor.arrivalSeq}
 * trigger Auto Teleport Maze uses. Never while the map is still moving him.
 *
 * <h2>Secret Aura ON (checked once, at the start, with {@code CheatUtilsConfig.isSecretAuraEnabled})</h2>
 * The doorway opens at roof height: the box grid is roofed with barrier at relative y 68, and at the far end the roof
 * meets a block wall topped by iron bars (relative z 27), directly over the alcove holding the chest (15,66,29). Standing
 * against that wall his eye is about 4.4 from the chest's box (Map Logger capture, decoded), inside the 4.5 reach. So he
 * turns smoothly to face along the room toward the chest's column, holds forward and sprint, and lets go of both the
 * moment the chest is inside Secret Aura's range - Secret Aura does the click. No button is pressed. If he is not on
 * the roof (he fell, or came in some other way) the room is played as with Secret Aura off.
 *
 * <h2>Secret Aura OFF</h2>
 * The buttons {@link BoulderSolverFeature} names - only those, one at a time, each once its button exists (it is laid
 * after the box before it moves) - then the chest. Each is reached by {@link BoulderPath}, which plans across heights,
 * so the walk from the doorway takes the stairs down instead of dropping through a hole in the roof; it never stands on
 * the barrier roof. The walk is smooth: {@link HumanLook} turns (eased, capped, on the mouse grid), forward held only
 * while roughly facing the way, sprint only on a long straight. At a button he turns to look at it and presses only
 * when the crosshair ray actually hits it, on a tick where he did not turn - the use packet carries what the previous
 * movement packet already reported. The chest is opened the same way.
 *
 * <h2>Out</h2>
 * Then he walks back to the doorway and on out until the live map no longer files him under Boulder (where etherwarp
 * is refused) - up the stairs in the legit mode, back along the roof in the aura one.
 *
 * <h2>Safety</h2>
 * Movement is the forward and sprint keys and rotation only - nothing written to position or velocity. The body turns
 * through {@link BodyAim} with the camera held by {@link ViewFreeze}, and after the room the body is turned back to his
 * view at the same human pace before the camera is released (no snap). Any of S/A/D/jump pressed after it started, or a
 * screen, stops or pauses it. A server position correction never does (mod rule, 2026-10-06): it posts a chat line, plays
 * the correction alarm, and the walk is planned again from where the server put him. Every stage change and refusal is an INFO line.
 */
public final class AutoBoulder {

    private static final Logger LOGGER = ModLog.get("killer560smod-autopuzzles");
    private static final String ROOM = "Boulder";

    /** Relative heights, from the decoded capture: feet on the doorway platform / roof, feet on the box floor. */
    private static final int ROOF_FEET_REL = 69;
    private static final int FLOOR_FEET_REL = 64;
    /** The iron-bars wall the aura run heads for, relative z. */
    private static final int BARS_Z_REL = 27;

    /** Press / open from a standing eye within this of the box when there is such a spot; else {@link #REACH_LOOSE}. */
    private static final double REACH_SNUG = 3.3;
    private static final double REACH_LOOSE = 4.0;
    /** Boulder's reward chest, room-relative: the back alcove (SimBoulderPuzzle.CHEST_SPOT, and his Hypixel log). */
    private static final int[] KNOWN_CHEST_REL = {15, 66, 29};
    private static final long BUTTON_WAIT_MS = 3000L;
    private static final long SOLVER_WAIT_MS = 4000L;
    /** Secret Aura's wait, plus the "Boulder Chest Wait" slider, before he opens the chest himself. */
    private static final long AURA_WAIT_BASE_MS = 1000L;
    private static final long ROOM_TIMEOUT_MS = 120_000L;
    private static final int MAX_REPLANS = 3;
    /** Eye-to-box distance to the chest from against the bars, measured 4.29-4.42 (93-solve-boulder-aura, 2026-10-06):
     *  an aura range under this cannot take it from there. */
    private static final double AURA_CHEST_REACH = 4.4;
    private static final long PLAN_RETRY_MS = 250L;
    private static final long PLAN_GIVE_UP_MS = 2500L;
    private static long lastPlanFailMs = 0L;
    private static long planFailSinceMs = 0L;
    private static final int AIM_MAX_TICKS = 60;

    private enum Mode { AURA, BUTTONS }

    private enum Stage { NEED_CHEST, RUN_TO_BARS, AWAIT_AURA, PUSH, TO_CHEST, EXIT, DONE }

    private static final BodyAim BODY = new BodyAim(() -> { });
    private static final HumanLook LOOK = new HumanLook();
    /** Decided once per room: Secret Aura off turns his real camera; the aura run turns only the body. */
    private static boolean realCamera = false;
    private static long aimStartMs = 0L;
    private static BlockPos aimPointFor = null;
    private static Vec3 aimPt = null;
    private static long chestAtMs = 0L;
    /** This walk is the Secret Aura run back out: sprint every tick once facing the way. */
    private static boolean sprintAll = false;
    private static boolean sprintStarted = false;

    private static boolean engaged = false;
    private static Stage stage = Stage.NEED_CHEST;
    private static Mode mode = Mode.BUTTONS;
    private static int[] cr = null;
    private static BlockPos chestReal = null;
    /** The chest already taken in this world, so walking back in does not run the room again. */
    private static BlockPos doneChest = null;
    private static long engagedMs = 0L;
    private static long stageStartMs = 0L;
    private static String loggedWait = null;
    private static int consumedArrival = Integer.MIN_VALUE;
    /** {@code ClearExecutor.positionPackets()} last seen: any server teleport, not only a map path that ended where it
     *  planned. */
    private static int seenPackets = Integer.MIN_VALUE;
    /** Until when a landing at the doorway may still start the auto (a teleport, then settling onto the floor). */
    private static long doorCheckUntilMs = 0L;
    private static boolean noChestWarned = false;
    private static boolean unloadedWarned = false;
    private static int scanCooldown = 0;
    private static boolean sawContainer = false;
    /** {@code util/ChestWindows.count} as the current stage began: a chest window in the chest stages is the chest's,
     *  shown or not (Auto Close Chest cancels the screen). */
    private static int stageWindows = 0;
    private static int presses = 0;
    private static long lastPressMs = 0L;
    private static long buttonMissingSinceMs = 0L;
    private static long pushStartMs = 0L;
    /** util/ServerLag readings taken with stageStartMs, pushStartMs and buttonMissingSinceMs: each wait measures the
     *  time the server has had ({@code ServerLag.serverMsSince}), so a server stall never runs one out (2026-10-15). */
    private static long stageLag = 0L;
    private static long pushLag = 0L;
    private static long buttonLag = 0L;
    private static boolean sawSolution = false;
    /** Physical S/A/D/jump: armed once seen up, so a key already held when it started is not a takeover. */
    private static final boolean[] keyArmed = new boolean[4];
    private static volatile boolean correction = false;

    // walk
    private static BoulderPath path = null;
    private static List<Vec3> pts = List.of();
    private static int ptIdx = 0;
    private static boolean walking = false;
    private static String walkLabel = null;
    private static long walkStartMs = 0L;
    private static long walkTimeoutMs = 0L;
    private static double bestRemaining = Double.MAX_VALUE;
    private static long bestAtMs = 0L;
    private static int replans = 0;
    private static BoulderPath.GoalTest walkGoal = null;
    private static boolean walkAvoidBarrier = true;
    /** The aim at a button or the chest: ticks spent turning onto it. */
    private static int aimTicks = 0;
    private static BlockPos aimFor = null;
    /** RUN_TO_BARS: ticks in a row with forward held and no headway. */
    private static int stalledTicks = 0;

    // the run out: one straight run once nothing but open floor lies between him and the way out (see runOut)
    private static boolean running = false;
    private static boolean runStarted = false;
    /** A run out that stalled: the rest of this exit is walked on the path instead. */
    private static boolean runFailed = false;
    private static double runBest = Double.MAX_VALUE;
    private static long runBestMs = 0L;
    /** Forward goes down once his body is within this of the run's heading: far enough round that W carries him off
     *  the bars, not into them (pressed into a wall a sprint ends on the collision). */
    private static final float RUN_START_ERR = 60f;
    /** Where the run out aims: two blocks past the doorway gap, on the doorway's line (relative 15.5, -3). */
    private static final int OUT_Z_REL = -3;
    // the box layer as it stood at the last press: the next walk is planned once a box has moved (see push)
    private static int boxHashAtPress = 0;
    private static long pressLag = 0L;
    /** Liveness only: a press that moved no box (the server ignored it) is given this long, server lag added. */
    private static final long BOX_MOVE_TIMEOUT_MS = 1000L;

    private AutoBoulder() {
    }

    static void levelChanged() {
        disengage(Minecraft.getInstance(), null);
        doneChest = null;
        consumedArrival = ClearExecutor.arrivalSeq();
        seenPackets = ClearExecutor.positionPackets();
        doorCheckUntilMs = 0L;
    }

    private static volatile Vec3 testTarget = null;
    private static boolean testRunning = false;

    /**
     * For the testkit's GrimAC case (1016-grim-puzzle-speed): run out toward {@code target} every tick, exactly as the
     * room's run out does (turn at the human pace with the camera held, forward and sprint once facing it), with no room
     * around it - a dedicated test server has no Boulder. Null ends it: keys up, the body turned back under the view.
     */
    public static void testRunTo(Vec3 target) {
        testTarget = target;
    }

    /** From the position-packet hook: a server correction while he is being walked (reported and re-planned on the
     *  next tick - never a stop). */
    public static void onServerPosition() {
        if (engaged && (walking || stage == Stage.RUN_TO_BARS)) {
            correction = true;
        }
    }

    static void tick(Minecraft client, String roomName) {
        LocalPlayer player = client.player;
        long t0 = System.nanoTime();
        if (lastTickStartNanos != 0L) {
            long d = Math.max(20_000_000L, Math.min(150_000_000L, t0 - lastTickStartNanos));
            tickNanos = (long) (tickNanos * 0.7 + d * 0.3);
        }
        lastTickStartNanos = t0;
        finishStep(player);
        Vec3 test = testTarget;
        if (test != null && player != null) {
            // The testkit's GrimAC case: the run out's own steering, keys and body turn, toward its point, no room.
            if (!testRunning) {
                testRunning = true;
                running = true;
                runStarted = false;
                runFailed = false;
                realCamera = false;
                runBest = hdist(player.position(), test);
                runBestMs = System.currentTimeMillis();
            }
            runTo(client, player, test);
            BODY.tick(player, true);
            sentYaw = player.getYRot();
            sentPitch = player.getXRot();
            return;
        }
        if (testRunning) {
            testRunning = false;
            running = false;
            releaseKeys(client);
        }
        boolean acting = false;
        try {
            acting = run(client, player, roomName);
            if (acting) {
                AutoPuzzleActivity.note("Boulder");
            }
        } finally {
            if (!acting) {
                settleBody(client, player);
            } else {
                BODY.tick(player, true);
            }
            if (player != null) {
                sentYaw = player.getYRot();
                sentPitch = player.getXRot();
            }
        }
    }

    /** @return true while it is driving his body this tick */
    private static boolean run(Minecraft client, LocalPlayer player, String roomName) {
        AutoPuzzlesConfig cfg = AutoPuzzlesConfig.getInstance();
        if (!cfg.isAutoBoulderEnabled()) {
            if (engaged) {
                disengage(client, "Auto Boulder was switched off");
            }
            return false;
        }
        boolean inRoom = ROOM.equals(roomName);
        if (!engaged) {
            if (inRoom) {
                int[] c = LiveMapFeature.currentRoomClayAndRotation();
                if (c == null) {
                    waitFor("the live map has no rotation for the room yet");
                    return false;
                }
                if (ClearExecutor.isBusy()) {
                    waitFor("the Interactive Map is moving him");
                    return false;
                }
                engage(client, c, "he is in the room");
            } else {
                int[] c = mapArrivalAtDoor(player);
                if (c == null) {
                    return false;
                }
                engage(client, c, "the Interactive Map put him at the doorway");
            }
        }
        if (stage == Stage.DONE) {
            if (!inRoom && !nearDoor(player)) {
                LOGGER.info("[AutoPuzzles] Boulder: done and away from the doorway - idle again ({} s after the start,"
                        + " tick {})", fmt((System.currentTimeMillis() - engagedMs) / 1000.0), tick());
                disengage(client, null);
            }
            return false;
        }
        if (stage == Stage.EXIT && !inRoom && outsideRoom(player)) {
            finish(client, "out of the room - the live map no longer files him under Boulder, etherwarp is free");
            return false;
        }
        if (!inRoom && stage != Stage.EXIT && !nearDoor(player)) {
            disengage(client, "he left the room");
            return false;
        }
        if (System.currentTimeMillis() - engagedMs > ROOM_TIMEOUT_MS) {
            giveUp(client, "still not done after " + ROOM_TIMEOUT_MS / 1000 + " s");
            return false;
        }
        if ((stage == Stage.AWAIT_AURA || stage == Stage.TO_CHEST)
                && com.killer560.hub.util.ChestWindows.since(stageWindows)) {
            sawContainer = true;
        }
        if (McCompat.screen(client) != null) {
            if (stage == Stage.AWAIT_AURA || stage == Stage.TO_CHEST) {
                sawContainer = true;
            }
            releaseKeys(client);
            waitFor("a screen is open (stage " + stage + (sawContainer ? ", counted as the chest's window" : "") + ")");
            return true;
        }
        if (ClearExecutor.isBusy()) {
            releaseKeys(client);
            waitFor("the Interactive Map is moving him");
            return true;
        }
        String takeover = takeover(client);
        if (takeover != null) {
            giveUp(client, "you pressed " + takeover);
            return false;
        }
        if (correction && ServerCorrections.lastMoveDistance() < ServerCorrections.MIN_MOVE_BLOCKS) {
            LOGGER.debug("[AutoPuzzles] Boulder: position packet moved him {} - rotation only, ignored",
                    ServerCorrections.lastMoveDistance());
            correction = false; // rotation-only packet: nothing moved
        }
        if (correction) {
            correction = false;
            // Mod rule (killer560, 2026-10-06): a correction never stops an auto - chat line + alarm, then carry on from
            // where the server put him. A walk is planned again from here (not counted against MAX_REPLANS); the run to
            // the bars steers from the live position every tick anyway.
            ServerCorrections.report("Auto Boulder", (walking ? "walking to " + walkLabel : "on the run to the bars")
                    + " - planning again from here", ServerCorrections.lastMoveDistance());
            LOGGER.info("[AutoPuzzles] Boulder: server correction of {} blocks in stage {} - now at {} (tick {})",
                    fmt(ServerCorrections.lastMoveDistance()), stage, fmt(player.position()), tick());
            if (walking) {
                String label = walkLabel;
                stopWalk(client, "server correction - planning again from here");
                if (walkGoal != null && player.onGround()) {
                    startWalk(client, player, walkGoal, walkAvoidBarrier, walkLabel);
                } else {
                    LOGGER.info("[AutoPuzzles] Boulder: not re-planning to {} this tick - {}", label,
                            walkGoal == null ? "no goal" : "not on the ground");
                }
            }
        }
        switch (stage) {
            case NEED_CHEST -> findChest(client, player);
            case RUN_TO_BARS -> runToBars(client, player);
            case AWAIT_AURA -> awaitAura(client, player);
            case PUSH -> push(client, player);
            case TO_CHEST -> toChest(client, player);
            case EXIT -> exit(client, player);
            default -> {
            }
        }
        return engaged && stage != Stage.DONE;
    }

    // ------------------------------------------------------------------ starting

    private static void engage(Minecraft client, int[] c, String why) {
        engaged = true;
        cr = c;
        engagedMs = System.currentTimeMillis();
        stage = Stage.NEED_CHEST;
        stageStartMs = engagedMs;
        stageLag = com.killer560.hub.util.ServerLag.mark();
        correction = false;
        KeyMapping[] keys = ownKeys(client);
        for (int i = 0; i < keys.length; i++) {
            keyArmed[i] = !keys[i].isDown();
        }
        LOGGER.info("[AutoPuzzles] Boulder: started - {} (at {}, relative {}, rotation clay {}, keys already held"
                        + " back/left/right/jump {}/{}/{}/{}, chest wait slider {} ms, tick {})", why,
                fmt(client.player == null ? null : client.player.position()),
                client.player == null ? "-" : AutoPuzzleUtil.fmt(PuzzleCoords.relative(client.player.blockPosition(), c)),
                java.util.Arrays.toString(c), !keyArmed[0], !keyArmed[1], !keyArmed[2], !keyArmed[3],
                AutoPuzzlesConfig.getInstance().getBoulderDelayMs(), tick());
    }

    /**
     * Boulder's clay/rotation when a teleport has just put him on its doorway spot, else null.
     *
     * <p>killer560 (2026-10-15): "Auto boulder does not work if i ehterwarp to the room on interactive map but it does if
     * i step into the room." The doorway spot the map lands on (relative 15, 68, -2) is filed by the live map under the
     * tile NEXT door, so "he is in the room" never starts it there; the one other start was ClearExecutor's arrival
     * counter, which only moves when a path ends within 1.5 blocks of its last planned landing ("ended N blocks from the
     * planned landing" moves nothing) - and not at all for an etherwarp of his own. Now ANY server teleport (every map
     * hop, every etherwarp) that leaves him on that spot starts it, once he is on the ground with no map path running.
     */
    private static int[] mapArrivalAtDoor(LocalPlayer player) {
        long now = System.currentTimeMillis();
        int arrival = ClearExecutor.arrivalSeq();
        if (arrival != consumedArrival) {
            consumedArrival = arrival;
            if (now - ClearExecutor.arrivalMs() <= 3000L) {
                doorCheckUntilMs = Math.max(doorCheckUntilMs, now + 3000L);
            }
        }
        int packets = ClearExecutor.positionPackets();
        if (packets != seenPackets) {
            seenPackets = packets;
            doorCheckUntilMs = Math.max(doorCheckUntilMs, now + 3000L);
            loggedDoor = null;
        }
        if (now > doorCheckUntilMs) {
            return null;
        }
        if (ClearExecutor.isBusy() || !player.onGround()) {
            LOGGER.debug("[AutoPuzzles] Boulder: door check - not settled yet (map busy {}, on ground {})",
                    ClearExecutor.isBusy(), player.onGround());
            return null; // not settled yet - the 3 s window allows for it
        }
        int[] c = boulderClayRotation();
        if (c == null) {
            LOGGER.debug("[AutoPuzzles] Boulder: door check - the layout has no Boulder room with a rotation");
            return null;
        }
        BlockPos door = PuzzleCoords.real(15, ROOF_FEET_REL, -1, c);
        BlockPos feet = player.blockPosition();
        if (Math.abs(feet.getX() - door.getX()) > 4 || Math.abs(feet.getZ() - door.getZ()) > 4
                || Math.abs(feet.getY() - door.getY()) > 2) {
            if (Math.abs(feet.getX() - door.getX()) <= 10 && Math.abs(feet.getZ() - door.getZ()) <= 10) {
                // Only near Boulder's doorway: every teleport anywhere opens this window, and those say nothing.
                doorNote("feet " + AutoPuzzleUtil.fmt(feet) + " are outside the 4/4/2 box round the doorway "
                        + AutoPuzzleUtil.fmt(door));
            }
            return null;
        }
        LOGGER.info("[AutoPuzzles] Boulder: a teleport left him at {} by the doorway {} - starting (tick {})",
                AutoPuzzleUtil.fmt(feet), AutoPuzzleUtil.fmt(door), tick());
        doorCheckUntilMs = 0L;
        return c;
    }

    private static int[] boulderClayRotation() {
        com.killer560.hub.livemap.DungeonLayout layout = com.killer560.hub.livemap.DungeonLayout.current();
        if (layout == null) {
            return null;
        }
        for (int r = 0; r < layout.roomCount(); r++) {
            if (ROOM.equals(layout.name(r))) {
                return layout.clayRotation(r);
            }
        }
        return null;
    }

    private static BlockPos rel(LocalPlayer player) {
        return PuzzleCoords.relative(player.blockPosition(), cr);
    }

    /** Within a few blocks outside the doorway (the map's spot is two out). */
    private static boolean nearDoor(LocalPlayer player) {
        if (cr == null) {
            return false;
        }
        BlockPos r = rel(player);
        return r.getZ() >= -5 && r.getZ() <= 1 && Math.abs(r.getX() - 15) <= 5;
    }

    /** Past the doorway gap, into the next tile. */
    private static boolean outsideRoom(LocalPlayer player) {
        return cr != null && rel(player).getZ() <= -1;
    }

    // ------------------------------------------------------------------ the chest

    private static void findChest(Minecraft client, LocalPlayer player) {
        RoomEntry entry = LiveMapFeature.currentRoomEntry();
        RoomEntry.Pos chestRel = null;
        String source = null;
        var chests = entry == null || !ROOM.equals(entry.name) || entry.secretCoords == null ? null
                : entry.secretCoords.chest;
        if (chests != null && !chests.isEmpty()) {
            chestRel = chests.get(0);
            for (RoomEntry.Pos p : chests) {
                if (p.z > chestRel.z) {
                    chestRel = p;
                }
            }
            source = "room database";
        } else {
            if (scanCooldown > 0) {
                LOGGER.debug("[AutoPuzzles] Boulder: no database chest - next room scan in {} tick(s)", scanCooldown);
                scanCooldown--;
                return;
            }
            scanCooldown = 20;
            LOGGER.info("[AutoPuzzles] Boulder: no chest in the room database ({}) - scanning the room for chest blocks"
                    + " (tick {})", entry == null ? "no entry" : !ROOM.equals(entry.name) ? "entry is '" + entry.name + "'"
                    : "entry has no chest", tick());
            BlockPos bestRel = null;
            java.util.List<BlockPos> found = AutoPuzzleUtil.chestsInRoom(client.level, cr, -1, 60, -1, 31, 75, 31);
            if (found == null) {
                // Part of the room is not loaded on the client: unknown, not "no chest" - and never the furthest of
                // the chests that happen to be loaded. The chest's spot is fixed, so go for it (see below).
                if (!unloadedWarned) {
                    unloadedWarned = true;
                    LOGGER.info("[AutoPuzzles] Boulder: part of the room is not loaded yet - going for the known chest "
                            + "spot");
                }
                found = java.util.List.of();
            }
            for (BlockPos real : found) {
                BlockPos r = PuzzleCoords.relative(real, cr);
                if (bestRel == null || r.getZ() > bestRel.getZ()) {
                    bestRel = r;
                }
            }
            if (!found.isEmpty()) {
                LOGGER.info("[AutoPuzzles] Boulder: room scan found {} chest block(s); furthest back at relative {}",
                        found.size(), AutoPuzzleUtil.fmt(bestRel));
            }
            if (bestRel == null) {
                // Boulder has one layout and its chest is always in the back alcove, relative (15, 66, 29) - read off his
                // Hypixel run (Mod Only Test 2026-10-19 15:51:39) and the sim's CHEST_SPOT. After a map warp onto the
                // doorway the far end of the room had not reached the client yet: the chunks read loaded but the chest
                // block was not in them, and the auto stood at the doorway for 12 s "looking again every 20 ticks" until
                // the blocks arrived (15:51:27 -> 15:51:39), which read as waiting for him to walk in. Go for the known
                // spot now; the run to the bars and Secret Aura only need where it is.
                bestRel = new BlockPos(KNOWN_CHEST_REL[0], KNOWN_CHEST_REL[1], KNOWN_CHEST_REL[2]);
                LOGGER.info("[AutoPuzzles] Boulder: no chest block in the client's copy of the room yet - going for "
                        + "Boulder's known chest spot (relative {}, {}, {}) instead of waiting for the blocks to arrive",
                        KNOWN_CHEST_REL[0], KNOWN_CHEST_REL[1], KNOWN_CHEST_REL[2]);
            }
            chestRel = new RoomEntry.Pos();
            chestRel.x = bestRel.getX();
            chestRel.y = bestRel.getY();
            chestRel.z = bestRel.getZ();
            source = "chest block found in the room";
        }
        chestReal = PuzzleCoords.real(chestRel, cr);
        if (chestReal.equals(doneChest)) {
            advance(Stage.DONE, "this room's chest was already taken");
            return;
        }
        // Whether Secret Aura will actually OPEN this chest, not just whether it is switched on (killer560,
        // 2026-10-06: "if I have chest aura off as well then it needs to go and press the buttons").
        String refusal = SecretAuraFeature.chestRefusal(client, AURA_CHEST_REACH);
        boolean aura = refusal == null;
        double roofY = PuzzleCoords.real(15, ROOF_FEET_REL, 0, cr).getY();
        boolean onRoof = player.getY() > roofY - 0.6;
        LOGGER.info("[AutoPuzzles] Boulder: chest at {} (relative {}, {}, {}) from the {}; Secret Aura {}; feet y {} "
                        + "(roof {}, on roof {}); block there now {}; eye to chest box {}; aura range {} (tick {})",
                AutoPuzzleUtil.fmt(chestReal), chestRel.x, chestRel.y, chestRel.z, source,
                aura ? "will take chests" : "will not take it (" + refusal + ")", fmt(player.getY()), (int) roofY,
                onRoof, client.level.getBlockState(chestReal).getBlock(),
                fmt(Math.sqrt(BlockHits.boxDistanceSq(player.getEyePosition(), chestReal))),
                fmt(CheatUtilsConfig.getInstance().getAuraRange()), tick());
        realCamera = !(aura && onRoof);
        if (aura && onRoof) {
            mode = Mode.AURA;
            advance(Stage.RUN_TO_BARS, "Secret Aura will take the chest - running along the roof to the bars");
        } else {
            mode = Mode.BUTTONS;
            advance(Stage.PUSH, aura ? "Secret Aura would take the chest but he is below the roof - pressing the buttons"
                    : refusal + " - pressing the solver's buttons");
        }
    }

    private static boolean chestTaken(Minecraft client) {
        if (chestReal == null) {
            return false;
        }
        if (sawContainer || SecretAuraFeature.isDone(chestReal)) {
            return true;
        }
        BlockEntity be = client.level.getBlockEntity(chestReal);
        return be instanceof ChestBlockEntity chest && chest.getOpenNess(0f) > 0f;
    }

    // ------------------------------------------------------------------ Secret Aura on: along the roof

    /** RUN_TO_BARS holds forward only while his yaw is within this many degrees of the run's direction. */
    private static final float FACING_TOLERANCE = 45f;
    /** The bars run's forward state as last logged (null: not logged in this run). */
    private static Boolean loggedBarsForward = null;
    /** The solver's next button as last logged. */
    private static BlockPos loggedNextButton = null;

    private static void runToBars(Minecraft client, LocalPlayer player) {
        double reach = Math.min(CheatUtilsConfig.getInstance().getAuraRange(), CheatUtilsConfig.MEASURED_MAX_REACH)
                - 0.08;
        double dist = Math.sqrt(BlockHits.boxDistanceSq(player.getEyePosition(), chestReal));
        if (chestTaken(client)) {
            releaseKeys(client);
            chestDone("Secret Aura took the chest on the way");
            return;
        }
        if (dist <= reach) {
            releaseKeys(client);
            advance(Stage.AWAIT_AURA, "the chest is " + fmt(dist) + " from his eye, inside Secret Aura's " + fmt(reach));
            return;
        }
        double roofY = PuzzleCoords.real(15, ROOF_FEET_REL, 0, cr).getY();
        if (player.onGround() && player.getY() < roofY - 1.0) {
            releaseKeys(client);
            mode = Mode.BUTTONS;
            advance(Stage.PUSH, "he is off the roof (feet y " + fmt(player.getY()) + ") - pressing the buttons");
            return;
        }
        if (System.currentTimeMillis() - stageStartMs > 10_000L) {
            giveUp(client, "the run to the bars took over 10 s (chest still " + fmt(dist) + " away)");
            return;
        }
        // Straight along the room at the chest's column, toward the bars.
        BlockPos bars = PuzzleCoords.real(15, ROOF_FEET_REL, BARS_Z_REL, cr);
        Vec3 aim = new Vec3(chestReal.getX() + 0.5, player.getEyeY(), chestReal.getZ() + 0.5);
        if (Math.hypot(aim.x - player.getX(), aim.z - player.getZ()) < 1.0) {
            aim = new Vec3(bars.getX() + 0.5, player.getEyeY(), bars.getZ() + 0.5);
        }
        float yaw = AutoPuzzleUtil.direction(player.getEyePosition(), aim)[0];
        LOOK.begin("bars");
        turnToward(client, player, yaw, 10f);
        // Forward AND sprint from the very first tick to the bars (killer560, 2026-10-06: sprinting the whole time).
        // He is already roughly facing down the room on the way in; the turn finishes while he runs.
        // But only once he faces roughly down the room: the map can leave him at the doorway facing along the corridor
        // he came in by, and forward held through the turn ran him 4 blocks the wrong way into a wall outside the room,
        // and Auto Boulder gave up (fullclear-1016 runs 055 and 057, both at the north door of a rotation-0 Boulder).
        boolean forward = Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - player.getYRot())) <= FACING_TOLERANCE;
        if (!Boolean.valueOf(forward).equals(loggedBarsForward)) {
            loggedBarsForward = forward;
            LOGGER.info("[AutoPuzzles] Boulder: run to the bars - forward and sprint {} (yaw error {}, chest {} away, at {},"
                            + " tick {})", forward ? "down" : "up, turning first",
                    fmt(Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - player.getYRot()))), fmt(dist),
                    fmt(player.position()), tick());
        }
        client.options.keyUp.setDown(forward);
        client.options.keySprint.setDown(forward);
        double speed = Math.hypot(player.getDeltaMovement().x, player.getDeltaMovement().z);
        LOGGER.debug("[AutoPuzzles] Boulder: bars run tick - chest {} away (reach {}), speed {}, stalled {}", dist, reach,
                speed, stalledTicks);
        if (forward && player.onGround() && speed < 0.02 && System.currentTimeMillis() - stageStartMs > 600L) {
            if (++stalledTicks >= 10) {
                releaseKeys(client);
                double auraReach = Math.min(CheatUtilsConfig.getInstance().getAuraRange(),
                        CheatUtilsConfig.MEASURED_MAX_REACH);
                if (dist <= auraReach) {
                    advance(Stage.AWAIT_AURA, "against the bars, the chest " + fmt(dist) + " from his eye");
                } else {
                    giveUp(client, "ran into something at " + AutoPuzzleUtil.fmt(player.blockPosition())
                            + " with the chest " + fmt(dist) + " away - past Secret Aura's " + fmt(auraReach));
                }
            }
        } else {
            stalledTicks = 0;
        }
    }

    private static void awaitAura(Minecraft client, LocalPlayer player) {
        releaseKeys(client);
        if (chestTaken(client)) {
            chestDone("Secret Aura took the chest");
            return;
        }
        // Look at it while Secret Aura takes it, at the same human pace as every other turn here: Secret Aura clicks only
        // along a look the server was told about, and would otherwise snap his body round to it (util/TurnFirst).
        net.minecraft.world.phys.BlockHitResult face = chestReal == null ? null
                : com.killer560.hub.util.BlockHits.surface(client.level, chestReal, player.getEyePosition());
        if (face != null) {
            float[] dir = AutoPuzzleUtil.direction(player.getEyePosition(), face.getLocation());
            turnToward(client, player, dir[0], dir[1]);
        }
        long wait = AURA_WAIT_BASE_MS + AutoPuzzlesConfig.getInstance().getBoulderDelayMs();
        waitFor("Secret Aura to take the chest at " + AutoPuzzleUtil.fmt(chestReal) + " (up to " + wait + " ms)");
        if (com.killer560.hub.util.ServerLag.serverMsSince(stageStartMs, stageLag) > wait) {
            LOGGER.info("[AutoPuzzles] Boulder: Secret Aura has not taken the chest in {} ms - opening it by looking at it"
                            + " (eye to box {}, Secret Aura says {}, tick {})", wait,
                    fmt(Math.sqrt(BlockHits.boxDistanceSq(player.getEyePosition(), chestReal))),
                    SecretAuraFeature.chestRefusal(client, AURA_CHEST_REACH) == null ? "it would take it"
                            : SecretAuraFeature.chestRefusal(client, AURA_CHEST_REACH), tick());
            advance(Stage.TO_CHEST, "opening it himself");
        }
    }

    private static void chestDone(String why) {
        doneChest = chestReal;
        chestAtMs = System.currentTimeMillis();
        LOGGER.info("[AutoPuzzles] Boulder: chest {} taken ({}) after {} press(es) - solve time {} s from the start",
                AutoPuzzleUtil.fmt(chestReal), why, presses, fmt((chestAtMs - engagedMs) / 1000.0));
        stopWalk(Minecraft.getInstance(), null);
        advance(Stage.EXIT, why);
    }

    // ------------------------------------------------------------------ Secret Aura off: the buttons

    private static void push(Minecraft client, LocalPlayer player) {
        if (pushStartMs == 0L) {
            pushStartMs = System.currentTimeMillis();
            pushLag = com.killer560.hub.util.ServerLag.mark();
        }
        BlockPos next = BoulderSolverFeature.getNextClick();
        if (next != null) {
            sawSolution = true;
        } else {
            if (!BoulderSolverConfig.getInstance().isEnabled()) {
                waitFor("Boulder Solver is off - it names the buttons; going for the chest as the boxes stand");
                advance(Stage.TO_CHEST, "no solver");
                return;
            }
            RoomEntry here = LiveMapFeature.currentRoomEntry();
            if (!sawSolution && (here == null || !ROOM.equals(here.name)) && rel(player).getZ() < 1) {
                // THE SOLVER ONLY READS THE BOXES FROM INSIDE THE ROOM. 142-sim-autopilot2 (2026-10-06): the map put
                // him on Boulder's doorway spot (relative z -2), which the live map files under the tile next door;
                // Boulder Solver scans only while the live map says Boulder, so it never read the arrangement, the
                // 4 s wait ran out where he stood, and with the boxes unmoved "no walkable spot has the reward chest
                // in reach and in sight". He walks in first (the nearest spot inside the tile, as he would), and the
                // solver's wait starts there. Same on Hypixel: the map lands on the doorway there too.
                pushStartMs = System.currentTimeMillis();
                pushLag = com.killer560.hub.util.ServerLag.mark();
                if (walking) {
                    tickWalk(client, player);
                    return;
                }
                if (!player.onGround()) {
                    releaseKeys(client);
                    waitFor("in the air at the doorway - walking in once he lands");
                    return;
                }
                LOGGER.info("[AutoPuzzles] Boulder: at the doorway (relative z {}), the live map files him under '{}' -"
                                + " walking in so the solver reads the boxes (tick {})", rel(player).getZ(),
                        here == null ? "nothing" : here.name, tick());
                BoulderPath.GoalTest inside = (feet, s) -> PuzzleCoords.relative(feet, cr).getZ() >= 1;
                String label = "into the room (the solver reads the boxes from inside)";
                if (!startWalk(client, player, inside, true, label) && !startWalk(client, player, inside, false, label)) {
                    giveUp(client, "no walk from the doorway into the room");
                }
                return;
            }
            if (sawSolution || com.killer560.hub.util.ServerLag.serverMsSince(pushStartMs, pushLag) > SOLVER_WAIT_MS) {
                stopWalk(client, null);
                advance(Stage.TO_CHEST, sawSolution ? "every box the solver named was pushed (" + presses
                        + " press(es))" : "the solver read no known arrangement in " + SOLVER_WAIT_MS + " ms");
                return;
            }
            releaseKeys(client);
            waitFor("the solver has not read the arrangement yet");
            return;
        }
        if (!(client.level.getBlockState(next).getBlock() instanceof ButtonBlock)) {
            releaseKeys(client);
            if (buttonMissingSinceMs == 0L) {
                buttonMissingSinceMs = System.currentTimeMillis();
                buttonLag = com.killer560.hub.util.ServerLag.mark();
            } else if (com.killer560.hub.util.ServerLag.serverMsSince(buttonMissingSinceMs, buttonLag) > BUTTON_WAIT_MS) {
                giveUp(client, "the button at " + AutoPuzzleUtil.fmt(next) + " never appeared ("
                        + client.level.getBlockState(next).getBlock() + " there)");
                return;
            }
            waitFor("the button at " + AutoPuzzleUtil.fmt(next) + " is not there yet");
            return;
        }
        buttonMissingSinceMs = 0L;
        if (boxHashAtPress != 0) {
            // The box the last press moved reaches the client a few ticks later; a walk planned before that plans
            // round where it used to be (2026-10-06 run: "no walk", 223 nodes, the very tick of the press). Waited for
            // by what it is - a box layer that has changed - not a fixed 400 ms (killer560's rule: no fixed waits).
            if (boxHash(client) == boxHashAtPress
                    && com.killer560.hub.util.ServerLag.serverMsSince(lastPressMs, pressLag) < BOX_MOVE_TIMEOUT_MS) {
                releaseKeys(client);
                waitFor("the box the last press pushes has not moved yet");
                return;
            }
            boxHashAtPress = 0;
        }
        if (!next.equals(loggedNextButton)) {
            loggedNextButton = next;
            LOGGER.info("[AutoPuzzles] Boulder: solver's next button {} ({} left, {} pressed, {} ms after the last press,"
                            + " tick {})", AutoPuzzleUtil.fmt(next), BoulderSolverFeature.getRemainingClicks(), presses,
                    lastPressMs == 0L ? -1 : System.currentTimeMillis() - lastPressMs, tick());
        }
        if (approachAndClick(client, player, next, "button " + AutoPuzzleUtil.fmt(next))) {
            // Read in the click's own tick: the server's answer (the box moving) comes ticks later.
            boxHashAtPress = boxHash(client);
            pressLag = com.killer560.hub.util.ServerLag.mark();
            presses++;
            LOGGER.info("[AutoPuzzles] Boulder: pressed {} ({} left), press {}, from {}, holding {} (tick {})",
                    AutoPuzzleUtil.fmt(next), BoulderSolverFeature.getRemainingClicks(), presses, fmt(player.position()),
                    player.getMainHandItem().getHoverName().getString(), tick());
        }
    }

    private static void toChest(Minecraft client, LocalPlayer player) {
        if (chestTaken(client)) {
            chestDone(sawContainer ? "the chest opened" : "the chest is open");
            return;
        }
        if (approachAndClick(client, player, chestReal, "the reward chest")) {
            LOGGER.info("[AutoPuzzles] Boulder: opened the chest at {} looking at it, after {} press(es)",
                    AutoPuzzleUtil.fmt(chestReal), presses);
            chestDone("opened it looking at it");
        }
    }

    /**
     * Walks to a spot with {@code target} in reach and in sight, turns to look at it, and right-clicks it when the
     * crosshair is on it.
     *
     * @return true on the tick the click was sent
     */
    private static boolean approachAndClick(Minecraft client, LocalPlayer player, BlockPos target, String label) {
        Vec3 eye = player.getEyePosition();
        boolean inSpot = BlockHits.boxDistanceSq(eye, target) <= REACH_LOOSE * REACH_LOOSE
                && sees(client, eye, target) && player.onGround();
        if (walking && !target.equals(aimFor)) {
            // a walk for something else
            stopWalk(client, null);
        }
        if (!inSpot || walking) {
            if (walking) {
                if (BlockHits.boxDistanceSq(eye, target) <= REACH_SNUG * REACH_SNUG && sees(client, eye, target)
                        && player.onGround()) {
                    stopWalk(client, label + " is in reach");
                } else {
                    tickWalk(client, player);
                    return false;
                }
            } else {
                if (!player.onGround()) {
                    releaseKeys(client);
                    waitFor("in the air - planning the walk to " + label + " once he lands");
                    return false;
                }
                aimFor = target;
                long now = System.currentTimeMillis();
                if (now - lastPlanFailMs < PLAN_RETRY_MS) {
                    LOGGER.debug("[AutoPuzzles] Boulder: plan to {} failed {} ms ago - retrying after {} ms", label,
                            now - lastPlanFailMs, PLAN_RETRY_MS);
                    return false; // the world may still be catching up with a box that moved
                }
                if (!startWalk(client, player, spotGoal(client, target, REACH_SNUG), mode == Mode.BUTTONS, label)
                        && !startWalk(client, player, spotGoal(client, target, REACH_LOOSE), mode == Mode.BUTTONS,
                        label)) {
                    LOGGER.info("[AutoPuzzles] Boulder: no spot within {} or {} has {} in reach and sight from {} -"
                                    + " retrying, {} ms of {} used", REACH_SNUG, REACH_LOOSE, label, fmt(player.position()),
                            planFailSinceMs == 0L ? 0 : now - planFailSinceMs, PLAN_GIVE_UP_MS);
                    lastPlanFailMs = now;
                    if (planFailSinceMs == 0L) {
                        planFailSinceMs = now;
                    } else if (now - planFailSinceMs > PLAN_GIVE_UP_MS) {
                        giveUp(client, "no walkable spot has " + label + " in reach and in sight");
                    }
                } else {
                    planFailSinceMs = 0L;
                }
                return false;
            }
        }
        releaseKeys(client);
        long now = System.currentTimeMillis();
        if (!target.equals(aimFor) || aimStartMs == 0L) {
            aimFor = target;
            aimTicks = 0;
            aimStartMs = now;
            aimPointFor = null;
        }
        if (aimPointFor == null || !aimPointFor.equals(target)) {
            aimPointFor = target;
            aimPt = humanAimPoint(client, target);
            LOOK.begin(target);
        }
        // FIRST, before any turn this tick: is his crosshair on it? The rotation he has now is the one the last
        // movement packet reported, so a click now carries what the server already has (BadPacketsJ's rule).
        BlockHitResult hit = crosshairOn(client, player, target);
        if (hit != null) {
            if (player.isShiftKeyDown()) {
                waitFor("sneaking - a sneak-click would place the held item");
                return false;
            }
            if (!AutoPuzzleUtil.gateWorldClick()) {
                waitFor("the action gate held the click on " + label);
                return false;
            }
            long sincePrev = lastPressMs == 0L ? now - engagedMs : now - lastPressMs;
            lastPressMs = now;
            client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND);
            LOGGER.info("[AutoPuzzles] Boulder: click on {} - crosshair on it: yes ({} hit {} face {}), turn {} tick(s) "
                            + "/ {} ms after the walk, {} ms since the previous click; eye {} to box {}, yaw {} pitch {},"
                            + " holding {} (tick {})", label,
                    realCamera ? "camera and last-sent rotation" : "body ray", AutoPuzzleUtil.fmt(hit.getBlockPos()),
                    hit.getDirection().getName(), aimTicks, now - aimStartMs, sincePrev, fmt(eye),
                    fmt(Math.sqrt(BlockHits.boxDistanceSq(eye, target))), fmt(sentYaw), fmt(sentPitch),
                    player.getMainHandItem().getHoverName().getString(), tick());
            aimFor = null;
            aimTicks = 0;
            aimStartMs = 0L;
            aimPointFor = null;
            return true;
        }
        if (++aimTicks > AIM_MAX_TICKS) {
            giveUp(client, "could not get the crosshair onto " + label + " in " + AIM_MAX_TICKS + " ticks");
            return false;
        }
        if (aimTicks % 10 == 0) {
            // Ten ticks on and the crosshair is still not on it: the point picked was hidden (a box still settling
            // in front of a button that has just been laid) - pick again from where he stands now.
            aimPt = humanAimPoint(client, target);
            LOGGER.info("[AutoPuzzles] Boulder: crosshair still not on {} after {} tick(s) - new aim point {} (yaw {} pitch"
                            + " {}, from {})", label, aimTicks, fmt(aimPt), fmt(player.getYRot()), fmt(player.getXRot()),
                    fmt(player.getEyePosition()));
        }
        LOGGER.debug("[AutoPuzzles] Boulder: turning onto {} - tick {} of the aim", label, aimTicks);
        float[] dir = AutoPuzzleUtil.direction(player.getEyePosition(), aimPt);
        turnToward(client, player, dir[0], dir[1]);
        return false;
    }

    /**
     * The block his crosshair is on, if it is {@code target}. With his real camera that is the client's own pick
     * ({@code Minecraft.hitResult}, what vanilla right-clicks); with the camera held (the Secret Aura run's fallback) it
     * is the same ray cast from his body's rotation.
     */
    private static BlockHitResult crosshairOn(Minecraft client, LocalPlayer player, BlockPos target) {
        if (Float.isNaN(sentYaw)) {
            return null;
        }
        // The rotation now on screen (the step just finished) AND the one the server last heard must both be on it;
        // the click is built from the latter, so the server sees a use where it already knows he is looking.
        BlockHitResult now = ray(client, player, player.getYRot(), player.getXRot(), target);
        return now == null ? null : ray(client, player, sentYaw, sentPitch, target);
    }

    private static BlockHitResult ray(Minecraft client, LocalPlayer player, float yaw, float pitch, BlockPos target) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = AutoPuzzleUtil.look(yaw, pitch);
        HitResult hr = client.level.clip(new ClipContext(eye, eye.add(look.scale(CheatUtilsConfig.MEASURED_MAX_REACH)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hr instanceof BlockHitResult bh && hr.getType() == HitResult.Type.BLOCK
                && bh.getBlockPos().equals(target) ? bh : null;
    }

    /** A point inside the middle 60% of the target's outline, picked once per target - nobody aims at the exact center. */
    private static Vec3 humanAimPoint(Minecraft client, BlockPos target) {
        VoxelShape shape = client.level.getBlockState(target).getShape(client.level, target);
        if (shape.isEmpty()) {
            return Vec3.atCenterOf(target);
        }
        var b = shape.bounds();
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        // A point he can actually SEE from here. The walk stops once the outline's middle is in sight, but a random
        // point of the outline can be behind a stair edge: 93-solve-boulder-mapdoor (2026-10-06) stood 2.2 blocks
        // below and beside the alcove's chest, aimed at a point the step hid, and gave up "could not get the crosshair
        // onto the reward chest in 60 ticks" with the chest in plain sight. Up to eight tries, else the middle, which
        // sees() already checked.
        Vec3 eye = client.player.getEyePosition();
        for (int i = 0; i < 8; i++) {
            double fx = 0.2 + 0.6 * r.nextDouble();
            double fy = 0.2 + 0.6 * r.nextDouble();
            double fz = 0.2 + 0.6 * r.nextDouble();
            Vec3 p = new Vec3(target.getX() + b.minX + (b.maxX - b.minX) * fx,
                    target.getY() + b.minY + (b.maxY - b.minY) * fy, target.getZ() + b.minZ + (b.maxZ - b.minZ) * fz);
            HitResult hr = client.level.clip(new ClipContext(eye, p.add(p.subtract(eye).normalize().scale(0.3)),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
            if (hr instanceof BlockHitResult hit && hr.getType() == HitResult.Type.BLOCK
                    && hit.getBlockPos().equals(target)) {
                return p;
            }
        }
        return aimPoint(client, target);
    }

    /** The box layer's blocks (relative y 64..66 over the whole grid), as one number: changes when a box moves. Block
     *  identity only - a pressed button changes its state, not its block. */
    private static int boxHash(Minecraft client) {
        if (cr == null || client.level == null) {
            return 0;
        }
        int h = 1;
        for (int x = 0; x <= 30; x++) {
            for (int z = 0; z <= 30; z++) {
                for (int y = FLOOR_FEET_REL; y <= FLOOR_FEET_REL + 2; y++) {
                    h = h * 31 + System.identityHashCode(
                            client.level.getBlockState(PuzzleCoords.real(x, y, z, cr)).getBlock());
                }
            }
        }
        return h == 0 ? 1 : h;
    }

    private static BoulderPath.GoalTest spotGoal(Minecraft client, BlockPos target, double reach) {
        return BoulderPath.eye(e -> BlockHits.boxDistanceSq(e, target) <= reach * reach && sees(client, e, target));
    }

    /** The middle of the block's outline (a button is a small box on a face). */
    private static Vec3 aimPoint(Minecraft client, BlockPos target) {
        VoxelShape shape = client.level.getBlockState(target).getShape(client.level, target);
        if (shape.isEmpty()) {
            return Vec3.atCenterOf(target);
        }
        return shape.bounds().getCenter().add(target.getX(), target.getY(), target.getZ());
    }

    /** Whether a ray from {@code eye} to the middle of {@code target}'s outline hits {@code target} first. */
    private static boolean sees(Minecraft client, Vec3 eye, BlockPos target) {
        Vec3 point = aimPoint(client, target);
        HitResult hr = client.level.clip(new ClipContext(eye, point.add(point.subtract(eye).normalize().scale(0.3)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
        return hr instanceof BlockHitResult hit && hr.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(target);
    }

    // ------------------------------------------------------------------ out

    private static void exit(Minecraft client, LocalPlayer player) {
        if (running) {
            runOut(client, player);
            return;
        }
        if (walking) {
            if (!runFailed && straightOut(client, player)) {
                // Up the stairs and onto the doorway's level: the rest is one straight line, run it.
                walking = false;
                LOGGER.info("[AutoPuzzles] Boulder: the rest of the way out is straight - running out from there");
                beginRun(client, player);
                return;
            }
            tickWalk(client, player);
            if (walking) {
                return;
            }
            if (stage != Stage.EXIT) {
                return;
            }
            BlockPos r = rel(player);
            if (r.getZ() <= 0) {
                finish(client, "walked out to relative z " + r.getZ() + " - as far as there is floor");
                return;
            }
        }
        if (!player.onGround()) {
            releaseKeys(client);
            LOGGER.debug("[AutoPuzzles] Boulder: way out - in the air, planning once he lands");
            return;
        }
        if (replans > MAX_REPLANS) {
            giveUp(client, "could not walk out of the room");
            return;
        }
        if (!runFailed && straightOut(client, player)) {
            beginRun(client, player);
            return;
        }
        // As far out as there is floor: the next tile first (where the live map stops calling it Boulder), then the
        // doorway gap, then the doorway itself.
        boolean avoid = mode == Mode.BUTTONS;
        for (int z : new int[]{-3, -2, -1, 0}) {
            final int limit = z;
            BoulderPath.GoalTest out = (feet, s) -> {
                BlockPos r = PuzzleCoords.relative(feet, cr);
                return r.getZ() <= limit && Math.abs(r.getX() - 15) <= 2;
            };
            if (startWalk(client, player, out, avoid, "the way out (relative z <= " + z + ")")) {
                return;
            }
        }
        giveUp(client, "no walkable way back to the doorway");
    }

    /**
     * The point the run out aims at: past the doorway gap, on the doorway's center line, at his feet height.
     *
     * <p>Why a run and not the path (killer560, 2026-10-16: "the boulder pathfinder broke going out, it didn't just run
     * in and out"). The way out from the bars is a straight line along the roof, and the walk followed it as 30 path
     * points with a 1.2-block pursuit carrot and a "more than 1.6 off the segment means pushed" test. At his sim speed
     * (600, about 1.7 blocks a tick sprinting) the carrot is less than a tick ahead and each tick's turn is an eased
     * fraction of what is left, so the steering lagged a tick behind and swung across the line wider every tick until the
     * test called it "pushed off the path" - a re-plan from a standstill, three times, then up to giving up
     * (93-solve-boulder-speed on main: 4 of 4 runs, the trace in that case's log). Nothing pushed him. Aimed at one point
     * far ahead there is nothing to swing across: the run in already worked that way, and the run out now does too.
     */
    private static Vec3 outPoint() {
        // Relative x 15 is the middle of the doorway's three columns, so its block center is the doorway's center line,
        // whichever way the room is turned.
        BlockPos o = PuzzleCoords.real(15, ROOF_FEET_REL, OUT_Z_REL, cr);
        return new Vec3(o.getX() + 0.5, o.getY(), o.getZ() + 0.5);
    }

    /**
     * Whether nothing but standable floor at his own feet height lies between him and {@link #outPoint}, wide enough for
     * his body (the center line and 0.35 either side), up to two blocks past the doorway gap - so a straight sprint
     * cannot step off a ledge, climb, or brush a wall. With the barrier roof excluded in the button mode, as every walk.
     */
    private static boolean straightOut(Minecraft client, LocalPlayer player) {
        if (cr == null || !player.onGround()) {
            return false;
        }
        double feet = player.getY();
        double roof = PuzzleCoords.real(15, ROOF_FEET_REL, 0, cr).getY();
        if (Math.abs(feet - roof) > 0.01) {
            return false;
        }
        Vec3 from = player.position();
        Vec3 to = outPoint();
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double len = Math.hypot(dx, dz);
        if (len < 0.5) {
            return true;
        }
        double ux = dx / len;
        double uz = dz / len;
        BlockPos a = PuzzleCoords.real(-3, FLOOR_FEET_REL - 4, -5, cr);
        BlockPos b = PuzzleCoords.real(33, ROOF_FEET_REL + 5, 33, cr);
        BoulderPath grid = new BoulderPath(client.level, a, b, mode == Mode.BUTTONS);
        int fy = (int) Math.floor(feet + 0.01);
        for (double s = 0; s <= len; s += 0.25) {
            double cx = from.x + ux * s;
            double cz = from.z + uz * s;
            if (PuzzleCoords.relative(BlockPos.containing(cx, feet, cz), cr).getZ() < -2) {
                break; // past the doorway: the next tile, where the finish lets go of the keys
            }
            for (double side : new double[]{-0.35, 0.0, 0.35}) {
                int x = Mth.floor(cx - uz * side);
                int z = Mth.floor(cz + ux * side);
                double h = grid.standHeight(x, fy, z);
                if (Double.isNaN(h) || Math.abs(h - feet) > 0.01) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void beginRun(Minecraft client, LocalPlayer player) {
        running = true;
        runStarted = false;
        runBest = hdist(player.position(), outPoint());
        runBestMs = System.currentTimeMillis();
        sprintStarted = false;
        LOGGER.info("[AutoPuzzles] Boulder: running out - straight to {} ({} blocks of open floor, forward and sprint "
                + "held from the moment he faces it)", AutoPuzzleUtil.fmt(BlockPos.containing(outPoint())), fmt(runBest));
        runOut(client, player);
    }

    /**
     * One tick of the run out: turn toward {@link #outPoint} at the human pace, and once roughly facing it hold forward
     * and sprint every tick until the finish (the live map no longer files him under Boulder) lets go. Discrete keys
     * only; the heading is re-aimed every tick from where he is, so a slide along a wall corrects itself.
     */
    private static void runOut(Minecraft client, LocalPlayer player) {
        runTo(client, player, outPoint());
    }

    private static void runTo(Minecraft client, LocalPlayer player, Vec3 out) {
        long now = System.currentTimeMillis();
        Vec3 eye = player.getEyePosition();
        double d = hdist(player.position(), out);
        float yaw = AutoPuzzleUtil.direction(eye, new Vec3(out.x, eye.y, out.z))[0];
        LOOK.begin("run out");
        turnToward(client, player, yaw, realCamera ? 8f : 10f);
        float err = Math.abs(Mth.wrapDegrees(yaw - player.getYRot()));
        if (!runStarted && err > RUN_START_ERR) {
            releaseKeys(client);
            runBestMs = now;
            LOGGER.debug("[AutoPuzzles] Boulder: run out - turning first, yaw error {}", err);
            return;
        }
        if (!runStarted) {
            LOGGER.info("[AutoPuzzles] Boulder: run out - facing it (yaw error {}), forward and sprint down, {} to go (tick {})",
                    fmt(err), fmt(d), tick());
        }
        runStarted = true;
        if (d < 0.6) {
            // On the aim point and the live map still says Boulder: stand, the finish comes with the map.
            releaseKeys(client);
            waitFor("on the run-out point " + fmt(out) + " - the live map still files him under Boulder");
            return;
        }
        client.options.keyUp.setDown(true);
        client.options.keySprint.setDown(true);
        if (d < runBest - 0.25) {
            runBest = d;
            runBestMs = now;
        } else if (now - runBestMs > 1500L) {
            running = false;
            runFailed = true;
            releaseKeys(client);
            replans++;
            LOGGER.info("[AutoPuzzles] Boulder: the run out made no headway for 1.5 s ({} from the way out) - walking "
                    + "it instead", fmt(d));
        }
    }

    // ------------------------------------------------------------------ walking

    /** Plans with a fresh grid (the boxes move) and starts following it. @return false if no path */
    private static boolean startWalk(Minecraft client, LocalPlayer player, BoulderPath.GoalTest goal,
                                     boolean avoidBarrier, String label) {
        BlockPos a = PuzzleCoords.real(-3, FLOOR_FEET_REL - 4, -5, cr);
        BlockPos b = PuzzleCoords.real(33, ROOF_FEET_REL + 5, 33, cr);
        BoulderPath p = new BoulderPath(client.level, a, b, avoidBarrier);
        if (!p.plan(player.position(), goal)) {
            LOGGER.info("[AutoPuzzles] Boulder: no walk to {} ({} nodes searched{}) from {} - {} (tick {})", label,
                    p.expanded(), avoidBarrier ? ", barrier roof excluded" : "", fmt(player.position()), p.failure(), tick());
            return false;
        }
        path = p;
        pts = simplify(p, p.points());
        ptIdx = 0;
        walking = true;
        walkLabel = label;
        walkGoal = goal;
        walkAvoidBarrier = avoidBarrier;
        walkStartMs = System.currentTimeMillis();
        aimTicks = 0;
        sprintAll = !realCamera && stage == Stage.EXIT;
        sprintStarted = false;
        double len = p.length(player.position());
        walkTimeoutMs = 3000L + (long) (len / 3.0 * 1000.0);
        bestRemaining = Double.MAX_VALUE;
        bestAtMs = walkStartMs;
        int drops = 0;
        double lowest = Double.MAX_VALUE;
        for (int i = 1; i < pts.size(); i++) {
            if (pts.get(i).y < pts.get(i - 1).y - 0.6) {
                drops++;
            }
            lowest = Math.min(lowest, pts.get(i).y);
        }
        StringBuilder corners = new StringBuilder();
        for (int i = 0; i < Math.min(pts.size(), 12); i++) {
            Vec3 v = pts.get(i);
            corners.append(i == 0 ? "" : " ").append(String.format(Locale.US, "%.1f/%.1f/%.1f", v.x, v.y, v.z));
        }
        LOGGER.info("[AutoPuzzles] Boulder: walking to {} at {} - {} step(s), {} blocks, {} step(s) down, lowest feet y {}"
                        + " ({} point(s) once pulled tight: {}); from {}, timeout {} ms, re-plan {}/{}, barrier {} (tick {})",
                label, AutoPuzzleUtil.fmt(p.goal()), p.points().size(),
                fmt(len), drops, pts.isEmpty() ? "-" : fmt(lowest), pts.size(), corners, fmt(player.position()),
                walkTimeoutMs, replans, MAX_REPLANS, avoidBarrier ? "avoided" : "allowed", tick());
        return true;
    }

    /** One tick of following the planned path: turn smoothly toward a point a little ahead and hold forward. */
    private static void tickWalk(Minecraft client, LocalPlayer player) {
        if (!walking) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - walkStartMs > walkTimeoutMs) {
            stopWalk(client, "walk timed out after " + walkTimeoutMs + " ms");
            replanOrFail(client, player);
            return;
        }
        Vec3 p = player.position();
        // Blocks a tick: at his sim speed (600) a sprint covers ~1.7 a tick, and every distance below that was tuned for
        // a walk (0.2) scales with it.
        double speed = Math.hypot(player.getDeltaMovement().x, player.getDeltaMovement().z);
        // Advance past points he has reached; skip ahead to a nearer later one (a corner he cut).
        // A stair point is only passed by standing on it: taken early from the floor beside it, the next step is aimed at
        // across the stair's side and he walks off it (2026-10-16, a fall off the side of the stairs and 30 ticks into
        // their wall).
        while (ptIdx < pts.size() - 1 && (stairPoint(ptIdx)
                ? hdist(p, pts.get(ptIdx)) < 0.45 && Math.abs(p.y - pts.get(ptIdx).y) < 0.6
                : hdist(p, pts.get(ptIdx)) < Math.max(0.75, speed) || passed(p, ptIdx))) {
            ptIdx++;
        }
        for (int j = ptIdx + 1; j < Math.min(pts.size(), ptIdx + 4); j++) {
            if (hdist(p, pts.get(j)) < hdist(p, pts.get(ptIdx)) && Math.abs(pts.get(j).y - p.y) < 0.7
                    && (path == null || clearLine(path, new Vec3(p.x, pts.get(j).y, p.z), pts.get(j)))) {
                ptIdx = j;
            }
        }
        Vec3 last = pts.get(pts.size() - 1);
        double remaining = hdist(p, pts.get(ptIdx));
        for (int i = ptIdx + 1; i < pts.size(); i++) {
            remaining += hdist(pts.get(i - 1), pts.get(i));
        }
        if (ptIdx == pts.size() - 1 && (hdist(p, last) < 0.35 || passed(p, ptIdx)) && Math.abs(p.y - last.y) < 0.7) {
            stopWalk(client, "arrived");
            return;
        }
        // Off the path = off every segment near where he is, not only the one he is heading along: a fast walk cuts a
        // corner onto the next segment a tick before the point index catches up.
        double off = Double.MAX_VALUE;
        for (int i = Math.max(1, ptIdx); i < Math.min(pts.size(), ptIdx + 4); i++) {
            off = Math.min(off, offSegment(p, pts.get(i - 1), pts.get(i)));
        }
        if (player.onGround() && ptIdx > 0 && off > 1.6) {
            stopWalk(client, "pushed off the path");
            replanOrFail(client, player);
            return;
        }
        if (remaining < bestRemaining - 0.25) {
            bestRemaining = remaining;
            bestAtMs = now;
        } else if (now - bestAtMs > 1500L) {
            stopWalk(client, "no headway for 1.5 s");
            replanOrFail(client, player);
            return;
        }
        // Pure pursuit: a carrot 1.2 blocks along the path from the point he is heading for - and at least three ticks
        // of travel ahead, or at his sim speed it sits under a tick away and the eased turn swings across the line. The
        // path is already pulled tight (simplify), so a point left in it is a real corner, and the carrot never goes
        // round one: aimed past a corner he would cut it into the box.
        Vec3 carrot = pts.get(ptIdx);
        double budget = Math.max(1.2, Math.min(4.0, speed * 3.0)) - hdist(p, carrot);
        for (int i = ptIdx + 1; i < pts.size() && budget > 0; i++) {
            Vec3 a = pts.get(i - 1);
            Vec3 b = pts.get(i);
            if (Math.abs(b.y - a.y) > 0.6) {
                break; // never aim past a change of height - take the step square on
            }
            if (turnAt(i - 1) > CORNER_DEG) {
                break; // never aim round a corner
            }
            double seg = hdist(a, b);
            if (seg >= budget) {
                carrot = new Vec3(a.x + (b.x - a.x) * budget / seg, b.y, a.z + (b.z - a.z) * budget / seg);
                budget = 0;
            } else {
                carrot = b;
                budget -= seg;
            }
        }
        // Eyes on the ground a few blocks ahead, as a walker's are.
        Vec3 ahead = pts.get(Math.min(pts.size() - 1, ptIdx + 3));
        double aheadDist = Math.max(2.0, hdist(p, ahead));
        float pitch = (float) Mth.clamp(Math.toDegrees(Math.atan2(player.getEyeY() - (ahead.y + 0.3), aheadDist)),
                -5.0, 40.0);
        Vec3 eye = player.getEyePosition();
        float yaw = AutoPuzzleUtil.direction(eye, new Vec3(carrot.x, eye.y, carrot.z))[0];
        if (aimFor != null && remaining < 3.0) {
            // The last few blocks: eyes already coming onto what he is walking to (pitch only - the yaw steers).
            pitch = AutoPuzzleUtil.direction(eye, aimPoint(client, aimFor))[1];
        }
        LOOK.begin("walk " + walkLabel);
        turnToward(client, player, yaw, pitch);
        float err = Math.abs(Mth.wrapDegrees(yaw - player.getYRot()));
        if (sprintAll) {
            // The Secret Aura run back out: turn round where he stands (W held against the bars would end a sprint
            // on the collision), then forward and sprint every tick until he is out.
            if (!sprintStarted && err > 20f) {
                releaseKeys(client);
                bestAtMs = now;
                LOGGER.debug("[AutoPuzzles] Boulder: aura run back - turning round first, yaw error {}", err);
                return;
            }
            if (!sprintStarted) {
                LOGGER.info("[AutoPuzzles] Boulder: walk to {} - facing it (yaw error {}), forward and sprint every tick"
                        + " (tick {})", walkLabel, fmt(err), tick());
            }
            sprintStarted = true;
            client.options.keyUp.setDown(true);
            client.options.keySprint.setDown(true);
            return;
        }
        // Forward only while he is slower than his heading error allows: square on, any speed; a turn under way, a
        // walk's pace; further off, he turns where he stands. At a vanilla sprint (0.28) only the last rule ever bites,
        // which is the old "forward within 55 degrees"; at his sim speed (1.7) the others stop him overshooting a point
        // and circling it while the eased turn catches up (93-solve-boulder-speed-buttons, 2026-10-16: 25 ticks circling
        // the stair top, yaw -27 to -709).
        double allowed = err < 15f ? Double.MAX_VALUE : err < 35f ? 0.6 : err < 55f ? 0.3 : 0.0;
        if (stairPoint(ptIdx) && err >= 12f) {
            allowed = 0.0; // a stair is one block wide: square on to it before the step
        }
        if (remaining < 0.8 && err >= 20f) {
            allowed = 0.0;
        }
        boolean coast = mustCoast(p, speed);
        boolean forward = speed < allowed && !coast;
        // Fast (more than twice a vanilla walk's attribute; his sim's 600 is 0.6): one tick of W alone carries him ~1.7
        // blocks, so in the box maze a corner, a one-wide stair or the end cannot be met by letting go of W - he slid
        // past corners, fell off the side of the stairs and pressed into their wall (93-solve-boulder-speed-buttons,
        // 2026-10-16: 0 of 4 runs with sprint-and-coast, every one re-planned). So a fast walk on the path is a SNEAKING
        // walk, ~0.4 a tick (8 blocks a second, still faster than a vanilla sprint), which the follower holds like a
        // vanilla sprint and which keeps him on a stair's edge as it does a player. The sprint key stays held. The run
        // out at the end (runOut) is a real sprint. Discrete keys; at a vanilla speed nothing here changes.
        boolean fast = player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED) > 0.2;
        boolean creeping = fast && allowed > 0.0;
        if (creeping) {
            // A turn of more than 25 degrees is mostly made standing: at ~0.4 a tick the eased turn would otherwise carry
            // him a block and a half past a corner (2026-10-16, the first corner off the doorway, 1.7 off the line).
            forward = err < 25f || err < 55f && speed < 0.15;
        }
        LOGGER.debug("[AutoPuzzles] Boulder: walk tick - point {} of {}, {} left, speed {}, yaw error {}, forward {},"
                + " coast {}, creep {}", ptIdx, pts.size(), remaining, speed, err, forward, coast, creeping);
        creep(client, creeping || fast && sneakByUs && !player.onGround());
        client.options.keyUp.setDown(forward);
        // Sprinting whenever he walks (killer560, 2026-10-15: "make sure that it is sprinting not walking"); it was
        // only on a long straight, so the stair walk and every short leg in button mode went at walking pace.
        client.options.keySprint.setDown(forward);
    }

    private static void replanOrFail(Minecraft client, LocalPlayer player) {
        if (++replans > MAX_REPLANS) {
            giveUp(client, "could not walk to " + walkLabel + " after " + MAX_REPLANS + " re-plan(s)");
            return;
        }
        LOGGER.info("[AutoPuzzles] Boulder: re-plan {}/{} to {} from {}{}", replans, MAX_REPLANS, walkLabel,
                fmt(player.position()), walkGoal != null && player.onGround() ? "" : " - not now ("
                        + (walkGoal == null ? "no goal" : "in the air") + "), the stage plans again");
        if (walkGoal != null && player.onGround()) {
            startWalk(client, player, walkGoal, walkAvoidBarrier, walkLabel);
        }
    }

    private static void stopWalk(Minecraft client, String why) {
        if (!walking) {
            return;
        }
        walking = false;
        releaseKeys(client);
        if (why != null) {
            LOGGER.info("[AutoPuzzles] Boulder: stopped walking to {} - {} (at {}, point {} of {}, {} ms into the walk,"
                            + " tick {})", walkLabel, why, Minecraft.getInstance().player == null ? "-"
                            : fmt(Minecraft.getInstance().player.position()), ptIdx, pts.size(),
                    System.currentTimeMillis() - walkStartMs, tick());
        }
        if ("arrived".equals(why) || (why != null && why.endsWith("in reach"))) {
            replans = 0;
        }
    }

    private static double hdist(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    /** A turn in the path sharper than this at a point makes it a corner: the carrot stops there and he slows for it. */
    private static final double CORNER_DEG = 20.0;
    /** Ground speed (blocks a tick) he may carry into a corner of up to 50 degrees, a sharper one, a change of height
     *  (a stair) and the end of the walk. A sprint at his sim speed is ~1.7; a vanilla sprint 0.28. */
    private static final double V_BEND = 0.8;
    private static final double V_CORNER = 0.45;
    private static final double V_STEP = 0.5;
    private static final double V_END = 0.3;

    /**
     * The planner's cell-by-cell path pulled tight: from each kept point, the furthest later one reachable in a straight
     * line on the same level with room for his body (the line and 0.35 either side standable at that height), so what
     * is left is the real corners and every change of height. On a cell path at his sim speed every grid step read as a
     * turn - the steering zig-zagged and pressed into box corners (93-solve-boulder-speed-buttons, 2026-10-16).
     */
    private static List<Vec3> simplify(BoulderPath grid, List<Vec3> raw) {
        if (raw.size() < 3) {
            return raw;
        }
        List<Vec3> out = new java.util.ArrayList<>();
        out.add(raw.get(0));
        int i = 0;
        while (i < raw.size() - 1) {
            int j = i + 1;
            for (int k = raw.size() - 1; k > i + 1; k--) {
                if (level(raw, i, k) && clearLine(grid, raw.get(i), raw.get(k))) {
                    j = k;
                    break;
                }
            }
            out.add(raw.get(j));
            i = j;
        }
        return out;
    }

    private static boolean level(List<Vec3> raw, int from, int to) {
        for (int k = from + 1; k <= to; k++) {
            if (Math.abs(raw.get(k).y - raw.get(from).y) > 0.01) {
                return false;
            }
        }
        return true;
    }

    /** Standable at {@code a}'s height all the way to {@code b}, on the line and 0.35 either side of it. */
    private static boolean clearLine(BoulderPath grid, Vec3 a, Vec3 b) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) {
            return true;
        }
        double ux = dx / len;
        double uz = dz / len;
        int fy = (int) Math.floor(a.y + 0.01);
        for (double s = 0; s <= len; s += 0.25) {
            double cx = a.x + ux * s;
            double cz = a.z + uz * s;
            for (double side : new double[]{-0.35, 0.0, 0.35}) {
                double h = grid.standHeight(Mth.floor(cx - uz * side), fy, Mth.floor(cz + ux * side));
                if (Double.isNaN(h) || Math.abs(h - a.y) > 0.01) {
                    return false;
                }
            }
        }
        return true;
    }

    /** A point where the path changes height, coming in or going on: a stair step. */
    private static boolean stairPoint(int k) {
        return k + 1 < pts.size() && Math.abs(pts.get(k + 1).y - pts.get(k).y) > 0.01
                || k > 0 && Math.abs(pts.get(k).y - pts.get(k - 1).y) > 0.01;
    }

    /** Degrees the path turns at point {@code k} (0 at either end). */
    private static double turnAt(int k) {
        if (k <= 0 || k >= pts.size() - 1) {
            return 0.0;
        }
        Vec3 a = pts.get(k - 1);
        Vec3 b = pts.get(k);
        Vec3 c = pts.get(k + 1);
        double h1 = Math.atan2(b.z - a.z, b.x - a.x);
        double h2 = Math.atan2(c.z - b.z, c.x - b.x);
        return Math.abs(Mth.wrapDegrees(Math.toDegrees(h2 - h1)));
    }

    /** The speed he may carry into point {@code k}, or MAX_VALUE where it does not matter. */
    private static double speedAt(int k) {
        if (k >= pts.size() - 1) {
            return V_END;
        }
        if (Math.abs(pts.get(k + 1).y - pts.get(k).y) > 0.01 || k > 0 && Math.abs(pts.get(k).y - pts.get(k - 1).y) > 0.01) {
            return V_STEP;
        }
        double t = turnAt(k);
        return t <= CORNER_DEG ? Double.MAX_VALUE : t <= 50.0 ? V_BEND : V_CORNER;
    }

    /**
     * Whether to let go of forward this tick: a corner, a stair or the end lies closer than he can slow down for it by
     * coasting (on the ground he keeps 0.546 of his speed a tick, so dropping from {@code v} to {@code v0} takes about
     * {@code 1.2 * (v - v0)} blocks), so he coasts into it as a player taps W - discrete keys only.
     */
    private static boolean mustCoast(Vec3 p, double speed) {
        double along = hdist(p, pts.get(ptIdx));
        for (int k = ptIdx; k < pts.size() && along < 8.0; k++) {
            double v = speedAt(k);
            if (v < speed && along < 1.2 * (speed - v) + speed) {
                return true;
            }
            if (k + 1 < pts.size()) {
                along += hdist(pts.get(k), pts.get(k + 1));
            }
        }
        return false;
    }

    /** Whether he is past point {@code i} along the path's direction there (the segment into it, or out of it at the
     *  start): a sprint overshoots a 0.35 arrival circle between two ticks. */
    private static boolean passed(Vec3 p, int i) {
        Vec3 at = pts.get(i);
        Vec3 from = i > 0 ? pts.get(i - 1) : null;
        if (from == null || hdist(from, at) < 1e-6 || Math.abs(at.y - from.y) > 0.6) {
            return false;
        }
        double dx = at.x - from.x;
        double dz = at.z - from.z;
        double len = Math.hypot(dx, dz);
        double lateral = Math.abs((p.x - from.x) * dz - (p.z - from.z) * dx) / len;
        return ((p.x - at.x) * dx + (p.z - at.z) * dz) > 0.0 && lateral < 1.2 && Math.abs(p.y - at.y) < 0.7;
    }

    private static double offSegment(Vec3 p, Vec3 a, Vec3 b) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        double len2 = dx * dx + dz * dz;
        if (len2 < 1e-6) {
            return hdist(p, a);
        }
        double t = Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.z - a.z) * dz) / len2));
        return Math.hypot(p.x - (a.x + dx * t), p.z - (a.z + dz * t));
    }

    // ------------------------------------------------------------------ rotation and keys

    /**
     * One human step toward (yaw, pitch). Secret Aura off: his REAL rotation, camera and all - he actually looks
     * where he walks and at what he clicks (killer560: "it needs to have the player actually looking at the button").
     * Secret Aura's run: the body through {@link BodyAim}, camera held.
     */
    private static void turnToward(Minecraft client, LocalPlayer player, float yaw, float pitch) {
        float[] next = LOOK.step(client, player.getYRot(), player.getXRot(), yaw, pitch);
        beginStep(player, next[0], next[1], !realCamera);
    }

    // ------------------------------------------------------------------ the rotation, drawn per frame
    //
    // killer560 (2026-10-06): "The boulder turning is really choppy." Each tick's turn (up to 28 degrees) used to be
    // written at once, so at a high frame rate the camera sat still for several frames and then jumped. Now a tick's
    // step is only PLANNED in the tick (beginStep); every render frame (frame) draws the rotation part-way from where
    // the step started to where it ends, by how far through the tick that frame is; and the next tick first finishes
    // the step exactly (finishStep). So the camera moves every frame by one frame's share of a step; each tick's
    // movement packet carries a rotation that was fully drawn, a whole number of mouse counts on from the one before
    // (HumanLook); and a click - sent at the START of a tick, before that tick's movement packet - goes out only when
    // the ray from the rotation the LAST packet reported hits the target as well as the one now on screen.

    private static boolean stepping = false;
    private static boolean stepBody = false;
    private static float fromYaw;
    private static float fromPitch;
    private static float toYaw;
    private static float toPitch;
    private static long stepStartNanos = 0L;
    /** His rotation when this hook ended last tick - what that tick's movement packet reported. */
    private static float sentYaw = Float.NaN;
    private static float sentPitch = Float.NaN;
    /** Render frames that drew a rotation part-way through a step (for the testkit's smoothness check). */
    private static long partialFrames = 0L;
    /** How long a client tick has been taking (smoothed), so a step is drawn over the tick it really spans. */
    private static long tickNanos = 50_000_000L;
    private static long lastTickStartNanos = 0L;
    private static final double DRAW_FRACTION = 0.8;

    private static void beginStep(LocalPlayer player, float yaw, float pitch, boolean body) {
        if (yaw == player.getYRot() && pitch == player.getXRot()) {
            return;
        }
        if (body) {
            BODY.turn(player, player.getYRot(), player.getXRot()); // camera held from here; nothing moves yet
        }
        fromYaw = player.getYRot();
        fromPitch = player.getXRot();
        toYaw = yaw;
        toPitch = Mth.clamp(pitch, -90f, 90f);
        stepBody = body;
        stepStartNanos = System.nanoTime();
        stepping = true;
    }

    /** Start of a tick: land the step the frames have been drawing, exactly where it was planned to end. */
    private static void finishStep(LocalPlayer player) {
        if (!stepping) {
            return;
        }
        stepping = false;
        if (player == null) {
            return;
        }
        if (stepBody) {
            BODY.turn(player, toYaw, toPitch);
        } else {
            player.setYRot(toYaw);
            player.setYHeadRot(toYaw);
            player.setXRot(toPitch);
        }
    }

    /** The rotation {@link #frame} draws {@code nanos} into the current step, or null when not stepping. */
    public static float[] rotationAt(long nanos) {
        if (!stepping) {
            return null;
        }
        // Drawn over 80% of a tick: a tick that comes a little early then finds the step already drawn, rather than
        // landing the rest of it in one frame (a late one just holds the end for a frame or two).
        double p = Mth.clamp((nanos - stepStartNanos) / (tickNanos * DRAW_FRACTION), 0.0, 1.0);
        return new float[]{(float) (fromYaw + (toYaw - fromYaw) * p), (float) (fromPitch + (toPitch - fromPitch) * p)};
    }

    /** Every render frame (AutoPuzzlesFeature's level-render hook). */
    public static void frame() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (!stepping || player == null) {
            return;
        }
        long now = System.nanoTime();
        float[] r = rotationAt(now);
        player.setYRot(r[0]);
        player.setYHeadRot(r[0]);
        player.setXRot(r[1]);
        long into = now - stepStartNanos;
        if (into > 0L && into < tickNanos * DRAW_FRACTION) {
            partialFrames++;
        }
    }

    public static long partialFrames() {
        return partialFrames;
    }

    /** The step being drawn, {fromYaw, fromPitch, toYaw, toPitch, startNanos, drawNanos}, or null - for the testkit. */
    public static double[] currentStep() {
        return stepping ? new double[]{fromYaw, fromPitch, toYaw, toPitch, stepStartNanos, tickNanos * DRAW_FRACTION}
                : null;
    }

    /**
     * When nothing is driving him: if the body is still turned away from his held view, turn it back at the same
     * human pace, and release the camera once they agree - so the end of a room is not a snap.
     */
    private static void settleBody(Minecraft client, LocalPlayer player) {
        if (player == null || !BODY.isHeld()) {
            return;
        }
        float vy = ViewFreeze.viewYaw();
        float vp = ViewFreeze.viewPitch();
        if (Float.isNaN(vy)) {
            BODY.tick(player, false);
            return;
        }
        float dy = Math.abs(Mth.wrapDegrees(vy - player.getYRot()));
        float dp = Math.abs(vp - player.getXRot());
        if (dy <= 1.5f && dp <= 1.5f) {
            BODY.tick(player, false);
            return;
        }
        LOOK.begin("settle");
        float[] next = LOOK.step(client, player.getYRot(), player.getXRot(), vy, vp);
        BODY.tick(player, true);
        beginStep(player, next[0], next[1], true);
    }

    private static void releaseKeys(Minecraft client) {
        client.options.keyUp.setDown(false);
        client.options.keySprint.setDown(false);
        creep(client, false);
    }

    /** Sneak held by this auto for a slow stretch (see tickWalk); a sneak he holds himself is never touched. */
    private static boolean sneakByUs = false;

    private static void creep(Minecraft client, boolean on) {
        if (on && !sneakByUs && !client.options.keyShift.isDown()) {
            sneakByUs = true;
            client.options.keyShift.setDown(true);
        } else if (!on && sneakByUs) {
            sneakByUs = false;
            client.options.keyShift.setDown(false);
        }
    }

    private static KeyMapping[] ownKeys(Minecraft client) {
        return new KeyMapping[]{client.options.keyDown, client.options.keyLeft, client.options.keyRight,
                client.options.keyJump};
    }

    /** The name of a movement key he pressed since it started (one already held then counts once released). */
    private static String takeover(Minecraft client) {
        KeyMapping[] keys = ownKeys(client);
        String[] names = {"back", "left", "right", "jump"};
        for (int i = 0; i < keys.length; i++) {
            boolean down = keys[i].isDown();
            if (!down) {
                keyArmed[i] = true;
            } else if (keyArmed[i]) {
                return names[i];
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ bookkeeping

    private static void finish(Minecraft client, String why) {
        stopWalk(client, null);
        releaseKeys(client);
        LOGGER.info("[AutoPuzzles] Boulder: finished - {} ({} s after the start, {} press(es), mode {}, tick {})", why,
                fmt((System.currentTimeMillis() - engagedMs) / 1000.0), presses, mode, tick());
        stage = Stage.DONE;
        // Its last step is over: Auto Secret, Autopilot and Auto Routes carry on now, not AutoPuzzleActivity's 40-tick
        // grace later (his 2026-10-16 log: "Auto Boulder is done - carrying on" 2 s after "finished").
        AutoPuzzleActivity.ended();
    }

    private static void advance(Stage next, String why) {
        LOGGER.info("[AutoPuzzles] Boulder: {} -> {} ({}) after {} ms in {}, {} s since the start (tick {})", stage, next, why,
                stageStartMs == 0L ? 0 : System.currentTimeMillis() - stageStartMs, stage,
                fmt((System.currentTimeMillis() - engagedMs) / 1000.0), tick());
        loggedBarsForward = null;
        stage = next;
        stageWindows = com.killer560.hub.util.ChestWindows.count();
        stageStartMs = System.currentTimeMillis();
        stageLag = com.killer560.hub.util.ServerLag.mark();
        loggedWait = null;
        replans = 0;
        stalledTicks = 0;
        running = false;
        runFailed = false;
    }

    private static void giveUp(Minecraft client, String why) {
        stopWalk(client, null);
        releaseKeys(client);
        LOGGER.info("[AutoPuzzles] Boulder: stopped for this room - {} (stage {}, mode {}, {} s after the start, {} press(es),"
                        + " at {}, tick {})", why, stage, mode, fmt((System.currentTimeMillis() - engagedMs) / 1000.0), presses,
                Minecraft.getInstance().player == null ? "-" : fmt(Minecraft.getInstance().player.position()), tick());
        AutoPuzzleActivity.ended();
        ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Auto Boulder: "), ModChat.bad(why), ModChat.text("."));
        stage = Stage.DONE;
    }

    private static void waitFor(String why) {
        if (!why.equals(loggedWait)) {
            loggedWait = why;
            LOGGER.info("[AutoPuzzles] Boulder: waiting - {} (stage {}, tick {})", why, stage, tick());
        }
    }

    /** Back to idle. {@code why} null: silently. */
    private static void disengage(Minecraft client, String why) {
        if (engaged && why != null) {
            LOGGER.info("[AutoPuzzles] Boulder: stopped - {} (stage {}, {} s after the start, tick {})", why, stage,
                    fmt((System.currentTimeMillis() - engagedMs) / 1000.0), tick());
        }
        loggedBarsForward = null;
        loggedNextButton = null;
        if (walking || engaged) {
            walking = false;
            running = false;
            if (client != null && client.options != null) {
                releaseKeys(client);
            }
        }
        engaged = false;
        stage = Stage.NEED_CHEST;
        mode = Mode.BUTTONS;
        cr = null;
        chestReal = null;
        stageStartMs = 0L;
        loggedWait = null;
        noChestWarned = false;
        unloadedWarned = false;
        scanCooldown = 0;
        sawContainer = false;
        presses = 0;
        lastPressMs = 0L;
        buttonMissingSinceMs = 0L;
        pushStartMs = 0L;
        sawSolution = false;
        correction = false;
        path = null;
        pts = List.of();
        ptIdx = 0;
        walkLabel = null;
        walkGoal = null;
        replans = 0;
        aimTicks = 0;
        aimFor = null;
        stalledTicks = 0;
        lastPlanFailMs = 0L;
        planFailSinceMs = 0L;
        runFailed = false;
        runStarted = false;
        boxHashAtPress = 0;
        realCamera = false;
        aimStartMs = 0L;
        aimPointFor = null;
        aimPt = null;
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    private static String fmt(Vec3 v) {
        return v == null ? "null" : String.format(Locale.US, "%.2f,%.2f,%.2f", v.x, v.y, v.z);
    }

    /** The session tick, for log lines. */
    private static long tick() {
        return com.killer560.hub.util.SessionRecorderSink.tick();
    }

    /** What {@link #mapArrivalAtDoor} last logged for a landing it did not start on; reset when the window opens. */
    private static String loggedDoor = null;

    private static void doorNote(String why) {
        if (!why.equals(loggedDoor)) {
            loggedDoor = why;
            LOGGER.info("[AutoPuzzles] Boulder: landing not started on - {} (tick {})", why, tick());
        }
    }
}
