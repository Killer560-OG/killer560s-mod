package com.killer560.hub.puzzlesolvers;

import com.google.gson.Gson;
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

    private IceFillSolverFeature() {
    }

    /** Copy of the drawn path (block centre x/z, floor y + 0.1) - for AutoPuzzles. Empty until all 3 floors scan. */
    public static List<Vec3> getCurrentPath() {
        return new ArrayList<>(currentPath);
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick(client));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(IceFillSolverFeature::onWorldRender);
    }

    private static IceFillData loadData() {
        try (InputStream stream = IceFillSolverFeature.class.getClassLoader()
                .getResourceAsStream("data/killer560smod/puzzles/ice-fill-floors.json")) {
            if (stream == null) {
                return new IceFillData(List.of(), List.of(), List.of());
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                IceFillData parsed = new Gson().fromJson(reader, IceFillData.class);
                return parsed != null ? parsed : new IceFillData(List.of(), List.of(), List.of());
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger("killer560smod-puzzles").warn("[IceFillSolver] Failed to load floors data", e);
            return new IceFillData(List.of(), List.of(), List.of());
        }
    }

    // [IceFillSolver] diagnostics - logging only.
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("killer560smod-puzzles");
    private static String lastLoggedState = null;
    private static int lastFailedFloor = -1;

    private static void tick(Minecraft client) {
        tickInner(client);
        RoomEntry current = IceFillSolverConfig.getInstance().isEnabled() && DungeonState.isInDungeon()
                ? LiveMapFeature.currentRoomEntry() : null;
        String state = current == null || !"Ice Fill".equals(current.name)
                ? "notInRoom(enabled=" + IceFillSolverConfig.getInstance().isEnabled() + " inBoss=" + LiveMapFeature.isInBoss() + ")"
                : "inRoom clayRot=" + java.util.Arrays.toString(LiveMapFeature.currentRoomClayAndRotation())
                + " pathPoints=" + currentPath.size() + " failedFloor=" + lastFailedFloor
                + " identifierFloors=" + DATA.identifier().size();
        if (!state.equals(lastLoggedState)) {
            LOGGER.info("[IceFillSolver] State: {}", state);
            lastLoggedState = state;
        }
    }

    private static void tickInner(Minecraft client) {
        // Boss check: NoammAddons e42d3316 "reset when entering boss" (2026-09-14 port).
        if (!IceFillSolverConfig.getInstance().isEnabled() || !DungeonState.isInDungeon() || LiveMapFeature.isInBoss()) {
            reset();
            return;
        }
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        if (current != lastRoomEntry) {
            lastRoomEntry = current;
            currentPath.clear();
        }
        if (current == null || !"Ice Fill".equals(current.name) || !currentPath.isEmpty()) {
            return;
        }
        int[] clayAndRotation = LiveMapFeature.currentRoomClayAndRotation();
        Level level = client.level;
        if (clayAndRotation == null || level == null) {
            return;
        }
        scanFloors(level, clayAndRotation);
    }

    private static void scanFloors(Level level, int[] clayAndRotation) {
        List<List<List<Pos>>> paths = IceFillSolverConfig.getInstance().isOptimizedPath() ? DATA.hard() : DATA.easy();
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
                    }
                    found = true;
                    break;
                }
            }
            if (!found) {
                // Odin logs a per-floor failure and still draws whatever floors DID identify; simplified
                // here to retry the whole scan next tick instead - self-healing once the real ice finishes
                // spawning, and avoids ever showing a path that's missing a floor in the middle.
                currentPath.clear();
                lastFailedFloor = floorIndex; // logged via the state line on change
                return;
            }
        }
        lastFailedFloor = -1;
    }

    private static BlockPos realPos(Pos pos, int[] clayAndRotation) {
        RoomEntry.Pos relative = new RoomEntry.Pos();
        relative.x = pos.x();
        relative.y = pos.y();
        relative.z = pos.z();
        return RoomDatabase.toRealCoord(relative, clayAndRotation[0], clayAndRotation[1], clayAndRotation[2]);
    }

    private static void onWorldRender(LevelRenderContext context) {
        if (!IceFillSolverConfig.getInstance().isEnabled() || currentPath.isEmpty()) {
            return;
        }
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        if (current == null || !"Ice Fill".equals(current.name)) {
            return;
        }
        WorldRenderUtils.renderLineStrip(context, currentPath, 0.4f, 0.8f, 1.0f, 1f, 3f);
    }

    private static void reset() {
        lastRoomEntry = null;
        currentPath.clear();
    }
}
