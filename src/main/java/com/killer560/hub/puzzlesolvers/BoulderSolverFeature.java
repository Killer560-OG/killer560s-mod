package com.killer560.hub.puzzlesolvers;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

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
 * (or, if configured, every remaining) BUTTON to click - it never sends any packet or click itself.
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
    private static long lastScanAttemptMs = 0;
    private static String lastLoggedScan = null;

    private BoulderSolverFeature() {
    }

    /** The next position to click (first remaining solution step), or null - for AutoPuzzles. */
    public static BlockPos getNextClick() {
        List<BoxPosition> positions = currentPositions;
        return positions.isEmpty() ? null : positions.get(0).click();
    }

    public static int getRemainingClicks() {
        return currentPositions.size();
    }

    public static void register() {
        // Shared solver highlight pipelines must exist before the level renderer precompiles them.
        SolverEspRender.init();
        ClientTickEvents.END_CLIENT_TICK.register(BoulderSolverFeature::tick);
        // After translucent TERRAIN, not features: water is drawn after the features pass, so a highlight
        // drawn there ended up painted over by any water behind/around it (killer560, 2026-09-21).
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(BoulderSolverFeature::onWorldRender);
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
            org.slf4j.LoggerFactory.getLogger("killer560smod-puzzles").warn("[BoulderSolver] Failed to load solutions", e);
            return Map.of();
        }
    }

    // [BoulderSolver] diagnostics - logging only.
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("killer560smod-puzzles");
    private static String lastLoggedState = null;

    private static void tick(Minecraft client) {
        tickInner(client);
        RoomEntry current = BoulderSolverConfig.getInstance().isEnabled() && DungeonState.isInDungeon()
                ? LiveMapFeature.currentRoomEntry() : null;
        String state = current == null || !"Boulder".equals(current.name)
                ? "notInRoom(enabled=" + BoulderSolverConfig.getInstance().isEnabled() + " inBoss=" + LiveMapFeature.isInBoss() + ")"
                : "inRoom clayRot=" + java.util.Arrays.toString(LiveMapFeature.currentRoomClayAndRotation())
                + " scanned=" + scannedThisRoom + " remainingClicks=" + currentPositions.size()
                + " solutionsLoaded=" + SOLUTIONS.size();
        if (!state.equals(lastLoggedState)) {
            LOGGER.info("[BoulderSolver] State: {}", state);
            lastLoggedState = state;
        }
    }

    private static void tickInner(Minecraft client) {
        // Boss check: NoammAddons e42d3316 "reset when entering boss" (2026-09-14 port).
        if (!BoulderSolverConfig.getInstance().isEnabled() || !DungeonState.isInDungeon() || LiveMapFeature.isInBoss()) {
            reset();
            return;
        }
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        if (current != lastRoomEntry) {
            lastRoomEntry = current;
            scannedThisRoom = false;
            lastScanAttemptMs = 0;
            currentPositions = new ArrayList<>();
        }
        if (current == null || !"Boulder".equals(current.name) || scannedThisRoom) {
            return;
        }
        int[] clayAndRotation = LiveMapFeature.currentRoomClayAndRotation();
        if (clayAndRotation == null) {
            return;
        }
        // Real bug found and fixed (2026-09-14, code review): scannedThisRoom used to be set true even
        // when the floor pattern matched NO known solution (e.g. scanned before the boulders' chunk
        // finished loading), so the room was never rescanned and the solver stayed blank. Now only a
        // real match marks the room scanned; misses retry once per second.
        long now = System.currentTimeMillis();
        if (now - lastScanAttemptMs < 1000) {
            return;
        }
        lastScanAttemptMs = now;
        scannedThisRoom = scanFloor(client, clayAndRotation[0], clayAndRotation[1], clayAndRotation[2]);
    }

    /** @return true only if the floor matched a known solution. */
    private static boolean scanFloor(Minecraft client, int clayX, int clayZ, int rotationDegrees) {
        Level level = client.level;
        if (level == null) {
            return false;
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
        String scanLog = clayX + "," + clayZ + "," + rotationDegrees + "," + key;
        if (!scanLog.equals(lastLoggedScan)) { // retried every 1s on a miss - log only when the key changes
            lastLoggedScan = scanLog;
            LOGGER.info("[BoulderSolver] Floor scan clay=({},{}) rotation={} key={} solutionFound={} (steps={}){}",
                    clayX, clayZ, rotationDegrees, key, solution != null, solution != null ? solution.size() : 0,
                    solution == null ? " - will rescan" : "");
        }
        if (solution == null) {
            currentPositions = new ArrayList<>();
            return false;
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
        return true;
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
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        boolean showAll = BoulderSolverConfig.getInstance().isShowAllClicks();
        if (showAll) {
            for (BoxPosition pos : currentPositions) {
                renderButton(context, level, pos);
            }
        } else {
            renderButton(context, level, currentPositions.get(0));
        }
    }

    /**
     * killer560, 2026-09-20: "highlight the button itself, not the block it sits on". Each solution
     * quadruple's first pair is the boulder tile, the second is the block you actually right-click - the
     * stone button, the same position {@code AutoBoulder} interacts with and the one NoammAddons' own
     * {@code BoulderSolver} draws ({@code renderBlock(box.click, clickColor)}). We were boxing the first
     * pair, which is the block behind/under the button. Drawing the button's own voxel shape rather than
     * a whole cube keeps the highlight on the button; if the block isn't loaded or has no shape (or the
     * cheat build's Full Block hitbox mixin has already widened it) it falls back to the full cube.
     */
    private static void renderButton(LevelRenderContext context, Level level, BoxPosition pos) {
        BlockPos click = pos.click();
        VoxelShape shape = level.getBlockState(click).getShape(level, click);
        AABB box = shape.isEmpty() ? new AABB(click) : shape.bounds().move(click);
        SolverEspRender.renderWaypoint(context, box, 0.4f, 0.9f, 1.0f, 2f);
    }

    private static void reset() {
        lastRoomEntry = null;
        scannedThisRoom = false;
        lastScanAttemptMs = 0;
        lastLoggedScan = null;
        currentPositions = new ArrayList<>();
    }
}
