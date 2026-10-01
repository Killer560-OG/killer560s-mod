package com.killer560.hub.autopuzzles;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.puzzlesolvers.BlazeSolverConfig;
import com.killer560.hub.puzzlesolvers.BlazeSolverFeature;
import com.killer560.hub.puzzlesolvers.PuzzleCoords;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ViewFreeze;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
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
 * accept the first whose simulated arrow reaches the target before any other blaze or a block (Terminator: the
 * +-5 degree side arrows must not hit another blaze either); shoot with the held shortbow once the Shoot cooldown
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
 *   freecam view" - {@link #seedDefaultView} pre-seeds {@link ViewFreeze}'s held view (before anything else in
 *   this room gets a chance to seed it from wherever the player happened to be looking) to face the room's own
 *   horizontal centre, using {@link LiveMapFeature#roomWorldBounds}.</li>
 * </ul>
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
    private static final AutoReposition REPOSITION = new AutoReposition("Blaze");

    private static long lastShotTime = 0L;
    private static boolean waitingForUpdate = false;
    private static Entity currentTarget = null;
    private static int currentSpot = 0;
    private static boolean wasInRoom = false;
    private static int lastBlazeCount = 0;

    private enum SecretStage { NONE, FIND, WALK, AURA, DONE }

    private static SecretStage secretStage = SecretStage.NONE;
    private static BlockPos secretReal = null;
    private static boolean secretIsChest = false;
    private static int secretAuraAttempts = 0;
    private static long secretLegStartMs = 0L;
    private static boolean noSecretWarned = false;
    private static boolean secretMapOffWarned = false;

    private AutoBlaze() {
    }

    static void levelChanged(Minecraft client) {
        GUARD.levelChanged();
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
        wasInRoom = true;
        if (!GUARD.solverOn(BlazeSolverConfig.getInstance().isEnabled())) {
            return;
        }
        LocalPlayer player = client.player;
        // killer560, 2026-09-27: "have my character's head face more towards the middle when put into the freecam
        // view" - seed the held view BEFORE the first rotation of this run gets a chance to anchor it to wherever
        // the player happened to be looking (ViewFreeze.hold is a no-op past the first call of a run).
        seedDefaultView(player);
        if (blazes.isEmpty()) {
            if (lastBlazeCount > 0) {
                ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Blaze: "), ModChat.good("done"), ModChat.text("."));
                REPOSITION.cancel(client);
                AutoReposition.releaseSneak(client);
                if (cfg.isAutoBlazeSecretEnabled() && secretStage == SecretStage.NONE) {
                    secretStage = SecretStage.FIND;
                }
            }
            lastBlazeCount = 0;
            if (secretStage != SecretStage.NONE && secretStage != SecretStage.DONE) {
                tickSecret(client, player);
            }
            return;
        }
        lastBlazeCount = blazes.size();
        if (!GUARD.fresh() || McCompat.screen(client) != null) {
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
                cyclePosition(client, player, blazes, cr, higher);
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
            double dist = target.position().distanceTo(player.position());
            double travelTime = dist / 2.5 * 50.0;
            if (now - lastShotTime > travelTime) {
                waitingForUpdate = false;
            } else {
                return;
            }
        }

        Entity blaze = blazes.get(0);
        List<BlazeHitbox> hitboxes = hitboxes(blazes, blaze);
        boolean terminator = AutoPuzzleUtil.hasTerminator(player);
        float[] hitDir = canHit(client, player, player.getEyePosition(), hitboxes, terminator);
        if (hitDir == null) {
            if (reposition) {
                cyclePosition(client, player, blazes, cr, higher);
            }
            return;
        }
        if (!AutoPuzzleUtil.isShortbow(player.getMainHandItem())) {
            return;
        }
        if (now - lastShotTime < cfg.getShootCooldownMs()) {
            return;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 finalTarget = eye.add(AutoPuzzleUtil.look(hitDir[0], hitDir[1]).scale(10.0));
        float[] dir = AutoPuzzleUtil.direction(eye, finalTarget);
        if (!AutoPuzzleUtil.useItemRotated(client, player, dir[0], dir[1])) {
            return; // gate held this tick back - no shot, so lastShotTime / waitingForUpdate must not move
        }
        lastShotTime = now;
        waitingForUpdate = true;
        currentTarget = blaze;
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
            if (isSafe(client, player, origin, dir[0], dir[1], hitboxes, false)) {
                if (!terminator || (isSafe(client, player, origin, dir[0] + 5f, dir[1], hitboxes, true)
                        && isSafe(client, player, origin, dir[0] - 5f, dir[1], hitboxes, true))) {
                    return dir;
                }
            }
        }
        return null;
    }

    private static boolean isSafe(Minecraft client, LocalPlayer player, Vec3 from, float yaw, float pitch,
                                  List<BlazeHitbox> hitboxes, boolean sideArrow) {
        BlazeHitbox target = null;
        for (BlazeHitbox h : hitboxes) {
            if (h.isTarget()) {
                target = h;
                break;
            }
        }
        if (target == null) {
            return sideArrow;
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
                return sideArrow;
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
            if (currDist > dist + 30.0) {
                break;
            }
            mx *= 0.99;
            my = my * 0.99 - 0.05;
            mz *= 0.99;
        }
        return sideArrow;
    }

    private static void cyclePosition(Minecraft client, LocalPlayer player, List<Entity> blazes, int[] cr, boolean higher) {
        if (REPOSITION.isActive() || blazes.isEmpty()) {
            return;
        }
        BlockPos[] spots = higher ? HIGHER_SPOTS : LOWER_SPOTS;
        List<BlazeHitbox> hitboxes = hitboxes(blazes, blazes.get(0));
        boolean terminator = AutoPuzzleUtil.hasTerminator(player);
        BlockPos fallback = null;
        for (int j = 0; j < spots.length; j++) {
            int i = (currentSpot + j + 1) % spots.length;
            BlockPos realSpot = PuzzleCoords.real(spots[i], cr);
            Vec3 spotEye = new Vec3(realSpot.getX() + 0.5, realSpot.getY() + 1.62, realSpot.getZ() + 0.5);
            float[] dir = AutoPuzzleUtil.etherwarpDirection(client.level, player, realSpot);
            if (fallback == null && dir != null) {
                fallback = realSpot;
            }
            if (canHit(client, player, spotEye, hitboxes, terminator) == null) {
                continue;
            }
            if (dir != null) {
                currentSpot = i;
                REPOSITION.start(client, realSpot, true, false, false);
                return;
            }
            for (BlockPos link : spots) {
                BlockPos realLink = PuzzleCoords.real(link, cr);
                if (AutoPuzzleUtil.etherwarpDirection(client.level, player, realLink) == null) {
                    continue;
                }
                Vec3 linkEye = new Vec3(realLink.getX() + 0.5, realLink.getY() + 1.62, realLink.getZ() + 0.5);
                if (AutoPuzzleUtil.etherwarpDirection(client.level, linkEye, realSpot, 61.0) != null) {
                    currentSpot = i;
                    REPOSITION.start(client, realLink, false, false, false);
                    return;
                }
            }
        }
        if (fallback != null) {
            REPOSITION.start(client, fallback, false, false, false);
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    /** Room-centre yaw/pitch, seeded into {@link ViewFreeze} only on the FIRST hold of a run (see its own doc) -
     *  calling this every tick is harmless, it only actually moves anything the very first time. */
    private static void seedDefaultView(LocalPlayer player) {
        if (ViewFreeze.isHeld()) {
            return;
        }
        int idx = LiveMapFeature.currentRoomIndex();
        int[] bounds = idx < 0 ? null : LiveMapFeature.roomWorldBounds(idx);
        if (bounds == null) {
            return;
        }
        double centerX = (bounds[0] + bounds[2]) / 2.0;
        double centerZ = (bounds[1] + bounds[3]) / 2.0;
        Vec3 eye = player.getEyePosition();
        float[] dir = AutoPuzzleUtil.direction(eye, new Vec3(centerX, eye.y, centerZ));
        ViewFreeze.hold(dir[0], dir[1]);
    }

    // ------------------------------------------------------------------ Auto Secret (killer560, 2026-09-27)

    private static void tickSecret(Minecraft client, LocalPlayer player) {
        if (McCompat.screen(client) != null) {
            return;
        }
        switch (secretStage) {
            case FIND -> findSecret(player);
            case WALK -> walkToSecret(client, player);
            case AURA -> auraSecret(client, player);
            default -> {
            }
        }
    }

    private static void findSecret(LocalPlayer player) {
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
        secretStage = SecretStage.WALK;
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
        if (AutoPuzzleUtil.at(player, secretReal)) {
            secretMapOffWarned = false;
            secretStage = secretIsChest ? SecretStage.AURA : SecretStage.DONE;
            return;
        }
        if (com.killer560.hub.livemap.autoclear.ClearExecutor.isBusy()) {
            return; // already walking there
        }
        if (System.currentTimeMillis() - secretLegStartMs > SECRET_WALK_TIMEOUT_MS) {
            LOGGER.warn("[AutoPuzzles] Blaze: walk to the secret at {} timed out - giving up for this room", secretReal);
            secretStage = SecretStage.DONE;
            return;
        }
        if (!AutoPuzzleUtil.pathIfMapOn(secretReal, null) && !secretMapOffWarned) {
            secretMapOffWarned = true;
            ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Auto Secret needs "), ModChat.value("Interactive Map"),
                    ModChat.text(" on to walk to it."));
        }
    }

    private static void auraSecret(Minecraft client, LocalPlayer player) {
        if (secretAuraAttempts >= MAX_AURA_ATTEMPTS) {
            secretStage = SecretStage.DONE;
            return;
        }
        BlockPos target = AutoPuzzleUtil.nearestChest(client, player, AURA_REACH_SQ);
        if (target == null) {
            target = secretReal;
        }
        // To the box, like the picker above - measuring the gate one way and the choice another is how a
        // module ends up clicking at something it cannot reach.
        double distSq = com.killer560.hub.util.BlockHits.boxDistanceSq(player.getEyePosition(), target);
        if (player.isShiftKeyDown() || distSq > AURA_REACH_SQ) {
            secretAuraAttempts++;
            return;
        }
        if (!AutoPuzzleUtil.gateWorldClick()) {
            return; // gate held this tick back - not burnt, retried next tick
        }
        secretAuraAttempts++;
        if (!AutoPuzzleUtil.interactBlock(client, target)) {
            LOGGER.warn("[AutoPuzzles] Blaze: no clickable shape at {} (attempt {}/{})", target, secretAuraAttempts, MAX_AURA_ATTEMPTS);
            return;
        }
        ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Higher/Lower: aura'd the "), ModChat.good("secret"),
                ModChat.text("."));
        secretStage = SecretStage.DONE;
    }

    private static void reset(Minecraft client) {
        REPOSITION.cancel(client);
        AutoReposition.releaseSneak(client);
        lastShotTime = 0L;
        waitingForUpdate = false;
        currentTarget = null;
        currentSpot = 0;
        lastBlazeCount = 0;
        secretStage = SecretStage.NONE;
        secretReal = null;
        secretIsChest = false;
        secretAuraAttempts = 0;
        secretLegStartMs = 0L;
        noSecretWarned = false;
        secretMapOffWarned = false;
    }
}
