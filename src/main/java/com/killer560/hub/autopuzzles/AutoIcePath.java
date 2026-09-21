package com.killer560.hub.autopuzzles;

import com.killer560.hub.puzzlesolvers.IcePathSolverConfig;
import com.killer560.hub.puzzlesolvers.IcePathSolverFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Auto Ice Path - port of QUOI {@code IcePathSolver.kt}'s {@code auto(...)} on top of {@link IcePathSolverFeature}.
 * Standing on the silverfish's cell (x/z of the silverfish, y 66), it shoots straight down (pitch 90) with the yaw of
 * the etherwarp direction towards the next stop, so the arrow knocks the silverfish that way; Shoot/Miss cooldowns
 * as QUOI. While the silverfish slides it waits. With "Etherwarp Reposition" on it also warps onto the silverfish's
 * cell (and, while it slides, onto the next stop) like QUOI; with it off you stand there yourself.
 * Safety addition: only fires while holding a shortbow (QUOI relied on its reposition having swapped to one).
 */
final class AutoIcePath {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autopuzzles");
    private static final String ROOM = "Ice Path";

    private static final AutoGuard GUARD = new AutoGuard("Auto Ice Path", "Ice Path Solver");
    private static final AutoReposition REPOSITION = new AutoReposition("IcePath");

    private static long lastShotTime = 0L;
    private static boolean waitingForUpdate = false;
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
        if (!GUARD.fresh() || client.screen != null || path.size() < 2 || fish == null) {
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
            waitingForUpdate = false;
            if (reposition && !AutoPuzzleUtil.at(player, nextSpot)) {
                REPOSITION.start(client, nextSpot, true, false, false);
            }
            return;
        }
        if (waitingForUpdate) {
            if (now - lastShotTime > cfg.getMissCooldownMs()) {
                waitingForUpdate = false;
            } else {
                return;
            }
        }
        if (now - lastShotTime < cfg.getShootCooldownMs()) {
            return;
        }
        BlockPos fishPos = fish.blockPosition();
        BlockPos currSpot = new BlockPos(fishPos.getX(), 66, fishPos.getZ());
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
            return; // gate held this tick back - no shot, so lastShotTime / waitingForUpdate must not move
        }
        LOGGER.info("[AutoPuzzles] IcePath: shot silverfish at {} towards {} (yaw={})", currSpot, nextSpot, dir[0]);
        lastShotTime = now;
        waitingForUpdate = true;
    }

    private static void reset(Minecraft client) {
        REPOSITION.cancel(client);
        AutoReposition.releaseSneak(client);
        lastShotTime = 0L;
        waitingForUpdate = false;
    }
}
