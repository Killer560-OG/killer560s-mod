package com.killer560.hub.autopuzzles;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.livemap.autoclear.AutoClearUtils;
import com.killer560.hub.puzzlesolvers.PuzzleCoords;
import com.killer560.hub.puzzlesolvers.TeleportMazeSolverConfig;
import com.killer560.hub.puzzlesolvers.TeleportMazeSolverFeature;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ViewFreeze;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.killer560.hub.compat.McCompat;

/**
 * Auto Teleport Maze - port of QUOI {@code TeleportMazeSolver.kt}'s {@code auto}, which IS movement automation: once
 * the first maze teleport has happened (you step on the start pad yourself), one tick after every maze teleport it
 * picks the pad to use in the current cell ({@link MazeRoute} since 2026-10-06: the exit pad, or a way to it over links
 * already taken, else QUOI's exploring order - best, unvisited diagonal, farthest - else a known way to a chamber with
 * a pad still untried), turns the camera to face that pad's centre and holds the forward key until the next teleport. Nothing
 * beyond QUOI: no pathing, jumping or strafing. Stops when a screen opens, when there is no pad to go to (the end),
 * when you leave the room, and (safety addition) after 3 seconds of walking without a teleport.
 * <p>
 * killer560, 2026-09-27: "For auto tp maze make it also aura the chest once it detects it stepped on the final
 * correct pad, then have it walk to the exit pad and walk out of the room as well." {@link TeleportMazeSolverFeature}
 * tracks exactly two pads outside every cell - "end" (relative 15,69,14) and "start" (relative 15,69,12), the same
 * fixed layout QUOI's own solver uses - so "the final correct pad" is detected the moment {@code getPad} would
 * normally report "no pad to go to" (the existing stop condition) AND the landing pad is that "end" pad specifically.
 * From there this walks (plain forward-key + camera turn, same primitive already used above for pad-to-pad hops -
 * both "end" and "start" sit on the maze's own starting platform, not across a void gap, so no etherwarp/pathfinder
 * is needed or appropriate here - {@code AutoClearUtils.canPath} already refuses to path ANY "Maze" room for exactly
 * that reason). Since 2026-10-19 the finish walks toward the centre cell's chest, clicking it with its own deliberate
 * interact (not {@code SecretAuraFeature} - works even with Secret Aura off) once he is in that cell and in reach, until
 * the server confirms the click; then walks onto the only pad in that cell (the "end" pad, which sends him out), then
 * to this room's own doorway-side spot ({@link AutoClearUtils#roomOverride}, the same one "Interactive Map" uses to
 * walk a player INTO this room) so an etherwarp can be used again right after. See {@link #tickFinish}.
 */
final class AutoTeleportMaze {

    private static final Logger LOGGER = ModLog.get("killer560smod-autopuzzles");
    private static final String ROOM = "Teleport Maze";
    private static final long WALK_TIMEOUT_MS = 3000L;
    private static final long FINISH_WALK_TIMEOUT_MS = 5000L;
    /** The measured block reach, squared - was 36.0 (6.0 blocks) measured to the centre. */
    private static final double AURA_REACH_SQ = AutoPuzzleUtil.BLOCK_REACH_SQ;
    private static final double ARRIVE_SQ = 2.25; // 1.5 blocks - "close enough", same feel as QUOI's own pad hops
    // Same fixed relative layout TeleportMazeSolverFeature's own PADS array carries ("end"/"start", the two pads
    // outside every cell) - copied, not re-derived, so this can never disagree with what that solver detected.
    private static final int[] END_PAD_RELATIVE = {15, 69, 14};
    private static final int[] START_PAD_RELATIVE = {15, 69, 12};

    private static final AutoGuard GUARD = new AutoGuard("Auto Teleport Maze", "Teleport Maze Solver");

    private static int lastSeq = -1;
    /** Teleports this visit; past {@link #MAX_HOPS} the auto gives up (re-taking diagonals must end somewhere). */
    private static int hops = 0;
    private static final int MAX_HOPS = 60;
    private static int pendingTicks = -1;
    private static boolean walking = false;
    private static long walkStartMs = 0L;
    private static long walkTimeoutMs = WALK_TIMEOUT_MS;
    private static BlockPos walkTarget = null;
    /** True when the grid found no way round and the walk is the old straight one. */
    private static boolean walkStraight = false;
    private static final MazeWalk WALK = new MazeWalk();
    /** Blocks a second, walking - a little under a player's 4.3 so the timeout is generous. */
    private static final double WALK_SPEED = 3.5;
    /** The last Interactive Map arrival this auto has acted on (or chosen to ignore). */
    private static int consumedArrival = -1;
    /** Walking onto the start pad after a map arrival. */
    private static boolean startWalking = false;
    /** The teleport count when that walk began, or -1. */
    private static int startSeq = -1;
    private static boolean wasInRoom = false;

    private enum FinishStage { NONE, CHEST, STEP_ON_END_PAD, WALK_OUT, DONE }

    /** Further than this from the end pad in one tick and the pad has sent him out. */
    private static final double SENT_OUT_SQ = 36.0;
    /** The centre chamber's interior, database-relative (walls at x 11/19, z 13/21; SIM.md) - the last cell. The
     *  end pad (15,69,14) is the only pad in it. */
    private static final int[] CENTRE_MIN_RELATIVE = {12, 68, 14};
    private static final int[] CENTRE_MAX_RELATIVE = {18, 71, 20};
    /** How long the walk to the chest may go without the server confirming a click before the auto stops. */
    private static final long CHEST_TIMEOUT_MS = 8000L;
    /** Between clicks at the chest while its answer has not come; and how many are sent at most. */
    private static final long CHEST_RECLICK_MS = 500L;
    private static final int MAX_CHEST_CLICKS = 4;
    /** Horizontal distance (squared) inside which the walk stops pushing into the chest and only waits. */
    private static final double CHEST_STOP_SQ = 1.69;
    /** The chest in the centre cell, once found. */
    private static BlockPos finishChest = null;
    /** {@code ChestWindows.count} when the final step began: a chest window after it is this chest's answer. */
    private static int chestWindowMark = 0;
    private static long lastChestClickMs = 0L;
    private static int chestClicks = 0;
    private static boolean chestMissingLogged = false;

    /**
     * Whether the auto is running, and so holding the free camera.
     *
     * <p>killer560 (2026-10-04): "make the auto solver for it put me in that freecam state" - the same
     * {@link ViewFreeze} hold every other puzzle auto uses. {@link AutoPuzzleUtil#rotateCamera} only took a lease
     * per pad hop, and the lease is 400 ms against walks of up to 3 s, so the camera was handed back mid-walk and
     * every teleport's turn and every re-aim whipped it round. Held every tick from the first maze teleport until
     * the auto stops or finishes, then released - since 2026-10-06 through {@link FreeCam}, which turns the body
     * back under the held view before letting go (it used to just drop the hold, so the camera jumped onto the
     * body's last walk direction at the end).
     */
    private static final FreeCam CAMERA = new FreeCam("TeleportMaze");
    /** His view on the tick before, so the hold starts from where he was looking BEFORE the first teleport
     *  turned him, not after. */
    private static float lastYaw = Float.NaN;
    private static float lastPitch = Float.NaN;

    private static FinishStage finishStage = FinishStage.NONE;
    private static long finishStageStartMs = 0L;
    /** Where he stood last tick while stepping on the end pad: a jump of more than 1.5 blocks in one tick is the pad's teleport. */
    private static Vec3 finishLastPos;
    /** util/ServerLag readings taken with finishStageStartMs and walkStartMs: a server stall never runs a wait for a
     *  pad's teleport out (2026-10-15). */
    private static long finishStageLag = 0L;
    private static long walkLag = 0L;

    // Logging only: tick stamps for elapsed-tick values, and the last reason a held state was logged.
    private static long walkStartTick = 0L;
    private static long finishStageStartTick = 0L;
    private static String lastHeldKey = null;
    private static long lastHeldTick = 0L;

    private static long nowTick() {
        return com.killer560.hub.util.SessionRecorderSink.tick();
    }

    /** One line per change of reason, or at most once per 20 ticks for the same one. */
    private static void logHeld(String key, String detail) {
        long now = nowTick();
        if (key.equals(lastHeldKey) && now - lastHeldTick < 20) {
            return;
        }
        lastHeldKey = key;
        lastHeldTick = now;
        LOGGER.info("[AutoPuzzles] TeleportMaze: held: {} tick={}", detail, now);
    }

    /** One line per change of reason only - for guards that stay true for many ticks. */
    private static void logChange(String key, String detail) {
        if (key.equals(lastHeldKey)) {
            return;
        }
        lastHeldKey = key;
        lastHeldTick = nowTick();
        LOGGER.info("[AutoPuzzles] TeleportMaze: held: {} tick={}", detail, lastHeldTick);
    }

    private AutoTeleportMaze() {
    }

    static void levelChanged(Minecraft client) {
        GUARD.levelChanged();
        CAMERA.drop();
        reset(client);
        consumedArrival = com.killer560.hub.livemap.autoclear.ClearExecutor.arrivalSeq();
        startSeq = -1;
    }

    static void tick(Minecraft client, String roomName) {
        AutoPuzzlesConfig cfg = AutoPuzzlesConfig.getInstance();
        // A live view of the solver's set; only taken when something below can read it (fps-auto-1015).
        Set<BlockPos> visited = GUARD.observing() || startWalking || cfg.isAutoTeleportMazeEnabled()
                ? TeleportMazeSolverFeature.getVisited() : null;
        if (visited != null) {
            GUARD.observe(visited.isEmpty());
        }
        // The walk onto the start pad after a map arrival runs before the room test: the map's spot for this room
        // is its doorway (AutoClearUtils' relative 15,68,-2), which the live map can file under the corridor or the
        // room next door rather than the maze.
        if (cfg.isAutoTeleportMazeEnabled() && finishStage == FinishStage.NONE && visited.isEmpty()
                && McCompat.screen(client) == null && client.player != null) {
            tickStartWalk(client, client.player);
        } else if (startWalking) {
            LOGGER.info("[AutoPuzzles] TeleportMaze: start-pad walk stopped: enabled={} finishStage={} screen={}"
                            + " visitedEmpty={} elapsedTicks={}", cfg.isAutoTeleportMazeEnabled(), finishStage,
                    McCompat.screen(client) != null, visited == null ? "n/a" : visited.isEmpty(),
                    nowTick() - walkStartTick);
            stop(client);
            if (visited.isEmpty()) {
                disengage(); // a screen opened; with a teleport in, the maze walk below keeps the camera
            }
        }
        if (startWalking) {
            CAMERA.keep(client.player);
            return;
        }
        if (!cfg.isAutoTeleportMazeEnabled() || !ROOM.equals(roomName)) {
            if (wasInRoom) {
                LOGGER.info("[AutoPuzzles] TeleportMaze: left the maze (room={} enabled={}) - reset: hops={}"
                                + " finishStage={} walking={} tick={}", roomName, cfg.isAutoTeleportMazeEnabled(), hops,
                        finishStage, walking, nowTick());
                // Out of the maze after its last cell (the end pad sends him out, or he walked out): that is the auto's
                // last step, so the working spell ends now. Left to the 40-tick grace (and the grace's server-lag
                // extension), Auto Secret stood at the maze's exit for 64 s on one floor of fullclear acplan-1020.
                boolean finished = finishStage == FinishStage.STEP_ON_END_PAD || finishStage == FinishStage.WALK_OUT
                        || finishStage == FinishStage.DONE;
                reset(client);
                if (finished) {
                    LOGGER.info("[AutoPuzzles] TeleportMaze: out after the last cell - done tick={}", nowTick());
                    AutoPuzzleActivity.ended();
                }
                GUARD.leftRoom();
            }
            wasInRoom = false;
            return;
        }
        if (!wasInRoom) {
            // Only react to teleports made while in the room - or, after a start-pad walk, from when that walk
            // began, so the start pad's own teleport counts even if the room only read as the maze afterwards.
            lastSeq = startSeq >= 0 ? startSeq : TeleportMazeSolverFeature.getTeleportSeq();
            LOGGER.info("[AutoPuzzles] TeleportMaze: entered the maze: baseSeq={} (from {}) tick={}", lastSeq,
                    startSeq >= 0 ? "the start-pad walk" : "the solver now", nowTick());
            startSeq = -1;
        }
        wasInRoom = true;

        CAMERA.keep(client.player);

        if (finishStage != FinishStage.NONE) {
            tickFinish(client);
            if (finishStage == FinishStage.DONE) {
                disengage();
            }
            rememberView(client);
            return;
        }

        boolean ready = GUARD.solverOn(TeleportMazeSolverConfig.getInstance().isEnabled()) && GUARD.fresh();
        if (!ready || visited.isEmpty()) {
            if (!ready) {
                logChange("notReady", "solver not ready (solver off or its data predates this world) - not moving");
            } else if (walking || pendingTicks > 0) {
                logHeld("noVisited", "the solver's visited set emptied while walking - stopped");
            }
            stop(client);
            disengage();
            rememberView(client);
            return;
        }
        if (McCompat.screen(client) != null) {
            if (walking || pendingTicks > 0) {
                LOGGER.info("[AutoPuzzles] TeleportMaze: a screen opened ({}) - walk stopped, pending hop dropped:"
                                + " walking={} pendingTicks={} elapsedTicks={}", McCompat.screen(client).getClass().getSimpleName(),
                        walking, pendingTicks, nowTick() - walkStartTick);
            }
            stop(client);
            pendingTicks = -1;
            disengage();
            rememberView(client);
            return;
        }
        lastHeldKey = null;
        int seq = TeleportMazeSolverFeature.getTeleportSeq();
        if (seq != lastSeq) {
            if (client.player != null) {
                Vec3 landed = client.player.position();
                LOGGER.info("[AutoPuzzles] TeleportMaze: teleport landed: seq={} (was {}) hop={} at {} nearestPad={}"
                                + " target={} wasWalking={} elapsedTicks={}", seq, lastSeq, hops + 1,
                        String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", landed.x, landed.y, landed.z),
                        AutoPuzzleUtil.fmt(TeleportMazeSolverFeature.nearestPad(landed)),
                        walkTarget == null ? "none" : AutoPuzzleUtil.fmt(walkTarget), walking,
                        nowTick() - walkStartTick);
            }
            lastSeq = seq;
            if (++hops > MAX_HOPS) {
                LOGGER.info("[AutoPuzzles] TeleportMaze: {} teleports without reaching the end - stopped: visited={}"
                                + " knownPads={} tick={}", MAX_HOPS, TeleportMazeSolverFeature.getVisited().size(),
                        TeleportMazeSolverFeature.getTpPads().size(), nowTick());
                stop(client);
                disengage();
                finishStage = FinishStage.DONE;
                return;
            }
            LOGGER.info("[AutoPuzzles] TeleportMaze: next pad picked in 1 tick (hop {}) tick={}", hops, nowTick());
            stop(client);
            pendingTicks = 1; // QUOI: Scheduler.scheduleTask { nextMove = true }
            engage(client);
            return;
        }
        LocalPlayer player = client.player;
        if (pendingTicks > 0 && --pendingTicks == 0) {
            pendingTicks = -1;
            BlockPos target = getPad(player.position());
            if (target != null) {
                startWalk(client, player, target, "pad " + AutoPuzzleUtil.fmt(target) + " (" + padReason + ", hop "
                        + hops + ")");
            } else {
                stop(client);
                if (isAtEndPad(player.position())) {
                    LOGGER.info("[AutoPuzzles] TeleportMaze: final step reached - on the end pad at {} after {} hop(s)"
                                    + " tick={}", AutoPuzzleUtil.fmt(TeleportMazeSolverFeature.nearestPad(player.position())),
                            hops, nowTick());
                    finishStage = FinishStage.CHEST;
                    finishStageStartTick = nowTick();
                    finishStageStartMs = System.currentTimeMillis();
                    finishStageLag = com.killer560.hub.util.ServerLag.mark();
                    finishChest = null;
                    chestWindowMark = com.killer560.hub.util.ChestWindows.count();
                    lastChestClickMs = 0L;
                    chestClicks = 0;
                    chestMissingLogged = false;
                } else {
                    LOGGER.info("[AutoPuzzles] TeleportMaze: no pad to walk to - stopped: at {} nearestPad={} hops={}"
                                    + " knownPads={} visited={} exitCandidates={} best={} lastReason='{}' tick={}",
                            AutoPuzzleUtil.fmt(player.blockPosition()),
                            AutoPuzzleUtil.fmt(TeleportMazeSolverFeature.nearestPad(player.position())), hops,
                            TeleportMazeSolverFeature.getTpPads().size(), TeleportMazeSolverFeature.getVisited().size(),
                            TeleportMazeSolverFeature.getCorrectPortals().size(),
                            TeleportMazeSolverFeature.getBest() == null ? "none"
                                    : AutoPuzzleUtil.fmt(TeleportMazeSolverFeature.getBest()), padReason, nowTick());
                    disengage();
                }
            }
        } else if (walking) {
            tickWalk(client, player);
        }
        rememberView(client);
    }

    /**
     * Starts a walk onto {@code pad}: round whatever is in the way ({@link MazeWalk}), or - if the grid finds no way
     * at all - straight at it as before. The timeout grows with the planned length.
     */
    private static void startWalk(Minecraft client, LocalPlayer player, BlockPos pad, String label) {
        // The pad's own block is where his feet are once he is on it (an end portal frame is 13/16 tall).
        boolean planned = WALK.plan(client.level, player.position(), pad);
        walkStraight = !planned;
        walkTarget = pad;
        walkStartMs = System.currentTimeMillis();
        walkLag = com.killer560.hub.util.ServerLag.mark();
        walkTimeoutMs = planned
                ? Math.max(WALK_TIMEOUT_MS, (long) (WALK.length(player.position()) / WALK_SPEED * 1000.0) + 1500L)
                : WALK_TIMEOUT_MS;
        walking = true;
        walkStartTick = nowTick();
        LOGGER.info("[AutoPuzzles] TeleportMaze: walking to {} - {} (from {} timeout={}ms tick={})", label, planned
                ? WALK.legs() + " straight leg(s), " + String.format(java.util.Locale.US, "%.1f",
                WALK.length(player.position())) + " blocks"
                : "no way round found, straight at it", AutoPuzzleUtil.fmt(player.blockPosition()), walkTimeoutMs,
                walkStartTick);
        tickWalk(client, player);
    }

    private static void tickWalk(Minecraft client, LocalPlayer player) {
        if (com.killer560.hub.util.ServerLag.serverMsSince(walkStartMs, walkLag) > walkTimeoutMs) {
            LOGGER.info("[AutoPuzzles] TeleportMaze: no teleport after {}ms of walking - stopped: target={} at {}"
                            + " straight={} startWalk={} hops={} elapsedTicks={}", walkTimeoutMs,
                    AutoPuzzleUtil.fmt(walkTarget), String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f",
                            player.getX(), player.getY(), player.getZ()), walkStraight, startWalking, hops,
                    nowTick() - walkStartTick);
            ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Auto Teleport Maze: "),
                    ModChat.bad("could not reach the pad"), ModChat.text(" - stopped."));
            startSeq = -1;
            stop(client);
            disengage();
            return;
        }
        if (!walkStraight && WALK.tick(client)) {
            return;
        }
        // No planned way (or the planned points are used up, i.e. he is at the pad): straight at it, as QUOI does.
        float[] dir = AutoPuzzleUtil.direction(player.getEyePosition(), Vec3.atCenterOf(walkTarget));
        AutoPuzzleUtil.rotateCamera(player, dir[0], dir[1]);
        client.options.keyUp.setDown(true);
    }

    /**
     * Walks from where the Interactive Map put him onto the start pad, which is what starts the maze.
     *
     * <p>killer560 (2026-10-04): "When I teleport to tp maze using interactive map it doesn't auto run to the first
     * tp node." The auto only ever reacted to a maze teleport - QUOI's design, where you step on the start pad
     * yourself - so a map path ending at this room's doorway spot left him standing there. Now an arrival by the
     * map ({@code ClearExecutor.arrivalSeq}, which only moves when a path ends with him where it planned) inside
     * this room, before any maze teleport, walks him onto the start pad; from its teleport on the auto runs as
     * before. Walking in yourself does not trigger it, so it never fights his own keys.
     */
    private static void tickStartWalk(Minecraft client, LocalPlayer player) {
        int arrival = com.killer560.hub.livemap.autoclear.ClearExecutor.arrivalSeq();
        if (arrival != consumedArrival && !startWalking) {
            if (System.currentTimeMillis() - com.killer560.hub.livemap.autoclear.ClearExecutor.arrivalMs() > 3000L) {
                LOGGER.debug("[AutoPuzzles] TeleportMaze: map arrival {} ignored - more than 3000ms old", arrival);
                consumedArrival = arrival; // too old to act on
                return;
            }
            if (com.killer560.hub.livemap.autoclear.ClearExecutor.isBusy() || !player.onGround()) {
                logHeld("arrivalSettle", "map arrival " + arrival + " not settled yet (mapBusy="
                        + com.killer560.hub.livemap.autoclear.ClearExecutor.isBusy() + " onGround=" + player.onGround()
                        + ")");
                return; // not settled yet - the 3 s window allows for it
            }
            consumedArrival = arrival;
            Vec3 arrivedAt = com.killer560.hub.livemap.autoclear.ClearExecutor.arrivalPos();
            int[] cr = mazeClayRotation();
            if (cr == null || arrivedAt == null || arrivedAt.distanceToSqr(player.position()) > 9.0) {
                if (cr == null) {
                    LOGGER.debug("[AutoPuzzles] TeleportMaze: map arrival {} ignored - no maze on this floor's layout",
                            arrival);
                } else {
                    LOGGER.info("[AutoPuzzles] TeleportMaze: map arrival {} ignored - {} tick={}", arrival,
                            arrivedAt == null ? "no arrival position" : "he moved "
                                    + String.format(java.util.Locale.ROOT, "%.2f",
                                    Math.sqrt(arrivedAt.distanceToSqr(player.position())))
                                    + " blocks since (over 3)", nowTick());
                }
                return; // no maze on this floor, or he has moved since - his walk, not ours
            }
            BlockPos startPad = roomBlock(START_PAD_RELATIVE, cr);
            BlockPos door = roomBlock(AutoClearUtils.roomOverride(ROOM), cr).above();
            BlockPos feet = player.blockPosition();
            boolean atDoor = Math.abs(feet.getX() - door.getX()) <= 3 && Math.abs(feet.getZ() - door.getZ()) <= 3;
            boolean inMaze = ROOM.equals(LiveMapFeature.currentRoomEntry() == null ? null
                    : LiveMapFeature.currentRoomEntry().name);
            if (!atDoor && !inMaze) {
                LOGGER.debug("[AutoPuzzles] TeleportMaze: map arrival {} elsewhere - feet {} door {}", arrival,
                        AutoPuzzleUtil.fmt(feet), AutoPuzzleUtil.fmt(door));
                return; // the map took him somewhere else
            }
            if (!TeleportMazeSolverConfig.getInstance().isEnabled() || !GUARD.fresh()) {
                LOGGER.info("[AutoPuzzles] TeleportMaze: map arrival at the maze but not walking onto the start pad -"
                                + " solverOn={} fresh={} tick={}", TeleportMazeSolverConfig.getInstance().isEnabled(),
                        !GUARD.observing(), nowTick());
                GUARD.solverOn(TeleportMazeSolverConfig.getInstance().isEnabled());
                return;
            }
            startWalking = true;
            startSeq = TeleportMazeSolverFeature.getTeleportSeq();
            engage(client);
            startWalk(client, player, startPad, "the start pad (arrived by the Interactive Map)");
            return;
        }
        if (startWalking && walking) {
            tickWalk(client, player);
            if (!walking) {
                startWalking = false;
            }
            return;
        }
        if (!startWalking) {
            tickEntrance(client, player);
        }
    }

    /** Ticks in a row with none of his own movement keys down. */
    private static int idleTicks = 0;
    /** One entrance walk per visit to the room; re-armed when he leaves it or the world changes. */
    private static boolean entranceTried = false;
    /** The entrance walk's own reason for not starting, logged once per change. */
    private static String entranceWait = null;
    /** He must have stood still this long before the auto walks him - his own walk in is never taken over. */
    private static final int ENTRANCE_IDLE_TICKS = 10;
    /** The start pad is alone in the entrance chamber; further than this on foot and he is not in it. */
    private static final double ENTRANCE_MAX_WALK = 24.0;

    /**
     * Walks onto the start pad when he is standing in the maze's ENTRANCE, not only after a map arrival.
     *
     * <p>2026-10-04, 93-solve: with the player at the room's own spawn in the entrance chamber, the auto never moved
     * him; the test walked him onto the start pad itself. QUOI's design leaves that first step to the player, but
     * the arrival-only trigger above misses every way in that is not the Interactive Map (walking in, /goto, a
     * door). So: in the Teleport Maze, before any maze teleport, on the ground, with none of his movement keys
     * down for {@link #ENTRANCE_IDLE_TICKS} ticks, and with a walk on foot to the start pad of at most
     * {@link #ENTRANCE_MAX_WALK} blocks (the start pad is alone in its chamber, so a walk that short only exists
     * from the entrance side), the auto walks him onto it - once per visit, so a stopped walk is not retried in a
     * loop. His own keys still win: the walk only starts when he is standing still. Same on Hypixel.
     */
    private static void tickEntrance(Minecraft client, LocalPlayer player) {
        boolean ownKeys = client.options.keyUp.isDown() || client.options.keyDown.isDown()
                || client.options.keyLeft.isDown() || client.options.keyRight.isDown()
                || client.options.keyJump.isDown();
        idleTicks = ownKeys ? 0 : idleTicks + 1;
        com.killer560.hub.roomdatabase.RoomEntry here = LiveMapFeature.currentRoomEntry();
        if (here == null || !ROOM.equals(here.name)) {
            entranceTried = false;
            entranceWait = null;
            return;
        }
        if (entranceTried) {
            return;
        }
        String why = null;
        if (!player.onGround()) {
            why = "not on the ground";
        } else if (idleTicks < ENTRANCE_IDLE_TICKS) {
            why = "his movement keys are down";
        } else if (com.killer560.hub.livemap.autoclear.ClearExecutor.isBusy()) {
            why = "the Interactive Map is moving him";
        } else if (!TeleportMazeSolverConfig.getInstance().isEnabled()) {
            GUARD.solverOn(false);
            why = "Teleport Maze Solver is off";
        } else if (!GUARD.fresh()) {
            why = "the solver's data predates this world";
        }
        int[] cr = why == null ? LiveMapFeature.currentRoomClayAndRotation() : null;
        if (why == null && cr == null) {
            why = "the room's rotation is not known yet";
        }
        if (why != null) {
            if (!why.equals(entranceWait)) {
                entranceWait = why;
                LOGGER.info("[AutoPuzzles] TeleportMaze: not walking onto the start pad yet - {}", why);
            }
            return;
        }
        BlockPos startPad = PuzzleCoords.real(START_PAD_RELATIVE[0], START_PAD_RELATIVE[1], START_PAD_RELATIVE[2], cr);
        entranceTried = true;
        MazeWalk probe = new MazeWalk();
        if (!probe.plan(client.level, player.position(), startPad)
                || probe.length(player.position()) > ENTRANCE_MAX_WALK) {
            LOGGER.info("[AutoPuzzles] TeleportMaze: in the maze but no walk of {} blocks or less onto the start pad {}"
                    + " - not in the entrance, so not walking", ENTRANCE_MAX_WALK, AutoPuzzleUtil.fmt(startPad));
            return;
        }
        startWalking = true;
        startSeq = TeleportMazeSolverFeature.getTeleportSeq();
        engage(client);
        startWalk(client, player, startPad, "the start pad (standing in the entrance)");
    }

    /** Takes the free camera from the view he had on the tick before this teleport - see {@link #CAMERA}. */
    private static void engage(Minecraft client) {
        if (CAMERA.isEngaged()) {
            return;
        }
        float yaw = Float.isNaN(lastYaw) ? client.player.getYRot() : lastYaw;
        float pitch = Float.isNaN(lastPitch) ? client.player.getXRot() : lastPitch;
        CAMERA.engage(client.player, yaw, pitch);
    }

    /** Hands the camera back, body turned under it first ({@link FreeCam#release}). Only a hold this auto took. */
    private static void disengage() {
        CAMERA.release(Minecraft.getInstance().player);
    }

    private static void rememberView(Minecraft client) {
        if (client.player != null && !CAMERA.isEngaged()) {
            lastYaw = client.player.getYRot();
            lastPitch = client.player.getXRot();
        }
    }

    /** The maze room's {@code [clayX, clayZ, rotation]} from the floor's layout - he need not be standing in it. */
    private static int[] mazeClayRotation() {
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

    /** A database-relative block of the maze in the world, shifted with the sim's floor (zero on Hypixel) - the
     *  conversion AutoClearUtils.pathToRoom makes, which does not depend on the room he is standing in. */
    private static BlockPos roomBlock(int[] rel, int[] cr) {
        com.killer560.hub.roomdatabase.RoomEntry.Pos p = new com.killer560.hub.roomdatabase.RoomEntry.Pos();
        p.x = rel[0];
        p.y = rel[1];
        p.z = rel[2];
        return com.killer560.hub.roomdatabase.RoomDatabase.toRealCoord(p, cr[0], cr[1], cr[2])
                .above(com.killer560.hub.livemap.DungeonLayout.simYOffset());
    }

    private static boolean isAtEndPad(Vec3 pos) {
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        if (cr == null) {
            return false;
        }
        BlockPos endReal = PuzzleCoords.real(END_PAD_RELATIVE[0], END_PAD_RELATIVE[1], END_PAD_RELATIVE[2], cr);
        BlockPos landed = TeleportMazeSolverFeature.nearestPad(pos);
        return endReal.equals(landed);
    }

    /**
     * The last cell: walk toward the chest (clicking it once he is in the centre cell and in reach) until the server
     * confirms the click, then walk to the only pad in that cell - the end pad - which sends him out, then walk out.
     *
     * <p>killer560, 2026-10-19: "in tp maze once it gets to the final thing have it walk towards the chest till it
     * realizes it clicked it then once it clicks it have it walk to the only pad in that cell", and the chest is
     * aura'd once he is in the middle cell and in range. This replaced the old finish (one click from the end pad, then
     * a walk to the start pad, which the centre's wall at z 13 made impossible on Hypixel - SIM.md).
     */
    private static void tickFinish(Minecraft client) {
        if (finishStage == FinishStage.CHEST) {
            String confirmed = chestClickConfirmed(client);
            if (confirmed != null) {
                chestConfirmed(client, confirmed);
            }
        }
        if (McCompat.screen(client) != null) {
            logHeld("finishScreen", "finish stage " + finishStage + " waits - a screen is open ("
                    + McCompat.screen(client).getClass().getSimpleName() + ")");
            return;
        }
        LocalPlayer player = client.player;
        switch (finishStage) {
            case CHEST -> tickChest(client, player);
            case STEP_ON_END_PAD -> stepOnEndPad(client, player);
            case WALK_OUT -> {
                int[] cr = LiveMapFeature.currentRoomClayAndRotation();
                int[] outsideRel = AutoClearUtils.roomOverride(ROOM);
                BlockPos outside = cr == null || outsideRel == null ? null
                        : PuzzleCoords.real(outsideRel[0], outsideRel[1], outsideRel[2], cr);
                boolean done = walkFinishLeg(client, player, outside, FinishStage.DONE, "the door");
                if (done && finishStage == FinishStage.DONE) {
                    ModChat.log(AutoPuzzlesFeature.CHAT, ModChat.text("Teleport Maze: "), ModChat.good("done"),
                            ModChat.text("."));
                    AutoPuzzleActivity.ended(); // walked out: the last step is over
                }
            }
            default -> {
            }
        }
    }

    /**
     * Whether the server has answered a click on the centre chest, and how - the signals the other puzzle autos read
     * for a chest (AutoBoulder.chestTaken): a chest window since the final step began (counted as its packet arrives,
     * so Auto Close Chest cancelling the screen does not hide it), a container screen open, Secret Aura having seen it
     * taken, or its lid open. Null while none has come.
     */
    private static String chestClickConfirmed(Minecraft client) {
        if (com.killer560.hub.util.ChestWindows.since(chestWindowMark)) {
            return "the server opened its window";
        }
        if (com.killer560.hub.util.ActionGate.containerScreenOpen(client)) {
            return "its window is open";
        }
        if (finishChest == null || client.level == null) {
            return null;
        }
        if (com.killer560.hub.cheatutils.SecretAuraFeature.isDone(finishChest)) {
            return "Secret Aura saw it taken";
        }
        return client.level.getBlockEntity(finishChest)
                instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest && chest.getOpenNess(0f) > 0f
                ? "its lid opened" : null;
    }

    /** The click is in: stop at the chest and start the walk to the only pad in the cell. */
    private static void chestConfirmed(Minecraft client, String how) {
        LOGGER.info("[AutoPuzzles] TeleportMaze: chest click confirmed - {} ({} click(s) of ours) chest={}"
                        + " elapsedTicks={} sinceLastClickMs={}", how, chestClicks, AutoPuzzleUtil.fmt(finishChest),
                nowTick() - finishStageStartTick,
                lastChestClickMs == 0L ? "none" : String.valueOf(System.currentTimeMillis() - lastChestClickMs));
        ModChat.log(AutoPuzzlesFeature.CHAT, ModChat.text("Teleport Maze: opened the "), ModChat.good("secret chest"),
                ModChat.text("."));
        client.options.keyUp.setDown(false);
        walking = false;
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        BlockPos pad = cr == null ? null
                : PuzzleCoords.real(END_PAD_RELATIVE[0], END_PAD_RELATIVE[1], END_PAD_RELATIVE[2], cr);
        LOGGER.info("[AutoPuzzles] TeleportMaze: walking to the only pad in the centre cell, the end pad at {}",
                pad == null ? "(room rotation not known)" : AutoPuzzleUtil.fmt(pad));
        finishStage = FinishStage.STEP_ON_END_PAD;
        finishLastPos = null;
        finishStageStartTick = nowTick();
        finishStageStartMs = System.currentTimeMillis();
        finishStageLag = com.killer560.hub.util.ServerLag.mark();
    }

    /** Whether his feet are inside the centre chamber. */
    private static boolean inCentreCell(LocalPlayer player, int[] cr) {
        BlockPos rel = PuzzleCoords.relative(player.blockPosition(), cr);
        return rel.getX() >= CENTRE_MIN_RELATIVE[0] && rel.getX() <= CENTRE_MAX_RELATIVE[0]
                && rel.getZ() >= CENTRE_MIN_RELATIVE[2] && rel.getZ() <= CENTRE_MAX_RELATIVE[2];
    }

    /**
     * Walks toward the chest in the centre cell, holding forward until right beside it, and clicks it (the puzzle
     * autos' own click: gateWorldClick, then interactBlock) whenever he is inside the centre cell and it is in reach,
     * again every {@link #CHEST_RECLICK_MS} while no answer has come. Gives up, and says why, after
     * {@link #CHEST_TIMEOUT_MS} with no confirmation.
     */
    private static void tickChest(Minecraft client, LocalPlayer player) {
        if (com.killer560.hub.util.ServerLag.serverMsSince(finishStageStartMs, finishStageLag) > CHEST_TIMEOUT_MS) {
            String why = finishChest == null ? "no chest was found in the centre cell"
                    : chestClicks == 0 ? "the chest at " + AutoPuzzleUtil.fmt(finishChest) + " never came in reach"
                    : "the server never answered " + chestClicks + " click(s) on the chest at "
                    + AutoPuzzleUtil.fmt(finishChest);
            LOGGER.info("[AutoPuzzles] TeleportMaze: chest click not confirmed after {}ms - {} - stopped:"
                            + " at {} elapsedTicks={}", CHEST_TIMEOUT_MS, why,
                    AutoPuzzleUtil.fmt(player.blockPosition()), nowTick() - finishStageStartTick);
            ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Auto Teleport Maze: "),
                    ModChat.bad("the chest click was never confirmed"), ModChat.text(" - stopped."));
            client.options.keyUp.setDown(false);
            walking = false;
            finishStage = FinishStage.DONE;
            return;
        }
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        if (finishChest == null && cr != null) {
            List<BlockPos> chests = AutoPuzzleUtil.chestsInRoom(client.level, cr,
                    CENTRE_MIN_RELATIVE[0], CENTRE_MIN_RELATIVE[1], CENTRE_MIN_RELATIVE[2],
                    CENTRE_MAX_RELATIVE[0], CENTRE_MAX_RELATIVE[1], CENTRE_MAX_RELATIVE[2]);
            if (chests != null && !chests.isEmpty()) {
                Vec3 feet = player.position();
                BlockPos nearest = null;
                for (BlockPos c : chests) {
                    if (nearest == null || feet.distanceToSqr(Vec3.atCenterOf(c))
                            < feet.distanceToSqr(Vec3.atCenterOf(nearest))) {
                        nearest = c;
                    }
                }
                finishChest = nearest;
                LOGGER.info("[AutoPuzzles] TeleportMaze: walking toward the chest at {} until a click is confirmed"
                                + " ({} chest(s) in the centre cell, nearest picked) from {} tick={}",
                        AutoPuzzleUtil.fmt(finishChest), chests.size(), AutoPuzzleUtil.fmt(player.blockPosition()),
                        nowTick());
            }
        }
        if (finishChest == null) {
            if (!chestMissingLogged) {
                chestMissingLogged = true;
                LOGGER.info("[AutoPuzzles] TeleportMaze: no chest seen in the centre cell yet - waiting ({}) tick={}",
                        cr == null ? "room rotation not known" : "scan found none or chunks not loaded", nowTick());
            }
            client.options.keyUp.setDown(false);
            walking = false;
            return;
        }
        // Reach to the box, as everywhere else: the server's 4.5 is measured to it.
        double distSq = com.killer560.hub.util.BlockHits.boxDistanceSq(player.getEyePosition(), finishChest);
        long now = System.currentTimeMillis();
        {
            // Logging only: why no click goes this tick (the gate's own refusal is logged by AutoPuzzleUtil).
            String holdKey = cr == null ? "rotation" : !inCentreCell(player, cr) ? "outsideCell"
                    : distSq > AURA_REACH_SQ ? "reach" : player.isShiftKeyDown() ? "sneaking"
                    : chestClicks >= MAX_CHEST_CLICKS ? "clicksUsed" : null;
            String hold = holdKey == null ? null : switch (holdKey) {
                case "rotation" -> "room rotation not known";
                case "outsideCell" -> "not inside the centre cell yet (rel "
                        + AutoPuzzleUtil.fmt(PuzzleCoords.relative(player.blockPosition(), cr)) + ")";
                case "reach" -> "out of reach";
                case "sneaking" -> "sneaking";
                default -> "all " + MAX_CHEST_CLICKS + " clicks used, waiting for the answer";
            };
            if (hold != null) {
                logHeld("chest:" + holdKey, "no chest click: " + hold + " - boxDist="
                        + String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(distSq)) + " reach="
                        + String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(AURA_REACH_SQ)) + " clicks="
                        + chestClicks + " elapsedTicks=" + (nowTick() - finishStageStartTick));
            }
        }
        if (cr != null && inCentreCell(player, cr) && distSq <= AURA_REACH_SQ && !player.isShiftKeyDown()
                && chestClicks < MAX_CHEST_CLICKS && now - lastChestClickMs >= CHEST_RECLICK_MS
                && AutoPuzzleUtil.gateWorldClick(client, finishChest)) {
            if (AutoPuzzleUtil.interactBlock(client, finishChest)) {
                chestClicks++;
                lastChestClickMs = now;
                LOGGER.info("[AutoPuzzles] TeleportMaze: clicked the chest at {} (click {}), waiting for the server",
                        AutoPuzzleUtil.fmt(finishChest), chestClicks);
            } else {
                LOGGER.warn("[AutoPuzzles] TeleportMaze: no clickable shape at {}", AutoPuzzleUtil.fmt(finishChest));
            }
        }
        Vec3 centre = Vec3.atCenterOf(finishChest);
        double dx = centre.x - player.getX();
        double dz = centre.z - player.getZ();
        if (dx * dx + dz * dz > CHEST_STOP_SQ) {
            float[] dir = AutoPuzzleUtil.direction(player.getEyePosition(), centre);
            AutoPuzzleUtil.rotateCamera(player, dir[0], dir[1]);
            client.options.keyUp.setDown(true);
            walking = true;
        } else {
            client.options.keyUp.setDown(false); // beside it: wait for the answer
            walking = false;
        }
    }

    /** Walks back onto the end pad until it sends him out (a jump of more than six blocks), or times out. */
    private static void stepOnEndPad(Minecraft client, LocalPlayer player) {
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        BlockPos pad = cr == null ? null
                : PuzzleCoords.real(END_PAD_RELATIVE[0], END_PAD_RELATIVE[1], END_PAD_RELATIVE[2], cr);
        // A jump of more than a block and a half in one tick is the pad's teleport, however near the pad it lands: the exit
        // can land six blocks back in the room (21:09 run, 2026-10-19), and the walk toward the pad then ran him back in.
        Vec3 here = player.position();
        boolean jumped = finishLastPos != null && here.distanceTo(finishLastPos) > 1.5;
        finishLastPos = here;
        boolean sentOut = pad == null || jumped || here.distanceToSqr(Vec3.atCenterOf(pad)) > SENT_OUT_SQ;
        boolean timedOut = com.killer560.hub.util.ServerLag.serverMsSince(finishStageStartMs, finishStageLag) > FINISH_WALK_TIMEOUT_MS;
        if (sentOut || timedOut) {
            if (timedOut && !sentOut) {
                LOGGER.warn("[AutoPuzzles] TeleportMaze: the end pad did not send me out - continuing anyway: pad={}"
                                + " dist={} elapsedTicks={}", AutoPuzzleUtil.fmt(pad),
                        String.format(java.util.Locale.ROOT, "%.2f",
                                Math.sqrt(player.position().distanceToSqr(Vec3.atCenterOf(pad)))),
                        nowTick() - finishStageStartTick);
            } else {
                LOGGER.info("[AutoPuzzles] TeleportMaze: the end pad sent me out ({}) - now at {}, walking out"
                                + " elapsedTicks={}", pad == null ? "room rotation not known, assumed" : "teleported",
                        AutoPuzzleUtil.fmt(player.blockPosition()), nowTick() - finishStageStartTick);
            }
            client.options.keyUp.setDown(false);
            walking = false;
            finishStageStartTick = nowTick();
            finishStage = FinishStage.WALK_OUT;
            finishStageStartMs = System.currentTimeMillis();
            finishStageLag = com.killer560.hub.util.ServerLag.mark();
            return;
        }
        float[] dir = AutoPuzzleUtil.direction(player.getEyePosition(), Vec3.atCenterOf(pad));
        AutoPuzzleUtil.rotateCamera(player, dir[0], dir[1]);
        client.options.keyUp.setDown(true);
        walking = true;
    }

    /** Rotates the camera towards {@code target} and holds forward until close, same primitive as the normal
     *  pad-to-pad walk above. @return true once this leg is over (arrived, no known target, or timed out) -
     *  callers must check {@code finishStage} afterward since a timeout still advances it. */
    private static boolean walkFinishLeg(Minecraft client, LocalPlayer player, BlockPos target, FinishStage nextStage, String label) {
        if (target == null) {
            LOGGER.info("[AutoPuzzles] TeleportMaze: walk to {} skipped - its spot is not known (room rotation or"
                    + " override missing), next stage {} tick={}", label, nextStage, nowTick());
            finishStage = nextStage;
            finishStageStartTick = nowTick();
            finishStageStartMs = System.currentTimeMillis();
            finishStageLag = com.killer560.hub.util.ServerLag.mark();
            client.options.keyUp.setDown(false);
            walking = false;
            return true;
        }
        double distSq = player.position().distanceToSqr(Vec3.atCenterOf(target));
        if (distSq < ARRIVE_SQ) {
            LOGGER.info("[AutoPuzzles] TeleportMaze: reached {} at {} (dist {}) - next stage {} elapsedTicks={}", label,
                    AutoPuzzleUtil.fmt(target), String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(distSq)),
                    nextStage, nowTick() - finishStageStartTick);
            client.options.keyUp.setDown(false);
            walking = false;
            finishStageStartTick = nowTick();
            finishStage = nextStage;
            finishStageStartMs = System.currentTimeMillis();
            finishStageLag = com.killer560.hub.util.ServerLag.mark();
            return true;
        }
        if (com.killer560.hub.util.ServerLag.serverMsSince(finishStageStartMs, finishStageLag) > FINISH_WALK_TIMEOUT_MS) {
            LOGGER.warn("[AutoPuzzles] TeleportMaze: walk to {} timed out - continuing anyway: target={} dist={}"
                            + " elapsedTicks={}", label, AutoPuzzleUtil.fmt(target),
                    String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(distSq)), nowTick() - finishStageStartTick);
            client.options.keyUp.setDown(false);
            walking = false;
            finishStageStartTick = nowTick();
            finishStage = nextStage;
            finishStageStartMs = System.currentTimeMillis();
            finishStageLag = com.killer560.hub.util.ServerLag.mark();
            return true;
        }
        float[] dir = AutoPuzzleUtil.direction(player.getEyePosition(), Vec3.atCenterOf(target));
        AutoPuzzleUtil.rotateCamera(player, dir[0], dir[1]);
        client.options.keyUp.setDown(true);
        walking = true;
        return false;
    }

    /** Why the last {@link #getPad} chose its pad, for the walking line. */
    private static String padReason = "";

    /**
     * The next pad, from {@link MazeRoute} over what this visit has learnt (the solver's visited pads, exit candidates,
     * best pad and the links its teleports showed). QUOI's own pick - best, unvisited diagonal, farthest, and once a
     * chamber was used up "the diagonal again" - walked him BACK through the maze from any pad he had come back
     * through, and on two generated floors of three never reached the middle (2026-10-06); see MazeRoute.
     */
    private static BlockPos getPad(Vec3 pos) {
        List<BlockPos> pads = new ArrayList<>(TeleportMazeSolverFeature.getTpPads());
        BlockPos currentPad = TeleportMazeSolverFeature.nearestPad(pos);
        int current = pads.indexOf(currentPad);
        if (current < 0) {
            LOGGER.info("[AutoPuzzles] TeleportMaze: pad pick: not standing on a known pad (nearest {} of {} known) -"
                    + " no pick", AutoPuzzleUtil.fmt(currentPad), pads.size());
            return null;
        }
        int[][] xz = new int[pads.size()][];
        int[] cellOf = new int[pads.size()];
        java.util.Map<BlockPos, Integer> index = new java.util.HashMap<>();
        for (int i = 0; i < pads.size(); i++) {
            xz[i] = new int[]{pads.get(i).getX(), pads.get(i).getZ()};
            index.put(pads.get(i), i);
            cellOf[i] = -1;
        }
        List<Set<BlockPos>> cells = TeleportMazeSolverFeature.getRealCells();
        for (int c = 0; c < cells.size(); c++) {
            for (BlockPos p : cells.get(c)) {
                Integer i = index.get(p);
                if (i != null) {
                    cellOf[i] = c;
                }
            }
        }
        java.util.Map<Integer, Integer> links = new java.util.HashMap<>();
        TeleportMazeSolverFeature.getLinks().forEach((from, to) -> {
            Integer a = index.get(from);
            Integer b = index.get(to);
            if (a != null && b != null) {
                links.put(a, b);
            }
        });
        Set<Integer> visited = new java.util.HashSet<>();
        for (BlockPos p : TeleportMazeSolverFeature.getVisited()) {
            Integer i = index.get(p);
            if (i != null) {
                visited.add(i);
            }
        }
        Set<Integer> candidates = new java.util.LinkedHashSet<>();
        for (BlockPos p : TeleportMazeSolverFeature.getCorrectPortals()) {
            Integer i = index.get(p);
            if (i != null) {
                candidates.add(i);
            }
        }
        BlockPos bestPos = TeleportMazeSolverFeature.getBest();
        Integer best = bestPos == null ? null : index.get(bestPos);
        MazeRoute.Choice choice = MazeRoute.choose(current, xz, cellOf, links, visited, candidates,
                best == null ? -1 : best);
        if (choice == null) {
            LOGGER.info("[AutoPuzzles] TeleportMaze: pad pick: none from {} (cell {}) - pads={} visited={} links={}"
                            + " exitCandidates={} best={}", AutoPuzzleUtil.fmt(currentPad), cellOf[current], pads.size(),
                    visited.size(), links.size(), candidates.size(), best == null ? "none" : AutoPuzzleUtil.fmt(bestPos));
            return null;
        }
        LOGGER.info("[AutoPuzzles] TeleportMaze: pad pick: {} from {} (cell {} -> {}) - {}; visited={} links={}"
                        + " exitCandidates={} best={}", AutoPuzzleUtil.fmt(pads.get(choice.pad())),
                AutoPuzzleUtil.fmt(currentPad), cellOf[current], cellOf[choice.pad()], choice.reason(), visited.size(),
                links.size(), candidates.size(), best == null ? "none" : AutoPuzzleUtil.fmt(bestPos));
        padReason = choice.reason();
        return pads.get(choice.pad());
    }

    private static void stop(Minecraft client) {
        startWalking = false;
        if (walking) {
            client.options.keyUp.setDown(false);
            walking = false;
        }
    }

    private static void reset(Minecraft client) {
        stop(client);
        entranceTried = false;
        entranceWait = null;
        idleTicks = 0;
        disengage();
        lastYaw = Float.NaN;
        lastPitch = Float.NaN;
        pendingTicks = -1;
        lastSeq = -1;
        hops = 0;
        finishStage = FinishStage.NONE;
        finishStageStartMs = 0L;
        finishChest = null;
        chestClicks = 0;
        lastChestClickMs = 0L;
        chestMissingLogged = false;
    }
}
