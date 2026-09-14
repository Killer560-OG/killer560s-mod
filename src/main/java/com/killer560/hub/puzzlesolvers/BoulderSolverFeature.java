package com.killer560.hub.puzzlesolvers;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Real Hypixel dungeon "Boulder" puzzle solver, ported from Odin's own {@code BoulderSolver.kt}. The
 * puzzle's floor is a real 6x7 grid of pressure-plate-sized tiles at y=66 (relative room coordinates
 * z in {24,21,18,15,12,9}, x in {24,21,18,15,12,9,6}) that is either solid or air in one of 8 known real
 * patterns; each pattern maps to a fixed real solution list of {@code [renderX, renderZ, clickX, clickZ]}
 * quadruples at y=65 (bundled verbatim from Odin in {@code data/killer560smod/puzzles/boulder-solutions.json}).
 * The player must click each listed position in any order; this only ever renders a box around the next
 * (or, if configured, every remaining) solution position - it never sends any packet or click itself.
 *
 * <p>Room-relative coordinates are translated to real world coordinates via
 * {@link com.killer560.hub.roomdatabase.RoomDatabase#toRealCoord}, the same real corner/rotation transform
 * already proven by Secret Waypoints. That transform's 4 rotation cases were hand-verified to be
 * mathematically identical to Odin's own {@code rotateAroundNorth}, but whether the two independently
 * written mods agree on which physical corner maps to which rotation VALUE is not provable from code
 * alone - see TESTING.md for the real live-test this still needs.
 */
public final class BoulderSolverFeature {

    private record BoxPosition(AABB render, BlockPos click) {
    }

    private static final Map<String, List<List<Integer>>> SOLUTIONS = loadSolutions();
    private static List<BoxPosition> currentPositions = new ArrayList<>();
    private static RoomEntry lastRoomEntry = null;
    private static boolean scannedThisRoom = false;

    private BoulderSolverFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(BoulderSolverFeature::tick);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(BoulderSolverFeature::onWorldRender);
    }

    private static Map<String, List<List<Integer>>> loadSolutions() {
        try (InputStream stream = BoulderSolverFeature.class.getClassLoader()
                .getResourceAsStream("data/killer560smod/puzzles/boulder-solutions.json")) {
            if (stream == null) {
                return Map.of();
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                Type type = new TypeToken<Map<String, List<List<Integer>>>>() {
                }.getType();
                Map<String, List<List<Integer>>> parsed = new Gson().fromJson(reader, type);
                return parsed != null ? parsed : Map.of();
            }
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static void tick(Minecraft client) {
        if (!BoulderSolverConfig.getInstance().isEnabled() || !DungeonState.isInDungeon()) {
            reset();
            return;
        }
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        if (current != lastRoomEntry) {
            lastRoomEntry = current;
            scannedThisRoom = false;
            currentPositions = new ArrayList<>();
        }
        if (current == null || !"Boulder".equals(current.name) || scannedThisRoom) {
            return;
        }
        int[] clayAndRotation = LiveMapFeature.currentRoomClayAndRotation();
        if (clayAndRotation == null) {
            return;
        }
        scanFloor(client, clayAndRotation[0], clayAndRotation[1], clayAndRotation[2]);
        scannedThisRoom = true;
    }

    private static void scanFloor(Minecraft client, int clayX, int clayZ, int rotationDegrees) {
        Level level = client.level;
        if (level == null) {
            return;
        }
        StringBuilder key = new StringBuilder(42);
        for (int z = 24; z >= 9; z -= 3) {
            for (int x = 24; x >= 6; x -= 3) {
                RoomEntry.Pos relative = new RoomEntry.Pos();
                relative.x = x;
                relative.y = 66;
                relative.z = z;
                BlockPos real = RoomDatabase.toRealCoord(relative, clayX, clayZ, rotationDegrees);
                key.append(level.getBlockState(real).isAir() ? '0' : '1');
            }
        }
        List<List<Integer>> solution = SOLUTIONS.get(key.toString());
        if (solution == null) {
            currentPositions = new ArrayList<>();
            return;
        }
        List<BoxPosition> positions = new ArrayList<>();
        for (List<Integer> sol : solution) {
            RoomEntry.Pos renderPos = new RoomEntry.Pos();
            renderPos.x = sol.get(0);
            renderPos.y = 65;
            renderPos.z = sol.get(1);
            RoomEntry.Pos clickPos = new RoomEntry.Pos();
            clickPos.x = sol.get(2);
            clickPos.y = 65;
            clickPos.z = sol.get(3);
            BlockPos render = RoomDatabase.toRealCoord(renderPos, clayX, clayZ, rotationDegrees);
            BlockPos click = RoomDatabase.toRealCoord(clickPos, clayX, clayZ, rotationDegrees);
            positions.add(new BoxPosition(new AABB(render), click));
        }
        currentPositions = positions;
    }

    public static void onPlayerInteract(BlockPos clicked) {
        if (!BoulderSolverConfig.getInstance().isEnabled() || currentPositions.isEmpty()) {
            return;
        }
        currentPositions.removeIf(pos -> pos.click().equals(clicked));
    }

    private static void onWorldRender(LevelRenderContext context) {
        if (!BoulderSolverConfig.getInstance().isEnabled() || currentPositions.isEmpty()) {
            return;
        }
        boolean showAll = BoulderSolverConfig.getInstance().isShowAllClicks();
        if (showAll) {
            for (BoxPosition pos : currentPositions) {
                WorldRenderUtils.renderOutlineBox(context, pos.render(), 0.4f, 0.9f, 1.0f, 1f, 2f);
            }
        } else {
            WorldRenderUtils.renderOutlineBox(context, currentPositions.get(0).render(), 0.4f, 0.9f, 1.0f, 1f, 2f);
        }
    }

    private static void reset() {
        lastRoomEntry = null;
        scannedThisRoom = false;
        currentPositions = new ArrayList<>();
    }
}
