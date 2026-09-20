package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Hypixel dungeon "Ice Path" (silverfish) solver, ported from QUOI {@code puzzlesolvers/impl/IcePathSolver.kt}
 * (modified Skyblocker {@code IcePath.java}). The 17x17 board is read every tick at room-relative y=67
 * ({@code (23 - col, 67, 24 - row)}, non-air = wall). Whenever the silverfish (searched in a 16-block box around
 * relative (15,66,16)) comes to rest on a new cell, or the board changes, a BFS over "slide until blocked" moves finds
 * the shortest push sequence to the exit (row 0, col 7..9). The path is drawn as a green line and the next stop is
 * outlined red. Never hits the silverfish - see AutoPuzzles for the auto.
 */
public final class IcePathSolverFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-puzzles");
    private static final String ROOM = "Ice Path";
    private static final int BOARD_SIZE = 17;
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private static final boolean[][] board = new boolean[BOARD_SIZE][BOARD_SIZE];
    private static int[] silverfishCell = null;
    private static List<Vec3> path = new ArrayList<>();
    private static Silverfish silverfish = null;
    private static Vec3 lastPos = Vec3.ZERO;
    private static int stillTicks = 0;
    private static boolean moving = false;
    private static RoomEntry lastRoomEntry = null;

    private IcePathSolverFeature() {
    }

    public static void register() {
        // Shared solver highlight pipelines must exist before the level renderer precompiles them.
        SolverEspRender.init();
        ClientTickEvents.END_CLIENT_TICK.register(IcePathSolverFeature::tick);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(IcePathSolverFeature::onWorldRender);
    }

    /** Real block positions (corner vectors, y=66) of the solution, starting at the silverfish's cell. */
    public static List<Vec3> getPath() {
        return Collections.unmodifiableList(path);
    }

    public static Silverfish getSilverfish() {
        return silverfish;
    }

    public static boolean isSilverfishMoving() {
        return moving;
    }

    private static void tick(Minecraft client) {
        if (!IcePathSolverConfig.getInstance().isEnabled() || !DungeonState.isInDungeon() || LiveMapFeature.isInBoss()
                || client.level == null) {
            if (lastRoomEntry != null) {
                reset();
            }
            lastRoomEntry = null;
            return;
        }
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        if (current != lastRoomEntry) {
            lastRoomEntry = current;
            reset();
        }
        if (current == null || !ROOM.equals(current.name)) {
            return;
        }
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        if (cr == null) {
            return;
        }
        Level level = client.level;
        boolean boardChanged = updateBoard(level, cr);

        AABB searchBox = AABB.ofSize(Vec3.atCenterOf(PuzzleCoords.real(15, 66, 16, cr)), 16.0, 16.0, 16.0);
        List<Silverfish> found = level.getEntitiesOfClass(Silverfish.class, searchBox, e -> !e.isRemoved());
        if (found.isEmpty()) {
            silverfish = null;
            return;
        }
        Silverfish fish = found.get(0);
        silverfish = fish;
        Vec3 pos = fish.position();
        if (pos.distanceToSqr(lastPos) > 0.0001) {
            stillTicks = 0;
            moving = true;
        } else {
            stillTicks++;
            if (stillTicks > 1) {
                moving = false;
            }
        }
        lastPos = pos;

        if (!moving) {
            BlockPos rel = PuzzleCoords.relative(fish.blockPosition(), cr);
            int[] cell = {24 - rel.getZ(), 23 - rel.getX()};
            if (cell[0] < 0 || cell[0] >= BOARD_SIZE || cell[1] < 0 || cell[1] >= BOARD_SIZE) {
                return;
            }
            if (silverfishCell == null || silverfishCell[0] != cell[0] || silverfishCell[1] != cell[1] || boardChanged) {
                silverfishCell = cell;
                solve(cr);
            }
        }
    }

    private static boolean updateBoard(Level level, int[] cr) {
        boolean changed = false;
        for (int row = 0; row < BOARD_SIZE; row++) {
            for (int col = 0; col < BOARD_SIZE; col++) {
                boolean isBlock = !level.getBlockState(PuzzleCoords.real(23 - col, 67, 24 - row, cr)).isAir();
                if (board[row][col] != isBlock) {
                    board[row][col] = isBlock;
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static void solve(int[] cr) {
        int[] start = silverfishCell;
        if (start == null) {
            return;
        }
        Set<Integer> seen = new HashSet<>();
        ArrayDeque<List<int[]>> queue = new ArrayDeque<>();
        List<int[]> first = new ArrayList<>();
        first.add(start);
        queue.add(first);
        seen.add(start[0] * BOARD_SIZE + start[1]);
        while (!queue.isEmpty()) {
            List<int[]> current = queue.removeFirst();
            int[] pos = current.get(current.size() - 1);
            if (pos[0] == 0 && pos[1] >= 7 && pos[1] <= 9) {
                List<Vec3> real = new ArrayList<>();
                for (int[] step : current) {
                    BlockPos b = PuzzleCoords.real(23 - step[1], 66, 24 - step[0], cr);
                    real.add(new Vec3(b.getX(), b.getY(), b.getZ()));
                }
                path = real;
                LOGGER.info("[IcePathSolver] Silverfish at cell ({},{}) - solved in {} push(es)", start[0], start[1],
                        real.size() - 1);
                return;
            }
            for (int[] d : DIRECTIONS) {
                int nextRow = pos[0];
                int nextCol = pos[1];
                while (nextRow + d[0] >= 0 && nextRow + d[0] < BOARD_SIZE && nextCol + d[1] >= 0 && nextCol + d[1] < BOARD_SIZE
                        && !board[nextRow + d[0]][nextCol + d[1]]) {
                    nextRow += d[0];
                    nextCol += d[1];
                }
                if (seen.add(nextRow * BOARD_SIZE + nextCol)) {
                    List<int[]> newPath = new ArrayList<>(current);
                    newPath.add(new int[]{nextRow, nextCol});
                    queue.add(newPath);
                }
            }
        }
        // QUOI keeps the previous path when no solution exists.
    }

    private static void onWorldRender(LevelRenderContext context) {
        if (!IcePathSolverConfig.getInstance().isEnabled()) {
            return;
        }
        List<Vec3> points = path;
        if (points.isEmpty()) {
            return;
        }
        List<Vec3> line = new ArrayList<>(points.size());
        for (Vec3 p : points) {
            line.add(p.add(0.5, 1.0, 0.5));
        }
        SolverEspRender.renderLineStrip(context, line, 0.33f, 1.0f, 0.33f, 1f, 3f);
        if (IcePathSolverConfig.getInstance().isShowNextBox() && points.size() > 2) {
            Vec3 next = points.get(1);
            SolverEspRender.renderOutlineBox(context, new AABB(next.x, next.y, next.z, next.x + 1, next.y + 1, next.z + 1),
                    1.0f, 0.33f, 0.33f, 1f, 2f);
        }
    }

    private static void reset() {
        for (boolean[] row : board) {
            java.util.Arrays.fill(row, false);
        }
        silverfishCell = null;
        path = new ArrayList<>();
        silverfish = null;
        lastPos = Vec3.ZERO;
        stillTicks = 0;
        moving = false;
    }
}
