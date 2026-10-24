package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.util.FeatureGuard;
import com.google.gson.Gson;
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
import net.minecraft.world.phys.Vec3;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Real Hypixel dungeon "Ice Fill" puzzle solver, ported from Odin's own {@code IceFillSolver.kt}. The
 * puzzle has 3 real floors, each with several possible real ice layouts; each floor's layout is
 * identified by checking 2 fixed real relative positions (the first must be real air, the second must
 * NOT be air - a unique fingerprint for that layout, ported directly from Odin's own real
 * {@code identifier} data) and, once identified, that layout's known real safe walking path (a real,
 * pre-solved list of relative positions, {@code easy} or {@code hard} depending on
 * {@link IceFillSolverConfig#isOptimizedPath()}) is drawn as a connected line through all 3 floors. All
 * real position/path data is bundled verbatim from Odin in
 * {@code data/killer560smod/puzzles/ice-fill-floors.json}. Never walks for you - only draws the path.
 */
public final class IceFillSolverFeature {

    private record Pos(int x, int y, int z) {
    }

    private record IceFillData(List<List<List<Pos>>> identifier, List<List<List<Pos>>> easy,
                                List<List<List<Pos>>> hard) {
    }

    private static final IceFillData DATA = loadData();
    private static final List<Vec3> currentPath = new ArrayList<>();
    private static RoomEntry lastRoomEntry = null;
    /** The Alternate Path setting the drawn path was built with: flipping it in the room redraws instead of keeping the old line. */
    private static boolean pathOptimized = false;

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-puzzles");
    /** Logging only: last room state and last scan failure logged, so lines are written on CHANGE, never per tick. */
    private static String roomLogKey = "none";
    private static String scanLogKey = null;
    private static String noPathLogKey = null;

    private static void logRoomState(String key, String detail) {
        if (key.equals(roomLogKey)) {
            return;
        }
        roomLogKey = key;
        scanLogKey = null;
        LOGGER.info("[IceFillSolver] {}", detail);
    }

    private IceFillSolverFeature() {
    }

    /** Copy of the drawn path (block center x/z, floor y + 0.1) - for AutoPuzzles. Empty until all 3 floors scan. */
    public static List<Vec3> getCurrentPath() {
        return new ArrayList<>(currentPath);
    }

    public static void register() {
        // Shared solver highlight pipelines must exist before the level renderer precompiles them.
        SolverEspRender.init();
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("IceFillSolverFeature", com.killer560.hub.util.FeatureBits.register("IceFill Solver tick", () -> IceFillSolverConfig.getInstance().isEnabledRaw()), client -> tick(client)));
        // After translucent TERRAIN, not features: water is drawn after the features pass, so a highlight
        // drawn there ended up painted over by any water behind/around it (killer560, 2026-09-21).
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(com.killer560.hub.util.FeatureGuard.afterTerrain(
                com.killer560.hub.util.FeatureBits.register("IceFill Solver render", () -> IceFillSolverConfig.getInstance().isEnabledRaw()), IceFillSolverFeature::onWorldRender));
    }

    private static IceFillData loadData() {
        try (InputStream stream = IceFillSolverFeature.class.getClassLoader()
                .getResourceAsStream("data/killer560smod/puzzles/ice-fill-floors.json")) {
            if (stream == null) {
                // Inline logger: this runs in the static initializer, before LOGGER is assigned.
                com.killer560.hub.util.ModLog.get("killer560smod-puzzles").warn(
                        "[IceFillSolver] floors resource missing - no floor will ever be identified");
                return new IceFillData(List.of(), List.of(), List.of());
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                IceFillData parsed = new Gson().fromJson(reader, IceFillData.class);
                return parsed != null ? parsed : new IceFillData(List.of(), List.of(), List.of());
            }
        } catch (Exception e) {
            com.killer560.hub.util.ModLog.get("killer560smod-puzzles").warn("[IceFillSolver] Failed to load floors data", e);
            return new IceFillData(List.of(), List.of(), List.of());
        }
    }

    private static void tick(Minecraft client) {
        tickInner(client);
    }

    private static void tickInner(Minecraft client) {
        // Boss check: NoammAddons e42d3316 "reset when entering boss" (2026-09-14 port).
        if (!IceFillSolverConfig.getInstance().isEnabled() || !DungeonState.isInDungeon() || LiveMapFeature.isInBoss()) {
            if (!"none".equals(roomLogKey)) {
                logRoomState("none", "solver idle: enabled=" + IceFillSolverConfig.getInstance().isEnabled()
                        + " inDungeon=" + DungeonState.isInDungeon() + " inBoss=" + LiveMapFeature.isInBoss()
                        + " - path cleared");
            }
            reset();
            return;
        }
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        if (current != lastRoomEntry) {
            lastRoomEntry = current;
            currentPath.clear();
        }
        if ((current == null || !"Ice Fill".equals(current.name)) && !"none".equals(roomLogKey)) {
            logRoomState("none", "left Ice Fill: now in " + (current == null ? "no known room" : current.name));
        }
        if (!currentPath.isEmpty() && pathOptimized != IceFillSolverConfig.getInstance().isOptimizedPath()) {
            currentPath.clear();
        }
        if (current == null || !"Ice Fill".equals(current.name) || !currentPath.isEmpty()) {
            return;
        }
        int[] clayAndRotation = LiveMapFeature.currentRoomClayAndRotation();
        Level level = client.level;
        if (clayAndRotation == null || level == null) {
            logRoomState("wait", "in Ice Fill but not scanning: clay/rotation known=" + (clayAndRotation != null)
                    + " level=" + (level != null) + " - waiting for the map to resolve the room");
            return;
        }
        String roomKey = "room " + clayAndRotation[0] + "," + clayAndRotation[1] + " rot " + clayAndRotation[2];
        if (!roomKey.equals(roomLogKey)) {
            logRoomState(roomKey, "Ice Fill detected: clay=" + clayAndRotation[0] + "," + clayAndRotation[1]
                    + " rotation=" + clayAndRotation[2] + " floors=" + DATA.identifier().size()
                    + " optimizedPath=" + IceFillSolverConfig.getInstance().isOptimizedPath());
        }
        scanFloors(level, clayAndRotation);
    }

    private static void scanFloors(Level level, int[] clayAndRotation) {
        List<List<List<Pos>>> paths = IceFillSolverConfig.getInstance().isOptimizedPath() ? DATA.hard() : DATA.easy();
        pathOptimized = IceFillSolverConfig.getInstance().isOptimizedPath();
        StringBuilder patterns = new StringBuilder(); // logging only: which layout each floor matched
        for (int floorIndex = 0; floorIndex < DATA.identifier().size(); floorIndex++) {
            List<List<Pos>> floorIdentifiers = DATA.identifier().get(floorIndex);
            boolean found = false;
            for (int patternIndex = 0; patternIndex < floorIdentifiers.size(); patternIndex++) {
                List<Pos> pair = floorIdentifiers.get(patternIndex);
                BlockPos mustBeAir = realPos(pair.get(0), clayAndRotation);
                BlockPos mustBeSolid = realPos(pair.get(1), clayAndRotation);
                if (level.getBlockState(mustBeAir).isAir() && !level.getBlockState(mustBeSolid).isAir()) {
                    if (floorIndex < paths.size() && patternIndex < paths.get(floorIndex).size()) {
                        for (Pos step : paths.get(floorIndex).get(patternIndex)) {
                            BlockPos real = realPos(step, clayAndRotation);
                            currentPath.add(new Vec3(real.getX() + 0.5, real.getY() + 0.1, real.getZ() + 0.5));
                        }
                    } else {
                        String noPathKey = floorIndex + ":" + patternIndex;
                        if (!noPathKey.equals(noPathLogKey)) {
                            noPathLogKey = noPathKey;
                            LOGGER.warn("[IceFillSolver] floor {} matched layout {} but the bundled path list has no"
                                    + " entry for it - that floor draws no line", floorIndex + 1, patternIndex);
                        }
                    }
                    patterns.append(' ').append(floorIndex + 1).append('=').append(patternIndex);
                    found = true;
                    break;
                }
            }
            if (!found) {
                // Odin logs a per-floor failure and still draws whatever floors DID identify; simplified
                // here to retry the whole scan next tick instead - self-healing once the real ice finishes
                // spawning, and avoids ever showing a path that's missing a floor in the middle.
                logScan("miss " + floorIndex, "scan failed: floor " + (floorIndex + 1) + " matched none of its "
                        + floorIdentifiers.size() + " layouts (floors matched so far:" + patterns
                        + ") - ice not spawned/loaded yet or wrong clay/rotation; retrying every tick");
                currentPath.clear();
                return;
            }
        }
        // Keyed: an all-empty path leaves currentPath empty and the scan runs again next tick.
        logScan("solved" + patterns, "solution computed: layouts" + patterns + " -> path of " + currentPath.size()
                + " points, " + (IceFillSolverConfig.getInstance().isOptimizedPath() ? "optimized" : "easy")
                + " path, starts at " + (currentPath.isEmpty() ? "none" : String.format(java.util.Locale.ROOT,
                "%.1f,%.1f,%.1f", currentPath.get(0).x, currentPath.get(0).y, currentPath.get(0).z)));
    }

    /** Logging only: a scan line, once per distinct reason until the room state changes. */
    private static void logScan(String key, String detail) {
        if (key.equals(scanLogKey)) {
            return;
        }
        scanLogKey = key;
        LOGGER.info("[IceFillSolver] {}", detail);
    }

    /** Delegating to {@link PuzzleCoords#real} - see the note there about the sim's floor shift. */
    private static BlockPos realPos(Pos pos, int[] clayAndRotation) {
        return PuzzleCoords.real(pos.x(), pos.y(), pos.z(), clayAndRotation);
    }

    private static void onWorldRender(LevelRenderContext context) {
        if (!IceFillSolverConfig.getInstance().isEnabled() || currentPath.isEmpty()) {
            return;
        }
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        if (current == null || !"Ice Fill".equals(current.name)) {
            return;
        }
        // killer560, 2026-09-20: "ice fill's line shouldn't be blue, hard to see - make it configurable".
        IceFillSolverConfig.LineColor color = IceFillSolverConfig.getInstance().getLineColor();
        SolverEspRender.renderLineStrip(com.killer560.hub.puzzlesolvers.SolverEspConfig.Solver.ICE_FILL, context, currentPath, color.r, color.g, color.b, 1f, 3f);
    }

    private static void reset() {
        lastRoomEntry = null;
        currentPath.clear();
    }
}
