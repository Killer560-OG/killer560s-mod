package com.killer560.hub.autopuzzles;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.puzzlesolvers.BlazeSolverConfig;
import com.killer560.hub.puzzlesolvers.BlazeSolverFeature;
import com.killer560.hub.puzzlesolvers.PuzzleCoords;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto Blaze - port of QUOI {@code BlazeSolver.kt}'s {@code auto} (TickEvent.End) logic on top of this mod's
 * {@link BlazeSolverFeature} kill order. Each tick: wait out the previous shot (arrow travel time dist/2.5*50ms +
 * Miss cooldown, or until the target blaze dies); take the next blaze; build QUOI's hitboxes (target 0.35x0.8
 * half-extents, others 0.75x1.45, centred 1 block under the stand); try 7 aim points on the target with the arrow
 * simulation and accept the first whose simulated arrow reaches the target before any other blaze or a block
 * (Terminator: the +-5 degree side arrows must not hit another blaze either); shoot with the held shortbow once the
 * Shoot cooldown allows. With "Etherwarp Reposition" on, it also cycles QUOI's standing spots when there's no clean
 * shot (and on Higher Blaze below y=75).
 */
final class AutoBlaze {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autopuzzles");
    private static final String LOWER = "Lower Blaze";
    private static final String HIGHER = "Higher Blaze";
    private static final BlockPos[] HIGHER_SPOTS = {
            new BlockPos(10, 94, 19), new BlockPos(22, 88, 17), new BlockPos(10, 118, 23), new BlockPos(20, 85, 11)
    };
    private static final BlockPos[] LOWER_SPOTS = {
            new BlockPos(24, 62, 14), new BlockPos(9, 45, 18), new BlockPos(10, 68, 23),
            new BlockPos(24, 29, 16), new BlockPos(13, 50, 8), new BlockPos(24, 48, 17)
    };

    private record BlazeHitbox(AABB aabb, boolean isTarget) {
    }

    private static final AutoGuard GUARD = new AutoGuard("Auto Blaze", "Blaze Solver");
    private static final AutoReposition REPOSITION = new AutoReposition("Blaze");

    private static long lastShotTime = 0L;
    private static boolean waitingForUpdate = false;
    private static Entity currentTarget = null;
    private static int currentSpot = 0;
    private static boolean wasInRoom = false;
    private static int lastBlazeCount = 0;
    private static boolean noShortbowLogged = false;

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
        if (blazes.isEmpty()) {
            if (lastBlazeCount > 0) {
                LOGGER.info("[AutoPuzzles] Blaze: all blazes dead - done");
                ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Blaze: "), ModChat.good("done"), ModChat.text("."));
                REPOSITION.cancel(client);
                AutoReposition.releaseSneak(client);
            }
            lastBlazeCount = 0;
            return;
        }
        lastBlazeCount = blazes.size();
        if (!GUARD.fresh() || client.screen != null) {
            return;
        }
        if (REPOSITION.isActive()) {
            REPOSITION.tick(client);
            return;
        }
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        boolean reposition = cfg.isEtherwarpReposition() && cr != null;
        boolean higher = HIGHER.equals(roomName);

        if (higher && player.getY() <= 75) {
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
            if (now - lastShotTime > travelTime + cfg.getMissCooldownMs()) {
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
            if (!noShortbowLogged) {
                noShortbowLogged = true;
                LOGGER.info("[AutoPuzzles] Blaze: clean shot available but not holding a shortbow - waiting");
            }
            return;
        }
        noShortbowLogged = false;
        if (now - lastShotTime < cfg.getShootCooldownMs()) {
            return;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 finalTarget = eye.add(AutoPuzzleUtil.look(hitDir[0], hitDir[1]).scale(10.0));
        float[] dir = AutoPuzzleUtil.direction(eye, finalTarget);
        if (!AutoPuzzleUtil.useItemRotated(client, player, dir[0], dir[1])) {
            return; // gate held this tick back - no shot, so lastShotTime / waitingForUpdate must not move
        }
        LOGGER.info("[AutoPuzzles] Blaze: shot at blaze id={} ({} left) yaw={} pitch={} term={}", blaze.getId(),
                blazes.size(), dir[0], dir[1], terminator);
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

    private static void reset(Minecraft client) {
        REPOSITION.cancel(client);
        AutoReposition.releaseSneak(client);
        lastShotTime = 0L;
        waitingForUpdate = false;
        currentTarget = null;
        currentSpot = 0;
        lastBlazeCount = 0;
        noShortbowLogged = false;
    }
}
