package com.killer560.hub.autopuzzles;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.puzzlesolvers.IceFillSolverConfig;
import com.killer560.hub.puzzlesolvers.IceFillSolverFeature;
import com.killer560.hub.puzzlesolvers.PuzzleCoords;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto Ice Fill - port of QUOI {@code IceFillSolver.kt}'s {@code auto}. QUOI automates it with Aspect of the
 * Void/End teleports: the solver path (QUOI uses the "easy" patterns, so this only runs with the solver's Optimized
 * Path OFF) gets QUOI's {@code stupidStairs} midpoints and {@code fillGaps} 1-block interpolation; while holding an
 * AOTV/AOTE and standing exactly on a path point, after "Ice Fill Delay" ticks it right-clicks aimed from that point's
 * eye position at the next point, stepping one block at a time. Done when relative (15,71,26) becomes packed ice.
 * With "Etherwarp Reposition" on and the player outside y 69.5..72.5 it warps onto the first still-unfilled ice tile
 * like QUOI. Safety addition: requires an AOTV/AOTE in hand (QUOI also passed on non-Skyblock items) and positions are
 * matched with a 1e-4 tolerance instead of exact double equality.
 */
final class AutoIceFill {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autopuzzles");
    private static final String ROOM = "Ice Fill";
    private static final double EPS = 1e-4;

    private static final AutoGuard GUARD = new AutoGuard("Auto Ice Fill", "Ice Fill Solver");
    private static final AutoReposition REPOSITION = new AutoReposition("IceFill");

    private static List<Vec3> sourcePath = List.of();
    private static List<Vec3> path = List.of();
    private static int lastIndex = -1;
    private static int ticks = 0;
    private static boolean done = false;
    private static boolean optimizedWarned = false;
    private static boolean wasInRoom = false;

    private AutoIceFill() {
    }

    static void levelChanged(Minecraft client) {
        GUARD.levelChanged();
        reset(client);
    }

    static void tick(Minecraft client, String roomName) {
        List<Vec3> raw = IceFillSolverFeature.getCurrentPath();
        GUARD.observe(raw.isEmpty());
        AutoPuzzlesConfig cfg = AutoPuzzlesConfig.getInstance();
        if (!cfg.isAutoIceFillEnabled() || !ROOM.equals(roomName)) {
            if (wasInRoom) {
                reset(client);
                GUARD.leftRoom();
            }
            wasInRoom = false;
            return;
        }
        wasInRoom = true;
        if (!GUARD.solverOn(IceFillSolverConfig.getInstance().isEnabled()) || raw.isEmpty() || !GUARD.fresh()
                || client.screen != null || done) {
            return;
        }
        if (IceFillSolverConfig.getInstance().isOptimizedPath()) {
            if (!optimizedWarned) {
                optimizedWarned = true;
                LOGGER.info("[AutoPuzzles] IceFill: solver Optimized Path is ON - QUOI's auto only uses the easy path");
                ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Auto Ice Fill needs the solver's "),
                        ModChat.value("Optimized Path"), ModChat.text(" turned off."));
            }
            return;
        }
        if (!raw.equals(sourcePath)) {
            sourcePath = raw;
            path = fillGaps(stupidStairs(raw));
            lastIndex = -1;
            ticks = 0;
        }
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        if (cr == null) {
            return;
        }
        if (client.level.getBlockState(PuzzleCoords.real(15, 71, 26, cr)).is(Blocks.PACKED_ICE)) {
            done = true;
            REPOSITION.cancel(client);
            AutoReposition.releaseSneak(client);
            LOGGER.info("[AutoPuzzles] IceFill: puzzle complete");
            return;
        }
        if (REPOSITION.isActive()) {
            REPOSITION.tick(client);
            return;
        }
        LocalPlayer player = client.player;
        if (cfg.isEtherwarpReposition() && (player.getY() < 69.5 || player.getY() > 72.5)) {
            if (AutoPuzzleUtil.isMoving(player)) {
                return;
            }
            for (Vec3 vec : path) {
                BlockPos below = BlockPos.containing(vec).below();
                if (client.level.getBlockState(below).is(Blocks.ICE)) {
                    REPOSITION.start(client, below, false, true, true);
                    return;
                }
            }
        }
        if (!AutoPuzzleUtil.isAotv(player.getMainHandItem())) {
            return;
        }
        int index = -1;
        for (int i = 0; i < path.size(); i++) {
            Vec3 p = path.get(i);
            if (Math.abs(player.getX() - p.x) < EPS && Math.abs(player.getY() + 0.1 - p.y) < EPS && Math.abs(player.getZ() - p.z) < EPS) {
                index = i;
                break;
            }
        }
        if (index == -1 || index >= path.size() - 1) {
            lastIndex = -1;
            return;
        }
        if (lastIndex == -1 || index > lastIndex) {
            lastIndex = index;
            ticks = 0;
        }
        if (lastIndex >= path.size() - 1) {
            return;
        }
        // ticks is only advanced on a tick we did NOT warp on: >= (not ==) so that a tick the gate holds back simply
        // leaves the warp due, and the very next allowed tick takes it. The unblocked cadence is unchanged.
        if (cfg.isIceFillAdaptive()) {
            // Adaptive: no fixed delay - the next hop goes the moment the SERVER has taken the tile you stand on
            // (plain ice turns to packed ice when it registers you on it). A slow server just means a longer wait
            // here, never a hop off a tile it hasn't counted, so lag can't make it fail. Start / stair points are
            // not ice at all and go straight away.
            Vec3 here = path.get(lastIndex);
            if (client.level.getBlockState(BlockPos.containing(here).below()).is(Blocks.ICE)) {
                return;
            }
            ticks = Integer.MAX_VALUE - 1;
        }
        if (ticks + 1 >= cfg.getIceFillDelayTicks()) {
            Vec3 current = path.get(lastIndex);
            Vec3 next = path.get(lastIndex + 1);
            Vec3 from = new Vec3(current.x, current.y - 0.1 + player.getEyeHeight(), current.z);
            float[] dir = AutoPuzzleUtil.direction(from, next);
            if (!AutoPuzzleUtil.useItemRotated(client, player, dir[0], dir[1])) {
                return; // gate held this tick back - nothing warped, so lastIndex / ticks must not move
            }
            // This warp is our own, so waive the gate's teleport stand-down for the next hop - otherwise the
            // 6-tick teleport window would override the 2-tick Delay setting on every single step of the path.
            com.killer560.hub.util.ActionGate.expectSelfTeleport(com.killer560.hub.util.ActionGate.Actor.PUZZLE_WORLD);
            LOGGER.info("[AutoPuzzles] IceFill: teleport {} -> {} ({}/{})", current, next, lastIndex + 1, path.size() - 1);
            lastIndex++;
            ticks = 0;
        } else {
            ticks++;
        }
    }

    /** QUOI fillGaps: insert 1-block interpolated points between consecutive path points. */
    private static List<Vec3> fillGaps(List<Vec3> points) {
        if (points.isEmpty()) {
            return points;
        }
        List<Vec3> updated = new ArrayList<>(points.size() * 2);
        for (int i = 0; i < points.size() - 1; i++) {
            Vec3 p1 = points.get(i);
            Vec3 p2 = points.get(i + 1);
            updated.add(p1);
            double dx = p2.x - p1.x;
            double dy = p2.y - p1.y;
            double dz = p2.z - p1.z;
            int steps = (int) Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
            for (int s = 1; s < steps; s++) {
                double r = (double) s / steps;
                updated.add(new Vec3(p1.x + dx * r, p1.y + dy * r, p1.z + dz * r));
            }
        }
        updated.add(points.get(points.size() - 1));
        return updated;
    }

    /** QUOI stupidStairs: add a midpoint before the first point at y 71.1 and before the first at y 72.1. */
    private static List<Vec3> stupidStairs(List<Vec3> points) {
        if (points.isEmpty()) {
            return points;
        }
        List<Vec3> updated = new ArrayList<>(points.size() + 2);
        Vec3 lastPoint = points.get(0);
        boolean added71 = false;
        boolean added72 = false;
        for (Vec3 point : points) {
            if (!added71 && Math.abs(point.y - 71.1) < EPS) {
                updated.add(new Vec3((lastPoint.x + point.x) / 2, 71.1, (lastPoint.z + point.z) / 2));
                added71 = true;
            } else if (!added72 && Math.abs(point.y - 72.1) < EPS) {
                updated.add(new Vec3((lastPoint.x + point.x) / 2, 72.1, (lastPoint.z + point.z) / 2));
                added72 = true;
            }
            updated.add(point);
            lastPoint = point;
        }
        return updated;
    }

    private static void reset(Minecraft client) {
        REPOSITION.cancel(client);
        AutoReposition.releaseSneak(client);
        sourcePath = List.of();
        path = List.of();
        lastIndex = -1;
        ticks = 0;
        done = false;
        optimizedWarned = false;
    }
}
