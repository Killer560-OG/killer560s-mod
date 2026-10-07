package com.killer560.hub.autopuzzles;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.puzzlesolvers.BlazeSolverConfig;
import com.killer560.hub.puzzlesolvers.BlazeSolverFeature;
import com.killer560.hub.puzzlesolvers.PuzzleCoords;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.ArrayList;
import java.util.List;
import com.killer560.hub.compat.McCompat;

/**
 * Auto Blaze - port of QUOI {@code BlazeSolver.kt}'s {@code auto} (TickEvent.End) logic on top of this mod's
 * {@link BlazeSolverFeature} kill order. Each tick: wait out the previous shot's arrow travel time (dist/2.5*50ms,
 * or until the target blaze dies); take the next blaze; build QUOI's hitboxes (target 0.35x0.8 half-extents,
 * others 0.75x1.45, centred 1 block under the stand); try 7 aim points on the target with the arrow simulation and
 * accept the first whose simulated arrow reaches the target before any other blaze or a block AND whose every arrow
 * (Terminator: the centre and both +-5 degree side arrows) misses every other blaze for its whole flight, with the
 * target treated as not there - any one of them may be the one that kills it, and the others fly on; shoot with the held shortbow once the Shoot cooldown
 * allows. With "Etherwarp Reposition" on, it also cycles QUOI's standing spots when there's no clean shot (and on
 * Higher Blaze below y=75).
 * <p>
 * Miss cooldown removed (killer560, 2026-09-27: "For auto puzzles remove the miss cooldown."): it used to add an
 * extra {@code missCooldownMs} on top of the arrow's travel time before trying again in case that shot missed.
 * Now the wait after a shot is just the travel time itself (or the target dying), then the Shoot cooldown alone
 * paces the next attempt, hit or miss.
 * <p>
 * killer560, 2026-09-27, two more Blaze-specific requests:
 * <ul>
 *   <li>"have an option for auto secret ... it needs to pathfind to the secret once it finishes doing the puzzle
 *   if the puzzle is done and you have auto higher lower on" ("Higher or Lower" is the puzzle's own in-game name
 *   for this Blaze-shooting room). Once every blaze is dead, {@link #tickSecret} walks (Interactive-Map-gated,
 *   {@link AutoPuzzleUtil#pathIfMapOn}) to the nearest secret this room's real database entry
 *   ({@link RoomEntry#secretCoords}) actually knows about, then auras it itself if it's a chest - never
 *   {@code SecretAuraFeature}, same reasoning as Auto Boulder.</li>
 *   <li>"for blaze by default can you have my characters head face more towards the middle when put into the
 *   freecam view" - {@link #engageCamera} takes the run's {@link FreeCam} facing the room's own horizontal
 *   centre, using {@link LiveMapFeature#roomWorldBounds}.</li>
 * </ul>
 * killer560, 2026-10-06: "make higher lower blaze enter a free cam state right now it just snaps my camera around
 * everywhere. It should be the same state used for ap3." The camera is held by {@link FreeCam} from the first aim
 * (shot or reposition) to the last shot, renewed every tick, and handed back with the body turned under it.
 * <p>
 * killer560, 2026-10-06, Auto Secret: the trip starts the tick after the volley at the last blaze leaves the bow
 * ({@link #earlyPending}), not after the kill; it walks to a block within aura reach of the chest on the exit side
 * ({@link #auraSpots}), not onto it; and a miss found on the way is shot again only after the secret is taken.
 */
final class AutoBlaze {

    private static final Logger LOGGER = ModLog.get("killer560smod-autopuzzles");
    private static final String LOWER = "Lower Blaze";
    private static final String HIGHER = "Higher Blaze";
    private static final BlockPos[] HIGHER_SPOTS = {
            new BlockPos(10, 94, 19), new BlockPos(22, 88, 17), new BlockPos(10, 118, 23), new BlockPos(20, 85, 11)
    };
    private static final BlockPos[] LOWER_SPOTS = {
            new BlockPos(24, 62, 14), new BlockPos(9, 45, 18), new BlockPos(10, 68, 23),
            new BlockPos(24, 29, 16), new BlockPos(13, 50, 8), new BlockPos(24, 48, 17)
    };
    /** The measured block reach, squared - was 36.0 (6.0 blocks) measured to the centre. */
    private static final double AURA_REACH_SQ = AutoPuzzleUtil.BLOCK_REACH_SQ;
    private static final long SECRET_WALK_TIMEOUT_MS = 20_000L; // these rooms are tall - the pathfinder needs longer
    private static final int MAX_AURA_ATTEMPTS = 3;

    private record BlazeHitbox(AABB aabb, boolean isTarget) {
    }

    private record SecretCandidate(RoomEntry.Pos relative, boolean chest) {
    }

    private static final AutoGuard GUARD = new AutoGuard("Auto Blaze", "Blaze Solver");
    /** Held from the first aim (shot or reposition) to the last shot - see {@link FreeCam} for why a per-rotation
     *  lease was not enough. */
    private static final FreeCam CAMERA = new FreeCam("Blaze");
    private static final AutoReposition REPOSITION = new AutoReposition("Blaze", CAMERA);
    /** The secret trip's own last-leg warp and its camera, held only for that warp (see {@link #walkToSecret}). */
    private static final FreeCam SECRET_CAM = new FreeCam("Blaze secret");
    private static final AutoReposition SECRET_WARP = new AutoReposition("Blaze secret", SECRET_CAM);
    private static final java.util.Set<BlockPos> directTried = new java.util.HashSet<>();

    private static long lastShotTime = 0L;
    private static boolean waitingForUpdate = false;
    private static Entity currentTarget = null;
    private static int currentSpot = 0;
    private static boolean wasInRoom = false;
    private static int lastBlazeCount = 0;
    /** What the INFO log last said, so a line is written when something changes rather than every tick. */
    private static Entity loggedTarget = null;
    private static Entity loggedNoShot = null;
    private static boolean loggedNotShortbow = false;
    /** The last refusal logged by {@link #say}, so a per-tick refusal is one INFO line, not twenty a second. */
    private static String lastSaid = null;
    /** Shots at the current target from {@link #shotsFrom}; past {@link #MAX_SHOTS_HERE} that spot is given up. */
    private static int shotsHere = 0;
    private static BlockPos shotsFrom = null;
    private static final int MAX_SHOTS_HERE = 4;
    /** Spots given up on for the current target - never chosen again for it. */
    private static final java.util.Set<BlockPos> badSpots = new java.util.HashSet<>();
    /** Consecutive ticks the solver's list has been empty since it last held blazes - see {@link #DONE_TICKS}. */
    private static int emptyTicks = 0;
    /**
     * Ticks the list must stay empty before the room is called done. The solver's list goes empty for a moment
     * whenever the label stands are replaced - a chain rebuilt after a fail drops every stand on one tick and puts
     * the new ones up on a later one - and "Blaze: done." was announced (and the secret walk started) in that gap.
     * A real finish stays empty for good, so waiting a second and a half costs nothing.
     */
    private static final int DONE_TICKS = 30;

    private enum SecretStage { NONE, FIND, WALK, AURA, DONE }

    private static SecretStage secretStage = SecretStage.NONE;
    private static BlockPos secretReal = null;
    private static boolean secretIsChest = false;
    private static int secretAuraAttempts = 0;
    private static long secretLegStartMs = 0L;
    private static boolean noSecretWarned = false;
    private static boolean secretMapOffWarned = false;

    /**
     * Early secret (killer560, 2026-10-06): "the second the arrows that are going to hit the last blaze are shot leave
     * the bow it pathfinds to the secret. It doesn't need to wait at all." Set on the tick the volley at the LAST
     * listed blaze leaves the bow - the auto gives every blaze exactly one volley and only shoots again once that
     * volley's simulated flight is over with the blaze still listed, so the volley at the last one is the one that
     * finishes the room. The trip starts on the NEXT tick, after that tick's movement packet has reported the aim
     * (see {@link AutoPuzzleUtil#useItemRotated}: turning the body back under the camera in the shot's own tick
     * would leave the aim unreported, which GrimAC flags as BadPacketsJ).
     */
    private static boolean earlyPending = false;
    /** Ticks of this room visit, a clock for the log and for {@link #testProbe}. */
    private static int ticks = 0;
    private static int finalReleaseTick = -1;
    private static int secretWalkTick = -1;
    private static int secretAuraTick = -1;
    private static int resumeTick = -1;
    /** The trip ended with a blaze still listed (the final volley missed); the next shot is the resume. */
    private static boolean resumePending = false;
    /** The block he stood on for the final volley, and - after a miss - where he goes back to shoot again. */
    private static BlockPos finalShotFrom = null;
    private static BlockPos returnTo = null;
    private static long returnStartMs = 0L;
    private static boolean returnPathIssued = false;
    /** Where he stood when he came into the room - the doorway, on Hypixel; the exit side when no door is known. */
    private static Vec3 entryPos = null;
    /**
     * Standable blocks within aura reach of a chest secret, best exit first (killer560, 2026-10-06: "for going to the
     * secret it doesn't need to go on top of it it just needs to be within aura range on whatever block gives the
     * best exit angle"). {@link #standIdx} moves on when the planner finds no path to one.
     */
    private static List<BlockPos> standSpots = List.of();
    private static int standIdx = 0;
    private static boolean walkIssued = false;
    private static long auraWaitStartMs = 0L;
    /** How long to wait at the chest for it to appear, or for the landing sneak to let go. */
    private static final long CHEST_WAIT_MS = 6_000L;

    private AutoBlaze() {
    }

    static void levelChanged(Minecraft client) {
        GUARD.levelChanged();
        CAMERA.drop();
        SECRET_CAM.drop();
        reset(client);
    }

    static void tick(Minecraft client, String roomName) {
        List<Entity> blazes = BlazeSolverFeature.getOrderedBlazes();
        GUARD.observe(blazes.isEmpty());
        AutoPuzzlesConfig cfg = AutoPuzzlesConfig.getInstance();
        boolean inRoom = LOWER.equals(roomName) || HIGHER.equals(roomName);
        if (!cfg.isAutoBlazeEnabled() || !inRoom) {
            if (wasInRoom) {
                reset(client);
                GUARD.leftRoom();
            }
            wasInRoom = false;
            return;
        }
        LocalPlayer player = client.player;
        if (!wasInRoom && player != null) {
            entryPos = player.position();
        }
        wasInRoom = true;
        ticks++;
        // Every tick of the run, whatever this tick goes on to do (wait out an arrow, a cooldown, a screen, a warp's
        // landing): the camera stays free from the first aim to the last shot.
        CAMERA.keep(player);
        if (!GUARD.solverOn(BlazeSolverConfig.getInstance().isEnabled())) {
            CAMERA.release(player);
            return;
        }
        if (earlyPending) {
            earlyPending = false;
            if (cfg.isAutoBlazeSecretEnabled() && secretStage == SecretStage.NONE) {
                // The final volley left the bow last tick and its aim has been reported: hand the camera back
                // (body turned under his view, as at the end of a run) and set off for the secret.
                REPOSITION.cancel(client);
                AutoReposition.releaseSneak(client);
                CAMERA.release(player);
                secretStage = SecretStage.FIND;
                LOGGER.info("[AutoPuzzles] Blaze: final volley released at tick {} - going for the secret now "
                        + "(tick {}), not waiting for the kill", finalReleaseTick, ticks);
            }
        }
        if (blazes.isEmpty()) {
            if (lastBlazeCount > 0) {
                if (++emptyTicks < DONE_TICKS) {
                    // The list also goes empty for a moment when a failed chain's stands are replaced, so the room
                    // is only called done once it has stayed empty; a secret trip already under way carries on.
                    say("the solver's list is empty - waiting " + DONE_TICKS + " ticks before calling the room done");
                } else {
                    LOGGER.info("[AutoPuzzles] Blaze: done - the solver has listed no blaze for {} ticks", DONE_TICKS);
                    ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Blaze: "), ModChat.good("done"), ModChat.text("."));
                    REPOSITION.cancel(client);
                    AutoReposition.releaseSneak(client);
                    CAMERA.release(player); // the last shot has landed: body back under his view, camera handed back
                    if (cfg.isAutoBlazeSecretEnabled() && secretStage == SecretStage.NONE) {
                        secretStage = SecretStage.FIND;
                    }
                    lastBlazeCount = 0;
                }
            }
            if (secretTripActive()) {
                tickSecret(client, player, blazes);
            }
            return;
        }
        emptyTicks = 0;
        lastBlazeCount = blazes.size();
        if (secretTripActive()) {
            // killer560, 2026-10-06: "If while it goes to get the secret it realizes it missed a blaze then it can get
            // it again after getting the secret." So a miss never cuts the trip short: the walk and the aura run to
            // the end, and only then does the shooting below pick the survivor up again.
            tickSecret(client, player, blazes);
            if (secretTripActive()) {
                return;
            }
            resumePending = true;
            returnTo = finalShotFrom;
            returnStartMs = System.currentTimeMillis();
            returnPathIssued = false;
            LOGGER.info("[AutoPuzzles] Blaze: secret trip over at tick {} with {} blaze(s) still alive - the final "
                    + "volley missed, shooting again", ticks, blazes.size());
        }
        if (!GUARD.fresh()) {
            return; // AutoGuard logs this one itself
        }
        if (McCompat.screen(client) != null) {
            say("waiting: a screen is open");
            return;
        }
        if (REPOSITION.isActive()) {
            REPOSITION.tick(client);
            return;
        }
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        boolean reposition = cfg.isEtherwarpReposition() && cr != null;
        boolean higher = HIGHER.equals(roomName);

        // Relative 75, not absolute 75: "am I up on Higher Blaze's top level yet". The sim shifts the whole
        // floor - and for a floor holding Higher Blaze it shifts it UP, to put the ceiling under the build
        // limit - so the bare literal is the wrong question in there. See AutoWater and AutoBeams for the same
        // fault; this one failed the other way round, skipping the reposition instead of repeating it.
        if (higher && player.getY() <= 75 + com.killer560.hub.livemap.DungeonLayout.simYOffset()) {
            if (reposition) {
                engageCamera(player);
                cyclePosition(client, player, blazes, cr, higher, true);
            } else {
                say("waiting: below Higher Blaze's top level and Etherwarp Reposition is off");
            }
            return;
        }

        long now = System.currentTimeMillis();
        Entity target = currentTarget;
        if (waitingForUpdate && (target == null || target.isRemoved())) {
            waitingForUpdate = false;
            currentTarget = null;
        }
        if (waitingForUpdate) {
            // The arrow's own flight, simulated at the shot, not distance / 2.5: a steep shot up the shaft spends most
            // of its time climbing, and the old estimate let a second shot go before the first had landed. When the
            // first then killed the blaze, the second flew on through the empty space and killed the next blaze up
            // out of order (93-solve, Mod Only Test captures, 2026-10-04: 28.8 blocks at pitch -84).
            if (now - lastShotTime > shotFlightMs) {
                waitingForUpdate = false;
            } else {
                return;
            }
        }

        Entity blaze = blazes.get(0);
        List<BlazeHitbox> hitboxes = hitboxes(blazes, blaze);
        if (blaze != loggedTarget) {
            loggedTarget = blaze;
            shotsHere = 0;
            badSpots.clear();
            LOGGER.info("[AutoPuzzles] Blaze: target 1 of {} is '{}', stand at {}, aiming at y {}", blazes.size(),
                    nameOf(blaze), fmt(blaze.position()), String.format("%.2f", hitboxes.get(0).aabb().getCenter().y));
        }
        boolean terminator = AutoPuzzleUtil.hasTerminator(player);
        float[] hitDir = canHit(client, player, player.getEyePosition(), hitboxes, terminator);
        BlockPos standing = BlockPos.containing(player.getX(), Math.ceil(player.getY() - 1.0), player.getZ());
        if (hitDir != null && shotsHere >= MAX_SHOTS_HERE && standing.equals(shotsFrom)) {
            // The arrows from here are not landing, whatever the simulation says. Count this spot out for this
            // blaze and move, rather than shoot at it for the rest of the run.
            LOGGER.info("[AutoPuzzles] Blaze: {} shots at '{}' from {} and it is still alive - trying another spot",
                    shotsHere, nameOf(blaze), AutoPuzzleUtil.fmt(standing));
            badSpots.add(standing);
            shotsHere = 0;
            hitDir = null;
        }
        if (hitDir == null && returnTo != null) {
            // Back from the secret with the last blaze still alive. The chest ledge is usually somewhere no listed spot
            // or searched ledge is one warp from (93-solve-blazemiss-higher, 2026-10-06: it stood there for a minute),
            // but the spot the final volley left from had a clean shot at this very blaze moments ago, and the walk
            // out came from there - so go back to it.
            if (AutoPuzzleUtil.at(player, returnTo) || returnPathIssued
                    && !com.killer560.hub.livemap.autoclear.ClearExecutor.isBusy()
                    || System.currentTimeMillis() - returnStartMs > SECRET_WALK_TIMEOUT_MS) {
                returnTo = null; // there, or the walk back is over - from here on the usual reposition decides
            } else if (com.killer560.hub.livemap.autoclear.ClearExecutor.isBusy()) {
                return; // the Interactive Map is walking him back
            } else {
                engageCamera(player);
                if (REPOSITION.start(client, returnTo, true, false, false)) {
                    say("going back to " + AutoPuzzleUtil.fmt(returnTo) + ", where the final volley was shot from");
                } else if (AutoPuzzleUtil.pathIfMapOn(returnTo, null, true)) {
                    returnPathIssued = true;
                    say("asking the Interactive Map to path back to " + AutoPuzzleUtil.fmt(returnTo)
                            + ", where the final volley was shot from");
                } else {
                    returnTo = null; // no way back from here: the usual reposition below takes over
                }
                if (returnTo != null) {
                    return;
                }
            }
        }
        if (hitDir != null) {
            returnTo = null;
        }
        if (hitDir == null) {
            if (blaze != loggedNoShot) {
                loggedNoShot = blaze;
                LOGGER.info("[AutoPuzzles] Blaze: no clean shot at '{}' from {} (terminator {}) - {}", nameOf(blaze),
                        fmt(player.position()), terminator, reposition ? "repositioning" : "reposition is off, waiting");
            }
            if (reposition) {
                engageCamera(player);
                cyclePosition(client, player, blazes, cr, higher, false);
            }
            return;
        }
        AutoPuzzleUtil.BowState bow = AutoPuzzleUtil.holdShortbow(client, player);
        if (bow != AutoPuzzleUtil.BowState.HELD) {
            if (!loggedNotShortbow) {
                loggedNotShortbow = true;
                LOGGER.info("[AutoPuzzles] Blaze: holding '{}', which is not a shortbow - {}",
                        player.getMainHandItem().getHoverName().getString(),
                        bow == AutoPuzzleUtil.BowState.NONE ? "and no shortbow in the hotbar, not shooting"
                                : "swapping to the shortbow");
            }
            return; // shoot on a later tick, once the swap has gone through
        }
        loggedNotShortbow = false;
        if (now - lastShotTime < cfg.getShootCooldownMs()) {
            return;
        }
        lastSaid = null;
        Vec3 eye = player.getEyePosition();
        Vec3 finalTarget = eye.add(AutoPuzzleUtil.look(hitDir[0], hitDir[1]).scale(10.0));
        float[] dir = AutoPuzzleUtil.direction(eye, finalTarget);
        engageCamera(player); // before the aim turns him
        if (!AutoPuzzleUtil.useItemRotated(client, player, dir[0], dir[1])) {
            return; // gate held this tick back - no shot, so lastShotTime / waitingForUpdate must not move
        }
        lastShotTime = now;
        waitingForUpdate = true;
        currentTarget = blaze;
        shotFlightMs = (flightTicks(AutoPuzzleUtil.arrowOrigin(eye, hitDir[0], terminator), hitDir[0], hitDir[1],
                hitboxes.get(0).aabb().getCenter()) + 4) * 50L;
        if (!standing.equals(shotsFrom)) {
            shotsFrom = standing;
            shotsHere = 0;
        }
        shotsHere++;
        LOGGER.info("[AutoPuzzles] Blaze: shot at '{}' from {}, yaw {} pitch {}, {} blocks", nameOf(blaze),
                fmt(eye), String.format("%.1f", dir[0]), String.format("%.1f", dir[1]),
                String.format("%.1f", eye.distanceTo(blaze.position())));
        if (resumePending) {
            resumePending = false;
            resumeTick = ticks;
        }
        if (blazes.size() == 1 && cfg.isAutoBlazeSecretEnabled() && secretStage == SecretStage.NONE) {
            earlyPending = true;
            finalReleaseTick = ticks;
            finalShotFrom = returnSpot(standing);
        }
    }

    /**
     * A block the Interactive Map's planner will take as a goal, at or right beside {@code standing} - where to go
     * back to after a miss. The block he stands on is not always one: the final volley of 93-solve-blazemiss-higher
     * left from a carpet-topped ledge the planner calls "not etherwarpable", and the walk back never started.
     */
    private static BlockPos returnSpot(BlockPos standing) {
        for (int dy : new int[]{0, -1, 1}) {
            for (int dx : new int[]{0, -1, 1}) {
                for (int dz : new int[]{0, -1, 1}) {
                    BlockPos b = standing.offset(dx, dy, dz);
                    if (com.killer560.hub.livemap.autoclear.EtherwarpPathfinder.isEtherwarpable(b)) {
                        return b;
                    }
                }
            }
        }
        return standing;
    }

    private static boolean secretTripActive() {
        return secretStage == SecretStage.FIND || secretStage == SecretStage.WALK || secretStage == SecretStage.AURA;
    }

    /**
     * For the testkit: {ticks, final release tick, secret walk start tick, secret aura tick, resume shot tick}, -1 for
     * what has not happened in this room visit.
     */
    static int[] testProbe() {
        return new int[]{ticks, finalReleaseTick, secretWalkTick, secretAuraTick, resumeTick};
    }

    /** For the testkit: {the secret, the block he walks to for it}, either null. */
    static BlockPos[] testSecretPos() {
        return new BlockPos[]{secretReal, standGoal()};
    }

    /** One INFO line per change of reason - every place this auto declines to act says why, once. */
    private static void say(String what) {
        if (what.equals(lastSaid)) {
            return;
        }
        lastSaid = what;
        LOGGER.info("[AutoPuzzles] Blaze: {}", what);
    }

    /** How long the last shot's arrow needs to reach its target, with a few ticks' margin - see the wait in tick(). */
    private static long shotFlightMs = 0L;

    /** Ticks until a 3.0 / 0.99 drag / 0.05 gravity arrow comes closest to {@code target} - QUOI's own flight model. */
    private static int flightTicks(Vec3 from, float yaw, float pitch, Vec3 target) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double px = from.x, py = from.y, pz = from.z;
        double mx = -Math.sin(yawRad) * Math.cos(pitchRad) * 3.0;
        double my = -Math.sin(pitchRad) * 3.0;
        double mz = Math.cos(yawRad) * Math.cos(pitchRad) * 3.0;
        double best = Double.MAX_VALUE;
        int bestTick = 0;
        for (int tick = 1; tick <= 100; tick++) {
            px += mx;
            py += my;
            pz += mz;
            double d = sq(px - target.x) + sq(py - target.y) + sq(pz - target.z);
            if (d > best) {
                break;
            }
            best = d;
            bestTick = tick;
            mx *= 0.99;
            my = my * 0.99 - 0.05;
            mz *= 0.99;
        }
        return bestTick;
    }

    private static String nameOf(Entity e) {
        String raw = e.getName().getString();
        String plain = net.minecraft.ChatFormatting.stripFormatting(raw);
        return plain != null ? plain : raw;
    }

    private static String fmt(Vec3 v) {
        return String.format("%.1f,%.1f,%.1f", v.x, v.y, v.z);
    }

    private static List<BlazeHitbox> hitboxes(List<Entity> blazes, Entity target) {
        List<BlazeHitbox> out = new ArrayList<>(blazes.size());
        for (Entity e : blazes) {
            boolean isTarget = e == target;
            Vec3 c = e.getBoundingBox().getCenter();
            double cy = c.y - 1.0;
            double w = isTarget ? 0.35 : 0.75;
            double h = isTarget ? 0.8 : 1.45;
            out.add(new BlazeHitbox(new AABB(c.x - w, cy - h, c.z - w, c.x + w, cy + h, c.z + w), isTarget));
        }
        return out;
    }

    private static float[] canHit(Minecraft client, LocalPlayer player, Vec3 eyePos, List<BlazeHitbox> hitboxes, boolean terminator) {
        BlazeHitbox target = null;
        for (BlazeHitbox h : hitboxes) {
            if (h.isTarget()) {
                target = h;
                break;
            }
        }
        if (target == null) {
            return null;
        }
        Vec3 c = target.aabb().getCenter();
        Vec3[] testPoints = {
                c, new Vec3(c.x, c.y + 0.6, c.z), new Vec3(c.x, c.y - 0.6, c.z),
                new Vec3(c.x + 0.2, c.y, c.z), new Vec3(c.x - 0.2, c.y, c.z),
                new Vec3(c.x, c.y, c.z + 0.2), new Vec3(c.x, c.y, c.z - 0.2)
        };
        for (Vec3 point : testPoints) {
            float[] dir = AutoPuzzleUtil.arrowDirection(eyePos, point, terminator);
            Vec3 origin = AutoPuzzleUtil.arrowOrigin(eyePos, dir[0], terminator);
            // The centre arrow must reach the target before anything else...
            if (!reachesTarget(client, player, origin, dir[0], dir[1], hitboxes)) {
                continue;
            }
            // ...and EVERY arrow the bow fires must then clear every other blaze for its whole flight, as if the
            // target were not there. Only one arrow is needed to kill it, and an arrow flies straight through a
            // blaze that is already dying: when one of a shot's three arrows killed the target, a sibling that had
            // been judged safe because it "hit the target" flew on up the shaft into the next blaze.
            if (!clearOfOthers(client, player, origin, dir[0], dir[1], hitboxes)) {
                continue;
            }
            if (terminator && (!clearOfOthers(client, player, origin, dir[0] + 5f, dir[1], hitboxes)
                    || !clearOfOthers(client, player, origin, dir[0] - 5f, dir[1], hitboxes))) {
                continue;
            }
            return dir;
        }
        return null;
    }

    /**
     * Whether an arrow fired with this yaw and pitch touches no blaze but the target from leaving the bow until it
     * lands in a block. The target is ignored rather than counted as the end of the flight - see {@link #canHit}.
     */
    private static boolean clearOfOthers(Minecraft client, LocalPlayer player, Vec3 from, float yaw, float pitch,
                                         List<BlazeHitbox> hitboxes) {
        double px = from.x, py = from.y, pz = from.z;
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double mx = -Math.sin(yawRad) * Math.cos(pitchRad) * 3.0;
        double my = -Math.sin(pitchRad) * 3.0;
        double mz = Math.cos(yawRad) * Math.cos(pitchRad) * 3.0;
        for (int tick = 0; tick <= 100; tick++) {
            Vec3 currPos = new Vec3(px, py, pz);
            Vec3 nextPos = new Vec3(px + mx, py + my, pz + mz);
            boolean lands = !AutoPuzzleUtil.isPathClear(client.level, player, currPos, nextPos);
            for (BlazeHitbox box : hitboxes) {
                // Checked on the landing step too: the arrow can cross a blaze before reaching the wall. (A blaze
                // behind the wall it lands in reads as a hit here - over-cautious, never unsafe.)
                if (!box.isTarget() && box.aabb().clip(currPos, nextPos).isPresent()) {
                    return false;
                }
            }
            if (lands) {
                return true;
            }
            px = nextPos.x;
            py = nextPos.y;
            pz = nextPos.z;
            mx *= 0.99;
            my = my * 0.99 - 0.05;
            mz *= 0.99;
        }
        return true;
    }

    /** QUOI's check for the centre arrow: it reaches the target's box before any other blaze or a block. */
    private static boolean reachesTarget(Minecraft client, LocalPlayer player, Vec3 from, float yaw, float pitch,
                                         List<BlazeHitbox> hitboxes) {
        BlazeHitbox target = null;
        for (BlazeHitbox h : hitboxes) {
            if (h.isTarget()) {
                target = h;
                break;
            }
        }
        if (target == null) {
            return false;
        }
        Vec3 center = target.aabb().getCenter();
        double dist = sq(center.x - from.x) + sq(center.z - from.z);
        double px = from.x, py = from.y, pz = from.z;
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double mx = -Math.sin(yawRad) * Math.cos(pitchRad) * 3.0;
        double my = -Math.sin(pitchRad) * 3.0;
        double mz = Math.cos(yawRad) * Math.cos(pitchRad) * 3.0;
        for (int tick = 0; tick <= 100; tick++) {
            Vec3 currPos = new Vec3(px, py, pz);
            Vec3 nextPos = new Vec3(px + mx, py + my, pz + mz);
            if (!AutoPuzzleUtil.isPathClear(client.level, player, currPos, nextPos)) {
                return false;
            }
            for (BlazeHitbox box : hitboxes) {
                if (box.aabb().clip(currPos, nextPos).isPresent()) {
                    return box.isTarget();
                }
            }
            px = nextPos.x;
            py = nextPos.y;
            pz = nextPos.z;
            double currDist = sq(px - from.x) + sq(pz - from.z);
            // Past the target and still flying: a miss. Where it goes from here is clearOfOthers' question.
            if (currDist > dist + 30.0) {
                break;
            }
            mx *= 0.99;
            my = my * 0.99 - 0.05;
            mz *= 0.99;
        }
        return false;
    }

    /**
     * Moves to one of QUOI's standing spots that has a clean shot at the next blaze.
     *
     * <p>Three faults made this a loop onto the same spot about four times a second (93-solve, 2026-10-04):
     * <ul>
     *   <li>the shot from a candidate spot was judged from {@code spot.getY() + 1.62} - but a spot is the block he
     *       stands ON, so that eye sat 0.38 below the top of the block, and a bow reposition arrives sneaking (QUOI
     *       keeps sneak held) with the eye 1.27 over the top. A spot could pass here and fail the very next tick
     *       from the spot itself;</li>
     *   <li>the spot he was already standing on was a candidate, so "warp onto where you stand" counted as a move;</li>
     *   <li>with no spot passing, it warped to the first visible spot as a "fallback" - every time, with the bow
     *       swapped out for the AOTV and nothing to swap it back, which is the "left holding the AOTV" report.</li>
     * </ul>
     * Now the eye is the real sneaking one, his own spot is skipped, and with no listed spot that has a shot the
     * room's own ledges are searched ({@link #searchRoom}); only when that runs out too does he stay put, with the
     * bow back in hand, and say so.
     */
    private static void cyclePosition(Minecraft client, LocalPlayer player, List<Entity> blazes, int[] cr, boolean higher,
                                      boolean mustMove) {
        if (REPOSITION.isActive() || blazes.isEmpty()) {
            return;
        }
        BlockPos[] spots = higher ? HIGHER_SPOTS : LOWER_SPOTS;
        List<BlazeHitbox> hitboxes = hitboxes(blazes, blazes.get(0));
        boolean terminator = AutoPuzzleUtil.hasTerminator(player);
        for (int j = 0; j < spots.length; j++) {
            int i = (currentSpot + j + 1) % spots.length;
            BlockPos realSpot = PuzzleCoords.real(spots[i], cr);
            if (AutoPuzzleUtil.at(player, realSpot) || badSpots.contains(realSpot)) {
                continue; // already here, and the shot from here was just found wanting
            }
            Vec3 spotEye = new Vec3(realSpot.getX() + 0.5, realSpot.getY() + 1 + AutoPuzzleUtil.EYE_SNEAKING,
                    realSpot.getZ() + 0.5);
            if (canHit(client, player, spotEye, hitboxes, terminator) == null) {
                continue;
            }
            if (AutoPuzzleUtil.etherwarpAim(client.level, player, realSpot) != null) {
                currentSpot = i;
                say("moving to standing spot " + i + " " + AutoPuzzleUtil.fmt(realSpot) + ", which has a shot at '"
                        + nameOf(blazes.get(0)) + "'");
                REPOSITION.start(client, realSpot, true, false, false);
                return;
            }
            for (BlockPos link : spots) {
                BlockPos realLink = PuzzleCoords.real(link, cr);
                if (AutoPuzzleUtil.at(player, realLink)
                        || AutoPuzzleUtil.etherwarpAim(client.level, player, realLink) == null) {
                    continue;
                }
                Vec3 linkEye = new Vec3(realLink.getX() + 0.5, realLink.getY() + 1 + AutoPuzzleUtil.EYE_SNEAKING,
                        realLink.getZ() + 0.5);
                if (com.killer560.hub.livemap.autoclear.TeleportUtils.getEtherwarpDirection(linkEye, realSpot, 57.0)
                        != null) {
                    currentSpot = i;
                    say("moving to standing spot " + AutoPuzzleUtil.fmt(realLink) + " on the way to spot " + i);
                    REPOSITION.start(client, realLink, true, false, false);
                    return;
                }
            }
        }
        if (mustMove) {
            // Below Higher Blaze's top level nothing can be shot from where he is, so any spot up there beats staying.
            for (int j = 0; j < spots.length; j++) {
                int i = (currentSpot + j + 1) % spots.length;
                BlockPos realSpot = PuzzleCoords.real(spots[i], cr);
                if (!AutoPuzzleUtil.at(player, realSpot)
                        && AutoPuzzleUtil.etherwarpAim(client.level, player, realSpot) != null) {
                    currentSpot = i;
                    say("climbing to standing spot " + i + " " + AutoPuzzleUtil.fmt(realSpot));
                    REPOSITION.start(client, realSpot, true, false, false);
                    return;
                }
            }
        }
        // None of QUOI's spots has a shot. Look through the room's own ledges, a few a tick.
        BlockPos found = searchRoom(client, player, blazes, hitboxes, terminator, higher);
        if (found != null) {
            say("no listed spot has a clean shot at '" + nameOf(blazes.get(0)) + "' - moving to "
                    + AutoPuzzleUtil.fmt(found) + ", found by searching the room");
            REPOSITION.start(client, found, true, false, false);
            return;
        }
        // Nowhere better. Stay, hold the bow, and wait for the solver's next scan rather than warping somewhere
        // with no shot.
        if (searchSpots != null && searchIndex >= searchSpots.size() && !farSpots.isEmpty()) {
            // Spots with a shot exist but no single warp reaches them (the shaft floor from the top landing). First a
            // two-warp route through any searched block that sees one of them, then - once per spot - the
            // Interactive Map's planner.
            for (BlockPos far : farSpots) {
                if (!AutoPuzzleUtil.at(player, far) && !badSpots.contains(far)
                        && AutoPuzzleUtil.etherwarpAim(client.level, player, far) != null) {
                    say("moving to " + AutoPuzzleUtil.fmt(far) + ", which has a shot at '" + nameOf(blazes.get(0)) + "'");
                    REPOSITION.start(client, far, true, false, false);
                    return;
                }
            }
            BlockPos hop = hopToFar(client, player);
            if (hop != null) {
                say("the only spots with a shot at '" + nameOf(blazes.get(0)) + "' are out of one warp's reach - "
                        + "warping to " + AutoPuzzleUtil.fmt(hop) + " first, which sees " + AutoPuzzleUtil.fmt(farSpots.get(farIdx)));
                REPOSITION.start(client, hop, true, false, false);
                return;
            }
            if (farIdx < farSpots.size()) {
                return; // the two-warp search is still going
            }
            if (com.killer560.hub.livemap.autoclear.ClearExecutor.isBusy()) {
                return;
            }
            for (BlockPos far : farSpots) {
                if (pathTried.add(far) && AutoPuzzleUtil.pathIfMapOn(far, null)) {
                    say("no two-warp route to a spot with a shot at '" + nameOf(blazes.get(0)) + "' - asking the "
                            + "Interactive Map to path to " + AutoPuzzleUtil.fmt(far));
                    return;
                }
            }
        }
        AutoPuzzleUtil.holdShortbow(client, player);
        if (searchSpots != null && searchIndex >= searchSpots.size()) {
            say("no standing spot in the room has a clean shot at '" + nameOf(blazes.get(0)) + "' - staying at "
                    + fmt(player.position()) + " and waiting");
        }
    }

    /** Candidate ledges for {@link #searchFor}, nearest first, and how far through them the search has got. */
    private static List<BlockPos> searchSpots = null;
    private static Entity searchFor = null;
    /** The nearest searched spot with a shot that no single warp from here reaches, for the planner. */
    private static final List<BlockPos> farSpots = new ArrayList<>();
    /** Far spot being tried by {@link #hopToFar}, and how far through {@link #searchSpots} that try has got. */
    private static int farIdx = 0;
    private static int hopIdx = 0;
    private static final java.util.Set<BlockPos> pathTried = new java.util.HashSet<>();
    private static final int MAX_FAR = 6;
    private static final int HOPS_PER_TICK = 40;

    /**
     * A searched block he can warp onto from which one of {@link #farSpots} can be warped onto, or null (still
     * searching, or none). {@value #HOPS_PER_TICK} candidates a tick, far spots in order.
     */
    private static BlockPos hopToFar(Minecraft client, LocalPlayer player) {
        int budget = HOPS_PER_TICK;
        while (farIdx < farSpots.size() && budget > 0) {
            BlockPos far = farSpots.get(farIdx);
            while (hopIdx < searchSpots.size() && budget-- > 0) {
                BlockPos via = searchSpots.get(hopIdx++);
                if (via.equals(far) || AutoPuzzleUtil.at(player, via)) {
                    continue;
                }
                Vec3 eye = new Vec3(via.getX() + 0.5, via.getY() + 1 + AutoPuzzleUtil.EYE_SNEAKING, via.getZ() + 0.5);
                if (com.killer560.hub.livemap.autoclear.TeleportUtils.getEtherwarpDirection(eye, far, 57.0, false) != null
                        && AutoPuzzleUtil.etherwarpAim(client.level, player, via) != null) {
                    return via;
                }
            }
            if (hopIdx >= searchSpots.size()) {
                farIdx++;
                hopIdx = 0;
            }
        }
        return null;
    }
    private static int searchIndex = 0;
    /** Candidates judged per tick: each is up to 21 simulated arrow flights. */
    private static final int SEARCH_PER_TICK = 12;

    /**
     * Any standable block in the room he can etherwarp onto and shoot the next blaze cleanly from.
     *
     * <p>QUOI's six Lower and four Higher spots were picked for the blazes where QUOI's authors met them. When none
     * of them has a clean shot - in the 93-solve run of 2026-10-04 the fifth blaze of Lower Blaze floated a block
     * over the shaft floor, below the lowest listed spot, and Auto Blaze stood still for 55 s - this walks the room's
     * own standable blocks (solid, two clear above) within 24 blocks of the blaze and 14 below to 24 above it,
     * nearest first, judging {@value #SEARCH_PER_TICK} a tick with the same {@link #canHit} the listed spots get. The
     * list is built once per target. Returns null while the search is still going or when it has run out.
     */
    private static BlockPos searchRoom(Minecraft client, LocalPlayer player, List<Entity> blazes,
                                       List<BlazeHitbox> hitboxes, boolean terminator, boolean higher) {
        Entity target = blazes.get(0);
        if (target != searchFor || searchSpots == null) {
            searchFor = target;
            searchIndex = 0;
            searchSpots = candidates(client, target, higher);
            farSpots.clear();
            farIdx = 0;
            hopIdx = 0;
            pathTried.clear();
            LOGGER.info("[AutoPuzzles] Blaze: searching {} standable block(s) in the room for a shot at '{}'",
                    searchSpots.size(), nameOf(target));
        }
        int end = Math.min(searchSpots.size(), searchIndex + SEARCH_PER_TICK);
        for (; searchIndex < end; searchIndex++) {
            BlockPos spot = searchSpots.get(searchIndex);
            if (AutoPuzzleUtil.at(player, spot) || badSpots.contains(spot)) {
                continue;
            }
            Vec3 eye = new Vec3(spot.getX() + 0.5, spot.getY() + 1 + AutoPuzzleUtil.EYE_SNEAKING, spot.getZ() + 0.5);
            if (canHit(client, player, eye, hitboxes, terminator) != null) {
                if (AutoPuzzleUtil.etherwarpAim(client.level, player, spot) != null) {
                    searchIndex++;
                    return spot;
                }
                if (farSpots.size() < MAX_FAR) {
                    farSpots.add(spot);
                }
            }
        }
        return null;
    }

    private static List<BlockPos> candidates(Minecraft client, Entity target, boolean higher) {
        List<BlockPos> out = new ArrayList<>();
        int idx = LiveMapFeature.currentRoomIndex();
        int[] bounds = idx < 0 ? null : LiveMapFeature.roomWorldBounds(idx);
        if (bounds == null || client.level == null) {
            return out;
        }
        BlockPos t = target.blockPosition();
        int minY = t.getY() - 14;
        if (higher) {
            // Never below Higher Blaze's top level: tick() sends him straight back up from there, and a spot down
            // in the shaft turned the two into a ping-pong (93-solve, 2026-10-04).
            minY = Math.max(minY, 75 + com.killer560.hub.livemap.DungeonLayout.simYOffset());
        }
        int maxY = t.getY() + 24;
        for (int x = Math.max(bounds[0], t.getX() - 24); x <= Math.min(bounds[2], t.getX() + 24); x++) {
            for (int z = Math.max(bounds[1], t.getZ() - 24); z <= Math.min(bounds[3], t.getZ() + 24); z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (AutoPuzzleUtil.isPassable(client.level.getBlockState(pos))
                            || !client.level.getBlockState(pos.above()).isAir()
                            || !client.level.getBlockState(pos.above(2)).isAir()) {
                        continue;
                    }
                    out.add(pos);
                }
            }
        }
        out.sort(java.util.Comparator.comparingDouble(b -> b.distSqr(t)));
        return out;
    }

    private static double sq(double v) {
        return v * v;
    }

    /**
     * Takes the run's free camera, once, before its first aim - facing the room's horizontal centre (killer560,
     * 2026-09-27: "for blaze by default can you have my characters head face more towards the middle when put into
     * the freecam view"), or where he is looking when the room's bounds are not known.
     * <p>
     * This used to be {@code seedDefaultView}, called EVERY tick and holding a fresh 400 ms lease whenever none was
     * held. Nothing renewed that lease, so it lapsed every 400 ms and was immediately re-seeded: the camera dropped
     * onto his body (facing the last blaze) for a frame and was then thrown back to the room centre, wiping out
     * whatever his mouse had done - half of the "snaps my camera around everywhere" of 2026-10-06.
     */
    private static void engageCamera(LocalPlayer player) {
        if (CAMERA.isEngaged()) {
            return;
        }
        int idx = LiveMapFeature.currentRoomIndex();
        int[] bounds = idx < 0 ? null : LiveMapFeature.roomWorldBounds(idx);
        if (bounds == null) {
            CAMERA.engage(player);
            return;
        }
        double centerX = (bounds[0] + bounds[2]) / 2.0;
        double centerZ = (bounds[1] + bounds[3]) / 2.0;
        Vec3 eye = player.getEyePosition();
        float[] dir = AutoPuzzleUtil.direction(eye, new Vec3(centerX, eye.y, centerZ));
        // On his running yaw, not the wrapped one, so the held view does not start a whole turn away from his body.
        CAMERA.engage(player, player.getYRot() + Mth.wrapDegrees(dir[0] - player.getYRot()), dir[1]);
    }

    // ------------------------------------------------------------------ Auto Secret (killer560, 2026-09-27)

    private static void tickSecret(Minecraft client, LocalPlayer player, List<Entity> blazes) {
        if (McCompat.screen(client) != null) {
            return;
        }
        switch (secretStage) {
            case FIND -> {
                findSecret(client, player);
                if (secretStage == SecretStage.WALK) {
                    walkToSecret(client, player); // same tick: the walk starts the tick the secret is picked
                }
            }
            case WALK -> walkToSecret(client, player);
            case AURA -> auraSecret(client, player, blazes);
            default -> {
            }
        }
    }

    private static void findSecret(Minecraft client, LocalPlayer player) {
        RoomEntry entry = LiveMapFeature.currentRoomEntry();
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        if (entry == null || cr == null) {
            return; // room identity/rotation not known yet - retry next tick
        }
        List<SecretCandidate> candidates = new ArrayList<>();
        if (entry.secretCoords != null) {
            addCandidates(candidates, entry.secretCoords.chest, true);
            addCandidates(candidates, entry.secretCoords.item, false);
            addCandidates(candidates, entry.secretCoords.wither, false);
            addCandidates(candidates, entry.secretCoords.bat, false);
        }
        if (candidates.isEmpty()) {
            if (!noSecretWarned) {
                noSecretWarned = true;
                LOGGER.warn("[AutoPuzzles] Blaze: no secret coordinates known for this room in the room database "
                        + "- Auto Secret can't find one, stopping for this room visit");
                ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Auto Blaze: "),
                        ModChat.bad("no secret position known"), ModChat.text(" for this room."));
            }
            secretStage = SecretStage.DONE;
            return;
        }
        BlockPos bestReal = null;
        boolean bestIsChest = false;
        double bestDistSq = Double.MAX_VALUE;
        for (SecretCandidate c : candidates) {
            // PuzzleCoords, not RoomDatabase - see its RoomEntry.Pos overload: the raw call drops the sim's
            // floor shift, so this picked "nearest secret" by distance to a point at Hypixel's height.
            BlockPos real = PuzzleCoords.real(c.relative(), cr);
            double distSq = player.position().distanceToSqr(Vec3.atCenterOf(real));
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestReal = real;
                bestIsChest = c.chest();
            }
        }
        secretReal = bestReal;
        secretIsChest = bestIsChest;
        secretLegStartMs = System.currentTimeMillis();
        standSpots = secretIsChest ? auraSpots(client, player, secretReal) : List.of();
        standIdx = 0;
        walkIssued = false;
        auraWaitStartMs = 0L;
        directTried.clear();
        LOGGER.info("[AutoPuzzles] Blaze: secret {} at {}; {} standable block(s) within aura reach, walking to {}",
                secretIsChest ? "chest" : "item", AutoPuzzleUtil.fmt(secretReal), standSpots.size(),
                AutoPuzzleUtil.fmt(standGoal()));
        secretStage = SecretStage.WALK;
    }

    /** The block the walk is heading for: the best aura spot still in play, or the secret itself. */
    private static BlockPos standGoal() {
        return standIdx < standSpots.size() ? standSpots.get(standIdx) : secretReal;
    }

    /**
     * Standable blocks (solid, two clear above, inside the room) from which BOTH his standing and his sneaking eye
     * reach the chest's box within {@link #AURA_REACH_SQ} - the measured 4.5-block reach Secret Aura is capped at and
     * {@link #auraSecret} clicks with - and see it unobstructed, ordered by distance to the room's exit: the door he
     * will leave by, so the trip ends on the way out rather than on top of the chest.
     */
    private static List<BlockPos> auraSpots(Minecraft client, LocalPlayer player, BlockPos chest) {
        List<BlockPos> out = new ArrayList<>();
        if (client.level == null) {
            return out;
        }
        int idx = LiveMapFeature.currentRoomIndex();
        int[] bounds = idx < 0 ? null : LiveMapFeature.roomWorldBounds(idx);
        Vec3 exit = exitPoint(player);
        Vec3 chestCentre = Vec3.atCenterOf(chest);
        int r = (int) Math.ceil(Math.sqrt(AURA_REACH_SQ)) + 1;
        for (int x = chest.getX() - r; x <= chest.getX() + r; x++) {
            for (int z = chest.getZ() - r; z <= chest.getZ() + r; z++) {
                if (bounds != null && (x < bounds[0] || x > bounds[2] || z < bounds[1] || z > bounds[3])) {
                    continue;
                }
                for (int y = chest.getY() - r - 1; y <= chest.getY() + r - 1; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (pos.equals(chest) || AutoPuzzleUtil.isPassable(client.level.getBlockState(pos))
                            || !client.level.getBlockState(pos.above()).isAir()
                            || !client.level.getBlockState(pos.above(2)).isAir()
                            || !com.killer560.hub.livemap.autoclear.EtherwarpPathfinder.isEtherwarpable(pos)) {
                        continue;
                    }
                    if (!reachesChest(client, player, pos, AutoPuzzleUtil.EYE_SNEAKING, chest, chestCentre)
                            || !reachesChest(client, player, pos, 1.62, chest, chestCentre)) {
                        continue;
                    }
                    out.add(pos);
                }
            }
        }
        out.sort(java.util.Comparator.comparingDouble((BlockPos b) -> Vec3.atCenterOf(b).distanceToSqr(exit))
                .thenComparingDouble(b -> b.distSqr(chest)));
        return out;
    }

    private static boolean reachesChest(Minecraft client, LocalPlayer player, BlockPos stand, double eyeHeight,
                                        BlockPos chest, Vec3 chestCentre) {
        Vec3 eye = new Vec3(stand.getX() + 0.5, stand.getY() + 1 + eyeHeight, stand.getZ() + 0.5);
        if (com.killer560.hub.util.BlockHits.boxDistanceSq(eye, chest) > AURA_REACH_SQ) {
            return false;
        }
        net.minecraft.world.phys.HitResult hit = client.level.clip(new net.minecraft.world.level.ClipContext(eye,
                chestCentre, net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS
                || (hit instanceof net.minecraft.world.phys.BlockHitResult b && b.getBlockPos().equals(chest));
    }

    /**
     * Where he leaves the room: the centre of the room's door nearest where he came in (a puzzle room has one door),
     * or where he came in when the map knows no door, or where he stands.
     */
    private static Vec3 exitPoint(LocalPlayer player) {
        Vec3 from = entryPos != null ? entryPos : player.position();
        int idx = LiveMapFeature.currentRoomIndex();
        if (idx < 0) {
            return from;
        }
        com.killer560.hub.livemap.DungeonLayout layout = com.killer560.hub.livemap.DungeonLayout.capture();
        int grid = com.killer560.hub.livemap.DungeonLayout.GRID;
        int gx = idx % grid;
        int gz = idx / grid;
        int[][] around = {{gx + 1, gz}, {gx - 1, gz}, {gx, gz + 1}, {gx, gz - 1}};
        Vec3 best = null;
        for (int[] c : around) {
            if (c[0] < 0 || c[0] >= grid || c[1] < 0 || c[1] >= grid) {
                continue;
            }
            int door = c[1] * grid + c[0];
            if (!layout.isDoor(door)) {
                continue;
            }
            Vec3 centre = com.killer560.hub.livemap.DungeonLayout.doorCentre(door);
            if (best == null || centre.distanceToSqr(from) < best.distanceToSqr(from)) {
                best = centre;
            }
        }
        return best != null ? best : from;
    }

    private static void addCandidates(List<SecretCandidate> out, List<RoomEntry.Pos> src, boolean chest) {
        if (src == null) {
            return;
        }
        for (RoomEntry.Pos p : src) {
            out.add(new SecretCandidate(p, chest));
        }
    }

    private static void walkToSecret(Minecraft client, LocalPlayer player) {
        if (secretReal == null) {
            secretStage = SecretStage.DONE;
            return;
        }
        if (SECRET_WARP.isActive()) {
            SECRET_CAM.keep(player);
            SECRET_WARP.tick(client);
            if (SECRET_WARP.isActive()) {
                return;
            }
            SECRET_CAM.release(player);
        }
        BlockPos goal = standGoal();
        boolean busy = com.killer560.hub.livemap.autoclear.ClearExecutor.isBusy();
        boolean inReach = secretIsChest && !busy && com.killer560.hub.util.BlockHits.boxDistanceSq(
                player.getEyePosition(), secretReal) <= AURA_REACH_SQ;
        if (AutoPuzzleUtil.at(player, goal) || inReach) {
            secretMapOffWarned = false;
            if (busy) {
                // On the block while the walk is still settling (an "off the plan" re-plan warped him off it again
                // after the chest was taken, 93-solve-blazemiss-higher): he is where he needs to be, so it is over.
                com.killer560.hub.livemap.autoclear.ClearExecutor.cancel();
            }
            LOGGER.info("[AutoPuzzles] Blaze: at {} for the secret at {} (tick {})", fmt(player.position()),
                    AutoPuzzleUtil.fmt(secretReal), ticks);
            secretStage = secretIsChest ? SecretStage.AURA : SecretStage.DONE;
            return;
        }
        if (busy) {
            return; // already walking there
        }
        if (System.currentTimeMillis() - secretLegStartMs > SECRET_WALK_TIMEOUT_MS) {
            LOGGER.warn("[AutoPuzzles] Blaze: walk to the secret at {} timed out - giving up for this room", secretReal);
            secretStage = SecretStage.DONE;
            return;
        }
        // The last leg is one etherwarp of the auto's own onto the best-exit spot it can see from here: the Interactive
        // Map's planner works on a coarse graph, and for a ledge block it often lands "near" it - up to five blocks
        // off, out of the chest's reach (93-solve-higherblaze, 2026-10-06: five plans in a row before one landed).
        if (AutoPuzzlesConfig.getInstance().isAutoPuzzlePathingEnabled()) {
            for (BlockPos spot : standSpots) {
                if (directTried.contains(spot) || AutoPuzzleUtil.etherwarpAim(client.level, player, spot) == null) {
                    continue;
                }
                directTried.add(spot);
                if (SECRET_WARP.start(client, spot, false, false, false)) {
                    if (secretWalkTick < 0) {
                        secretWalkTick = ticks;
                    }
                    LOGGER.info("[AutoPuzzles] Blaze: warping onto {} for the secret (tick {})", AutoPuzzleUtil.fmt(spot),
                            ticks);
                    return;
                }
            }
        }
        if (walkIssued && standIdx < standSpots.size()) {
            // The walk to that block is over and did not bring him within reach (no path, or the planner's "near"
            // landing beside it): the next best one, planned from where he is now.
            LOGGER.info("[AutoPuzzles] Blaze: {} {} - trying the next spot in aura reach",
                    com.killer560.hub.livemap.autoclear.ClearExecutor.lastPathFailed() ? "no path to" : "did not reach",
                    AutoPuzzleUtil.fmt(goal));
            standIdx++;
            goal = standGoal();
        }
        walkIssued = AutoPuzzleUtil.pathIfMapOn(goal, null, secretIsChest);
        if (walkIssued && secretWalkTick < 0) {
            secretWalkTick = ticks;
            LOGGER.info("[AutoPuzzles] Blaze: walking to {} for the secret (tick {})", AutoPuzzleUtil.fmt(goal), ticks);
        }
        if (!walkIssued && !secretMapOffWarned) {
            secretMapOffWarned = true;
            ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Auto Secret needs "), ModChat.value("Interactive Map"),
                    ModChat.text(" on to walk to it."));
        }
    }

    private static void auraSecret(Minecraft client, LocalPlayer player, List<Entity> blazes) {
        if (secretAuraAttempts >= MAX_AURA_ATTEMPTS) {
            secretStage = SecretStage.DONE;
            return;
        }
        long now = System.currentTimeMillis();
        if (auraWaitStartMs == 0L) {
            auraWaitStartMs = now;
        }
        boolean alive = !blazes.isEmpty();
        if (alive && now - lastShotTime <= shotFlightMs) {
            return; // the final volley is still in the air: a chest may only open once the room is done
        }
        BlockPos target = AutoPuzzleUtil.nearestChest(client, player, AURA_REACH_SQ);
        if (target == null) {
            target = secretReal;
        }
        net.minecraft.world.level.block.state.BlockState state = client.level.getBlockState(target);
        boolean chestThere = state.is(net.minecraft.world.level.block.Blocks.CHEST)
                || state.is(net.minecraft.world.level.block.Blocks.TRAPPED_CHEST);
        if (!chestThere) {
            if (alive) {
                // Missed, and no chest until the room is done: shoot again, and come back for it at the end.
                LOGGER.info("[AutoPuzzles] Blaze: no chest at {} yet and a blaze is still alive - shooting again, the "
                        + "secret is fetched once the room is done", AutoPuzzleUtil.fmt(target));
                secretStage = SecretStage.NONE;
                return;
            }
            if (now - auraWaitStartMs > CHEST_WAIT_MS) {
                LOGGER.warn("[AutoPuzzles] Blaze: no chest appeared at {} - giving up for this room",
                        AutoPuzzleUtil.fmt(target));
                secretStage = SecretStage.DONE;
            }
            return; // arrived early: wait for it
        }
        if (player.isShiftKeyDown()) {
            // The etherwarp landing's sneak lets go a tick or two after the walk ends; a sneaking click on a chest
            // with an item in hand does not open it, so wait rather than burn an attempt.
            if (now - auraWaitStartMs > CHEST_WAIT_MS) {
                secretAuraAttempts++;
            }
            return;
        }
        // To the box, like the picker above - measuring the gate one way and the choice another is how a
        // module ends up clicking at something it cannot reach.
        double distSq = com.killer560.hub.util.BlockHits.boxDistanceSq(player.getEyePosition(), target);
        if (distSq > AURA_REACH_SQ) {
            secretAuraAttempts++;
            return;
        }
        if (!AutoPuzzleUtil.gateWorldClick(client, target)) {
            return; // gate held this tick back (or the body was turned to it) - not burnt, retried next tick
        }
        secretAuraAttempts++;
        if (!AutoPuzzleUtil.interactBlock(client, target)) {
            LOGGER.warn("[AutoPuzzles] Blaze: no clickable shape at {} (attempt {}/{})", target, secretAuraAttempts, MAX_AURA_ATTEMPTS);
            return;
        }
        ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Higher/Lower: aura'd the "), ModChat.good("secret"),
                ModChat.text("."));
        secretAuraTick = ticks;
        LOGGER.info("[AutoPuzzles] Blaze: aura'd the secret at {} from {} (tick {})", AutoPuzzleUtil.fmt(target),
                fmt(player.position()), ticks);
        secretStage = SecretStage.DONE;
    }

    private static void reset(Minecraft client) {
        REPOSITION.cancel(client);
        AutoReposition.releaseSneak(client);
        CAMERA.release(client.player);
        lastShotTime = 0L;
        waitingForUpdate = false;
        currentTarget = null;
        currentSpot = 0;
        lastBlazeCount = 0;
        shotsHere = 0;
        shotsFrom = null;
        badSpots.clear();
        searchSpots = null;
        farSpots.clear();
        farIdx = 0;
        hopIdx = 0;
        pathTried.clear();
        searchFor = null;
        searchIndex = 0;
        emptyTicks = 0;
        lastSaid = null;
        loggedTarget = null;
        loggedNoShot = null;
        loggedNotShortbow = false;
        secretStage = SecretStage.NONE;
        secretReal = null;
        secretIsChest = false;
        secretAuraAttempts = 0;
        secretLegStartMs = 0L;
        noSecretWarned = false;
        secretMapOffWarned = false;
        earlyPending = false;
        ticks = 0;
        finalReleaseTick = -1;
        secretWalkTick = -1;
        secretAuraTick = -1;
        resumeTick = -1;
        resumePending = false;
        standSpots = List.of();
        standIdx = 0;
        walkIssued = false;
        auraWaitStartMs = 0L;
        SECRET_WARP.cancel(client);
        SECRET_CAM.release(client.player);
        directTried.clear();
        finalShotFrom = null;
        returnTo = null;
        returnStartMs = 0L;
    }
}
