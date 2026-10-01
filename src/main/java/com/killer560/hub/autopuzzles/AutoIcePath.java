package com.killer560.hub.autopuzzles;

import com.killer560.hub.puzzlesolvers.IcePathSolverConfig;
import com.killer560.hub.puzzlesolvers.IcePathSolverFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import com.killer560.hub.compat.McCompat;

/**
 * Auto Ice Path - port of QUOI {@code IcePathSolver.kt}'s {@code auto(...)} on top of {@link IcePathSolverFeature}.
 * Standing on the silverfish's cell (x/z of the silverfish, y 66), it shoots straight down (pitch 90) with the yaw of
 * the etherwarp direction towards the next stop, so the arrow knocks the silverfish that way, paced by the Shoot
 * cooldown. While the silverfish slides it waits. With "Etherwarp Reposition" on it also warps onto the silverfish's
 * cell (and, while it slides, onto the next stop) like QUOI; with it off you stand there yourself.
 * Safety addition: only fires while holding a shortbow (QUOI relied on its reposition having swapped to one).
 * <p>
 * Miss cooldown removed (killer560, 2026-09-27: "For auto puzzles remove the miss cooldown."): it used to hold
 * every shot for an extra {@code missCooldownMs} on top of the Shoot cooldown in case that shot missed and the
 * silverfish never started sliding. Now the Shoot cooldown alone paces the next attempt, hit or miss.
 */
final class AutoIcePath {

    private static final String ROOM = "Ice Path";

    private static final AutoGuard GUARD = new AutoGuard("Auto Ice Path", "Ice Path Solver");
    private static final AutoReposition REPOSITION = new AutoReposition("IcePath");

    private static long lastShotTime = 0L;
    private static boolean wasInRoom = false;

    private AutoIcePath() {
    }

    static void levelChanged(Minecraft client) {
        GUARD.levelChanged();
        reset(client);
    }

    static void tick(Minecraft client, String roomName) {
        List<Vec3> path = IcePathSolverFeature.getPath();
        GUARD.observe(path.isEmpty());
        AutoPuzzlesConfig cfg = AutoPuzzlesConfig.getInstance();
        if (!cfg.isAutoIcePathEnabled() || !ROOM.equals(roomName)) {
            if (wasInRoom) {
                reset(client);
                GUARD.leftRoom();
            }
            wasInRoom = false;
            return;
        }
        wasInRoom = true;
        if (!GUARD.solverOn(IcePathSolverConfig.getInstance().isEnabled())) {
            return;
        }
        Silverfish fish = IcePathSolverFeature.getSilverfish();
        LocalPlayer player = client.player;
        if (!GUARD.fresh() || McCompat.screen(client) != null || path.size() < 2 || fish == null) {
            return;
        }
        if (REPOSITION.isActive()) {
            REPOSITION.tick(client);
            return;
        }
        boolean reposition = cfg.isEtherwarpReposition();
        long now = System.currentTimeMillis();
        BlockPos nextSpot = BlockPos.containing(path.get(1));

        if (IcePathSolverFeature.isSilverfishMoving()) {
            if (reposition && !AutoPuzzleUtil.at(player, nextSpot)) {
                REPOSITION.start(client, nextSpot, true, false, false);
            }
            return;
        }
        if (now - lastShotTime < cfg.getShootCooldownMs()) {
            return;
        }
        BlockPos fishPos = fish.blockPosition();
        // THE SOLVER'S OWN HEIGHT, not a literal 66 plus a correction.
        //
        // Every point in `path` is the solver's `PuzzleCoords.real(col, 66, row)`, so its y already IS the ice's
        // world height - whole-floor shift, per-room nudge and all. Re-deriving it here from the literal 66 meant
        // two expressions that had to stay equal, and they had already drifted apart once (the sim's floor shift
        // was missing) and would have drifted again the moment a room needed a nudge. `path.size() < 2` has
        // already been checked above, so element 0 exists.
        BlockPos currSpot = new BlockPos(fishPos.getX(), (int) Math.floor(path.get(0).y), fishPos.getZ());
        if (AutoPuzzleUtil.etherwarpDirection(client.level, player, currSpot) == null) {
            return;
        }
        if (!AutoPuzzleUtil.at(player, currSpot)) {
            if (reposition) {
                REPOSITION.start(client, currSpot, true, false, false);
            }
            return;
        }
        float[] dir = AutoPuzzleUtil.etherwarpDirection(client.level, player, nextSpot);
        if (dir == null || !AutoPuzzleUtil.isShortbow(player.getMainHandItem())) {
            return;
        }
        if (!AutoPuzzleUtil.useItemRotated(client, player, dir[0], 90f)) {
            return; // gate held this tick back - no shot, so lastShotTime must not move
        }
        lastShotTime = now;
    }

    private static void reset(Minecraft client) {
        REPOSITION.cancel(client);
        AutoReposition.releaseSneak(client);
        lastShotTime = 0L;
    }
}
