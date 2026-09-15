package com.killer560.hub.autopuzzles;

import com.killer560.hub.puzzlesolvers.TeleportMazeSolverConfig;
import com.killer560.hub.puzzlesolvers.TeleportMazeSolverFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Auto Teleport Maze - port of QUOI {@code TeleportMazeSolver.kt}'s {@code auto}, which IS movement automation: once
 * the first maze teleport has happened (you step on the start pad yourself), one tick after every maze teleport it
 * picks the pad to use in the current cell (QUOI {@code getPad}: the solver's best pad if unvisited, else the single
 * remaining candidate if it's in this cell, else an unvisited pad diagonal to the current one, else the farthest
 * unvisited), turns the camera to face that pad's centre and holds the forward key until the next teleport. Nothing
 * beyond QUOI: no pathing, jumping or strafing. Stops when a screen opens, when there is no pad to go to (the end),
 * when you leave the room, and (safety addition) after 3 seconds of walking without a teleport.
 */
final class AutoTeleportMaze {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autopuzzles");
    private static final String ROOM = "Teleport Maze";
    private static final long WALK_TIMEOUT_MS = 3000L;

    private static final AutoGuard GUARD = new AutoGuard("Auto Teleport Maze", "Teleport Maze Solver");

    private static int lastSeq = -1;
    private static int pendingTicks = -1;
    private static boolean walking = false;
    private static long walkStartMs = 0L;
    private static boolean wasInRoom = false;

    private AutoTeleportMaze() {
    }

    static void levelChanged(Minecraft client) {
        GUARD.levelChanged();
        reset(client);
    }

    static void tick(Minecraft client, String roomName) {
        Set<BlockPos> visited = TeleportMazeSolverFeature.getVisited();
        GUARD.observe(visited.isEmpty());
        AutoPuzzlesConfig cfg = AutoPuzzlesConfig.getInstance();
        if (!cfg.isAutoTeleportMazeEnabled() || !ROOM.equals(roomName)) {
            if (wasInRoom) {
                reset(client);
                GUARD.leftRoom();
            }
            wasInRoom = false;
            return;
        }
        if (!wasInRoom) {
            lastSeq = TeleportMazeSolverFeature.getTeleportSeq(); // only react to teleports made while in the room
        }
        wasInRoom = true;
        if (!GUARD.solverOn(TeleportMazeSolverConfig.getInstance().isEnabled()) || !GUARD.fresh() || visited.isEmpty()) {
            stop(client);
            return;
        }
        if (client.screen != null) {
            stop(client);
            pendingTicks = -1;
            return;
        }
        int seq = TeleportMazeSolverFeature.getTeleportSeq();
        if (seq != lastSeq) {
            lastSeq = seq;
            stop(client);
            pendingTicks = 1; // QUOI: Scheduler.scheduleTask { nextMove = true }
            return;
        }
        LocalPlayer player = client.player;
        if (pendingTicks > 0 && --pendingTicks == 0) {
            pendingTicks = -1;
            BlockPos target = getPad(player.position());
            if (target != null) {
                float[] dir = AutoPuzzleUtil.direction(player.getEyePosition(), Vec3.atCenterOf(target));
                AutoPuzzleUtil.rotateCamera(player, dir[0], dir[1]);
                client.options.keyUp.setDown(true);
                walking = true;
                walkStartMs = System.currentTimeMillis();
                LOGGER.info("[AutoPuzzles] TeleportMaze: walking to pad {}", target);
            } else {
                stop(client);
                LOGGER.info("[AutoPuzzles] TeleportMaze: no pad to walk to - stopped");
            }
        } else if (walking) {
            if (System.currentTimeMillis() - walkStartMs > WALK_TIMEOUT_MS) {
                LOGGER.info("[AutoPuzzles] TeleportMaze: no teleport after {}ms of walking - stopped", WALK_TIMEOUT_MS);
                stop(client);
            } else {
                client.options.keyUp.setDown(true);
            }
        }
    }

    private static BlockPos getPad(Vec3 pos) {
        BlockPos currentPad = TeleportMazeSolverFeature.nearestPad(pos);
        Set<BlockPos> currentCell = TeleportMazeSolverFeature.cellOf(currentPad);
        if (currentCell == null) {
            return null;
        }
        Set<BlockPos> visited = TeleportMazeSolverFeature.getVisited();
        BlockPos best = TeleportMazeSolverFeature.getBest();
        if (best != null && currentCell.contains(best) && !visited.contains(best)) {
            return best;
        }
        Set<BlockPos> correct = TeleportMazeSolverFeature.getCorrectPortals();
        if (correct.size() == 1) {
            BlockPos only = correct.iterator().next();
            if (currentCell.contains(only)) {
                return only;
            }
        }
        List<BlockPos> unvisited = new ArrayList<>();
        for (BlockPos p : currentCell) {
            if (!visited.contains(p)) {
                unvisited.add(p);
            }
        }
        for (BlockPos p : unvisited) {
            if (p.getX() != currentPad.getX() && p.getZ() != currentPad.getZ()) {
                return p;
            }
        }
        BlockPos farthest = null;
        double bestDist = -1;
        for (BlockPos p : unvisited) {
            double d = pos.distanceToSqr(Vec3.atCenterOf(p));
            if (d > bestDist) {
                bestDist = d;
                farthest = p;
            }
        }
        return farthest;
    }

    private static void stop(Minecraft client) {
        if (walking) {
            client.options.keyUp.setDown(false);
            walking = false;
        }
    }

    private static void reset(Minecraft client) {
        stop(client);
        pendingTicks = -1;
        lastSeq = -1;
    }
}
