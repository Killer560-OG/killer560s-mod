package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Hypixel dungeon "Teleport Maze" solver, ported from QUOI {@code puzzlesolvers/impl/TeleportMazeSolver.kt} (modified
 * OdinFabric {@code TPMazeSolver.kt}). The room has 7 cells of 4 end-portal-frame pads plus a start and end pad. Every
 * server teleport ({@link ClientboundPlayerPositionPacket}) to an x.5/69.5/z.5 position marks the pads touched as
 * visited, and the landing rotation is ray-tested (XZ, 32 blocks) against every unvisited pad's 1x4x1 column inflated
 * 0.75: the pads still intersecting the look ray are the candidates for the real exit. Candidates render green (one) /
 * gold (several), visited red, others white, plus an optional tracer to the best next pad in the current cell.
 * Never moves you - see AutoPuzzles for the auto. Position packets arrive via {@code PuzzlePacketMixin}.
 */
public final class TeleportMazeSolverFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-puzzles");
    private static final String ROOM = "Teleport Maze";

    private static final int[][] PADS = {
            {4, 69, 14}, {10, 69, 14}, {10, 69, 20}, {4, 69, 20},
            {4, 69, 12}, {4, 69, 6}, {10, 69, 6}, {10, 69, 12},
            {12, 69, 28}, {12, 69, 22}, {18, 69, 22}, {18, 69, 28},
            {26, 69, 14}, {20, 69, 20}, {20, 69, 14}, {26, 69, 20},
            {26, 69, 28}, {26, 69, 22}, {20, 69, 28}, {20, 69, 22},
            {10, 69, 22}, {10, 69, 28}, {4, 69, 28}, {4, 69, 22},
            {20, 69, 6}, {20, 69, 12}, {26, 69, 12}, {26, 69, 6},
            {15, 69, 14}, // end
            {15, 69, 12}, // start
    };
    private static final int CELL_COUNT = 7; // first 28 pads, 4 per cell, in the same order as QUOI's cells list

    private static RoomEntry lastRoomEntry = null;
    private static Set<BlockPos> tpPads = new LinkedHashSet<>();
    private static List<Set<BlockPos>> realCells = new ArrayList<>();
    private static Set<BlockPos> correctPortals = new LinkedHashSet<>();
    private static final Set<BlockPos> visited = new LinkedHashSet<>();
    private static BlockPos best = null;
    private static int teleportSeq = 0;

    private TeleportMazeSolverFeature() {
    }

    public static void register() {
        // Shared solver highlight pipelines must exist before the level renderer precompiles them.
        SolverEspRender.init();
        ClientTickEvents.END_CLIENT_TICK.register(TeleportMazeSolverFeature::tick);
        // After translucent TERRAIN, not features: water is drawn after the features pass, so a highlight
        // drawn there ended up painted over by any water behind/around it (killer560, 2026-09-21).
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(TeleportMazeSolverFeature::onWorldRender);
    }

    // ---- read-only accessors for AutoPuzzles ----
    public static Set<BlockPos> getTpPads() {
        return Collections.unmodifiableSet(tpPads);
    }

    public static List<Set<BlockPos>> getRealCells() {
        return Collections.unmodifiableList(realCells);
    }

    public static Set<BlockPos> getVisited() {
        return Collections.unmodifiableSet(visited);
    }

    public static Set<BlockPos> getCorrectPortals() {
        return Collections.unmodifiableSet(correctPortals);
    }

    public static BlockPos getBest() {
        return best;
    }

    /** Incremented on every maze teleport handled - lets the auto react once per teleport. */
    public static int getTeleportSeq() {
        return teleportSeq;
    }

    private static void tick(Minecraft client) {
        if (!TeleportMazeSolverConfig.getInstance().isEnabled() || !DungeonState.isInDungeon() || LiveMapFeature.isInBoss()) {
            if (lastRoomEntry != null || !tpPads.isEmpty()) {
                reset();
                tpPads = new LinkedHashSet<>();
                realCells = new ArrayList<>();
            }
            lastRoomEntry = null;
            return;
        }
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        if (current == lastRoomEntry && (current == null || !ROOM.equals(current.name) || !tpPads.isEmpty())) {
            return;
        }
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        if (current != null && ROOM.equals(current.name) && cr == null) {
            return; // rotation not known yet - retry next tick
        }
        lastRoomEntry = current;
        reset();
        tpPads = new LinkedHashSet<>();
        realCells = new ArrayList<>();
        if (current == null || !ROOM.equals(current.name)) {
            return;
        }
        for (int[] p : PADS) {
            tpPads.add(PuzzleCoords.real(p[0], p[1], p[2], cr));
        }
        for (int c = 0; c < CELL_COUNT; c++) {
            Set<BlockPos> cell = new LinkedHashSet<>();
            for (int i = 0; i < 4; i++) {
                int[] p = PADS[c * 4 + i];
                cell.add(PuzzleCoords.real(p[0], p[1], p[2], cr));
            }
            realCells.add(cell);
        }
        // killer560, 2026-09-20: "white hitboxes appear far away, off-centre from the actual maze area".
        // The 30 relative PADS coordinates above are byte-for-byte identical to QUOI's own
        // endPortalFrameLocations/cells (both are the same fixed OdinFabric-derived layout), so this logs
        // the real-world bounding box of every transformed pad next to the player's own position - if the
        // reported bug is real, this range will sit well away from where the player actually is standing,
        // which would point at RoomDatabase's clay/rotation transform (see staging notes; not owned by
        // this file) rather than the pad data itself.
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pad : tpPads) {
            minX = Math.min(minX, pad.getX());
            maxX = Math.max(maxX, pad.getX());
            minZ = Math.min(minZ, pad.getZ());
            maxZ = Math.max(maxZ, pad.getZ());
        }
        Vec3 playerPos = client.player != null ? client.player.position() : null;
        LOGGER.info("[TeleportMazeSolver] Entered maze (clayRot={},{},{}) - {} pads, real bounds x=[{},{}] z=[{},{}], player={}",
                cr[0], cr[1], cr[2], tpPads.size(), minX, maxX, minZ, maxZ, playerPos);
    }

    /** From {@code PuzzlePacketMixin}, main thread, before vanilla applies the teleport. */
    public static void onPlayerPosition(ClientboundPlayerPositionPacket packet) {
        Minecraft client = Minecraft.getInstance();
        if (!TeleportMazeSolverConfig.getInstance().isEnabled() || tpPads.isEmpty() || client.player == null) {
            return;
        }
        Vec3 pos = packet.change().position();
        if (pos.x % 0.5 != 0.0 || pos.y != 69.5 || pos.z % 0.5 != 0.0) {
            return;
        }
        float yaw = packet.change().yRot();
        AABB landing = new AABB(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1).inflate(1.0, 0.0, 1.0);
        AABB playerBox = client.player.getBoundingBox().inflate(1.0, 0.0, 1.0);
        for (BlockPos pad : tpPads) {
            AABB padBox = new AABB(pad);
            if (landing.intersects(padBox) || playerBox.intersects(padBox)) {
                visited.add(pad);
            }
        }
        getCorrectPortals(client, pos, yaw, packet.change().xRot());
        best = getBestPad(pos, yaw);
        teleportSeq++;
        LOGGER.info("[TeleportMazeSolver] Teleport to {} yaw={} - visited={} candidates={} best={}",
                pos, yaw, visited.size(), correctPortals.size(), best);
    }

    private static void getCorrectPortals(Minecraft client, Vec3 pos, float yaw, float pitch) {
        if (correctPortals.isEmpty()) {
            correctPortals = new LinkedHashSet<>(tpPads);
        }
        AABB playerBox = client.player.getBoundingBox();
        Set<BlockPos> next = new LinkedHashSet<>();
        for (BlockPos it : correctPortals) {
            if (visited.contains(it)) {
                continue;
            }
            AABB column = new AABB(it.getX(), it.getY(), it.getZ(), it.getX() + 1.0, it.getY() + 4.0, it.getZ() + 1.0)
                    .inflate(0.75, 0.0, 0.75);
            if (isXZInterceptable(column, 32.0, pos, yaw, pitch) && !new AABB(it).inflate(0.5, 0.0, 0.5).intersects(playerBox)) {
                next.add(it);
            }
        }
        correctPortals = next;
    }

    private static BlockPos getBestPad(Vec3 pos, float yaw) {
        BlockPos currentPad = nearestPad(pos);
        Set<BlockPos> currentCell = cellOf(currentPad);
        if (currentCell == null || currentCell.size() == 1) {
            return null;
        }
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos it : currentCell) {
            if (!it.equals(currentPad) && !visited.contains(it)) {
                candidates.add(it);
            }
        }
        for (BlockPos it : candidates) {
            if (correctPortals.contains(it)) {
                return it;
            }
        }
        BlockPos bestPad = null;
        double bestDiff = Double.MAX_VALUE;
        for (BlockPos it : candidates) {
            float targetYaw = (float) (Math.atan2(it.getZ() + 0.5 - pos.z, it.getX() + 0.5 - pos.x) * 180.0 / Math.PI) - 90f;
            double diff = Math.abs(Mth.wrapDegrees(targetYaw) - Mth.wrapDegrees(yaw));
            if (diff < bestDiff) {
                bestDiff = diff;
                bestPad = it;
            }
        }
        return bestPad;
    }

    public static BlockPos nearestPad(Vec3 pos) {
        BlockPos nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pad : tpPads) {
            double d = pos.distanceToSqr(Vec3.atCenterOf(pad));
            if (d < bestDist) {
                bestDist = d;
                nearest = pad;
            }
        }
        return nearest;
    }

    public static Set<BlockPos> cellOf(BlockPos pad) {
        if (pad == null) {
            return null;
        }
        for (Set<BlockPos> cell : realCells) {
            if (cell.contains(pad)) {
                return cell;
            }
        }
        return null;
    }

    /** QUOI {@code isXZInterceptable}: does the look ray (packet yaw/pitch, {@code range} blocks) cross one of the
     *  box's x or z faces; only the x/z of each intercept is tested, exactly like QUOI. */
    private static boolean isXZInterceptable(AABB box, double range, Vec3 pos, float yaw, float pitch) {
        Vec3 look = getLook(yaw, pitch);
        Vec3 start = pos;
        Vec3 goal = start.add(look.scale(range));
        return isVecInZ(intermediateX(start, goal, box.minX), box)
                || isVecInZ(intermediateX(start, goal, box.maxX), box)
                || isVecInX(intermediateZ(start, goal, box.minZ), box)
                || isVecInX(intermediateZ(start, goal, box.maxZ), box);
    }

    private static Vec3 getLook(float yaw, float pitch) {
        double f2 = -Math.cos(-pitch * 0.017453292f);
        return new Vec3(Math.sin(-yaw * 0.017453292f - 3.1415927f) * f2, Math.sin(-pitch * 0.017453292f),
                Math.cos(-yaw * 0.017453292f - 3.1415927f) * f2);
    }

    private static boolean isVecInX(Vec3 vec, AABB box) {
        return vec != null && vec.x >= box.minX && vec.x <= box.maxX;
    }

    private static boolean isVecInZ(Vec3 vec, AABB box) {
        return vec != null && vec.z >= box.minZ && vec.z <= box.maxZ;
    }

    private static Vec3 intermediateX(Vec3 from, Vec3 goal, double x) {
        double dx = goal.x - from.x;
        if (dx * dx < 1e-8) {
            return null;
        }
        double t = (x - from.x) / dx;
        return t >= 0.0 && t <= 1.0 ? new Vec3(from.x + dx * t, from.y + (goal.y - from.y) * t, from.z + (goal.z - from.z) * t) : null;
    }

    private static Vec3 intermediateZ(Vec3 from, Vec3 goal, double z) {
        double dz = goal.z - from.z;
        if (dz * dz < 1e-8) {
            return null;
        }
        double t = (z - from.z) / dz;
        return t >= 0.0 && t <= 1.0 ? new Vec3(from.x + (goal.x - from.x) * t, from.y + (goal.y - from.y) * t, from.z + dz * t) : null;
    }

    private static void onWorldRender(LevelRenderContext context) {
        TeleportMazeSolverConfig cfg = TeleportMazeSolverConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (!cfg.isEnabled() || tpPads.isEmpty() || client.level == null || client.player == null) {
            return;
        }
        for (BlockPos pad : tpPads) {
            VoxelShape shape = client.level.getBlockState(pad).getShape(client.level, pad);
            AABB box = shape.isEmpty() ? new AABB(pad) : shape.bounds().move(pad);
            if (correctPortals.contains(pad)) {
                if (correctPortals.size() == 1) {
                    SolverEspRender.renderWaypoint(context, box, 0.33f, 1.0f, 0.33f, 2f);
                } else {
                    SolverEspRender.renderWaypoint(context, box, 1.0f, 0.67f, 0.0f, 2f);
                }
            } else if (visited.contains(pad)) {
                SolverEspRender.renderWaypoint(context, box, 1.0f, 0.33f, 0.33f, 2f);
            } else {
                SolverEspRender.renderWaypoint(context, box, 1.0f, 1.0f, 1.0f, 2f);
            }
        }
        BlockPos target = best;
        if (cfg.isShowTracer() && target != null) {
            // Start a little IN FRONT of the camera, not at the eye (killer560, 2026-09-21: "the line that is supposed to
            // take me to the tp pads ... doesn't really show up"): a line that begins exactly at the camera runs straight
            // away from the viewer and collapses to a dot, so it only flickered into view at odd angles. Starting it
            // half a block along the view direction makes it a proper line from the crosshair to the pad.
            var camera = client.gameRenderer.getMainCamera();
            Vec3 start = camera.position().add(Vec3.directionFromRotation(camera.xRot(), camera.yRot()).scale(0.5));
            SolverEspRender.renderLineStrip(context, List.of(start,
                    new Vec3(target.getX() + 0.5, target.getY() + 0.8, target.getZ() + 0.5)), 0.33f, 1.0f, 1.0f, 1f, 3f);
        }
    }

    private static void reset() {
        correctPortals = new LinkedHashSet<>();
        visited.clear();
        best = null;
    }
}
