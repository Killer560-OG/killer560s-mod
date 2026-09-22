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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Real Hypixel dungeon "Creeper Beams" puzzle solver, ported from Odin's own {@code BeamsSolver.kt}. A
 * fixed real list of candidate Sea Lantern position pairs is bundled
 * ({@code data/killer560smod/puzzles/creeper-beams-solutions.json}, copied verbatim from Odin); a pair is
 * "active" right now only when BOTH of its real positions currently hold a real Sea Lantern block (the
 * player redirects the real beam by rotating panes elsewhere in the room, which changes which lanterns
 * light up). Active pairs get matching-colored boxes and an optional connecting tracer line.
 * <p>
 * Unlike Odin's own block-update-event-triggered recalculation (which needs a real event this codebase
 * doesn't have yet), this simply re-scans the small fixed candidate list every tick while in the room -
 * self-healing the instant a pane rotation changes which lanterns are lit, at a fraction of the cost of
 * Boulder Solver's own per-tick floor scan, so no new mixin/event infrastructure was needed for this one.
 * Carries the same real cross-mod corner/rotation-convention risk as Boulder/Quiz/Ice Fill/Water Board
 * (see TESTING.md Round 76) since the candidate positions are Odin's own bundled fixed relative data.
 */
public final class BeamsSolverFeature {

    private record CandidatePair(int x1, int y1, int z1, int x2, int y2, int z2) {
    }

    private record ActivePair(BlockPos a, BlockPos b, int colorIndex, boolean misaligned) {
    }

    private static final float[][] COLORS = {
            {1.0f, 0.8f, 0.0f}, // gold
            {0.2f, 0.9f, 0.2f}, // green
            {0.9f, 0.4f, 0.9f}, // light purple
            {0.0f, 0.6f, 0.6f}, // dark aqua
            {1.0f, 1.0f, 0.2f}, // yellow
            {0.2f, 0.5f, 1.0f}, // blue (was dark red - red now only means a misaligned pair)
            {1.0f, 1.0f, 1.0f}, // white
            {0.5f, 0.0f, 0.5f}, // dark purple
    };

    private static final List<CandidatePair> CANDIDATES = loadCandidates();
    private static List<ActivePair> activePairs = new ArrayList<>();
    private static RoomEntry lastRoomEntry = null;

    private BeamsSolverFeature() {
    }

    /** Currently lit lantern pairs as {@code {first, second}} (candidate-list order) - a copy, for AutoPuzzles. */
    public static List<BlockPos[]> getActivePairs() {
        List<BlockPos[]> out = new ArrayList<>();
        for (ActivePair pair : activePairs) {
            if (!pair.misaligned()) {
                out.add(new BlockPos[]{pair.a(), pair.b()});
            }
        }
        return out;
    }

    public static void register() {
        // Shared solver highlight pipelines must exist before the level renderer precompiles them.
        SolverEspRender.init();
        ClientTickEvents.END_CLIENT_TICK.register(BeamsSolverFeature::tick);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(BeamsSolverFeature::onWorldRender);
    }

    private static List<CandidatePair> loadCandidates() {
        try (InputStream stream = BeamsSolverFeature.class.getClassLoader()
                .getResourceAsStream("data/killer560smod/puzzles/creeper-beams-solutions.json")) {
            if (stream == null) {
                return List.of();
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                Type type = new TypeToken<List<int[]>>() {
                }.getType();
                List<int[]> raw = new Gson().fromJson(reader, type);
                if (raw == null) {
                    return List.of();
                }
                List<CandidatePair> pairs = new ArrayList<>();
                for (int[] entry : raw) {
                    if (entry.length == 6) {
                        pairs.add(new CandidatePair(entry[0], entry[1], entry[2], entry[3], entry[4], entry[5]));
                    }
                }
                return pairs;
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger("killer560smod-puzzles").warn("[BeamsSolver] Failed to load candidates", e);
            return List.of();
        }
    }

    // [BeamsSolver] diagnostics - logging only.
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("killer560smod-puzzles");
    private static String lastLoggedState = null;

    private static void tick(Minecraft client) {
        tickInner(client);
        RoomEntry current = BeamsSolverConfig.getInstance().isEnabled() && DungeonState.isInDungeon()
                ? LiveMapFeature.currentRoomEntry() : null;
        String state = current == null || !"Creeper Beams".equals(current.name)
                ? "notInRoom(enabled=" + BeamsSolverConfig.getInstance().isEnabled() + " inBoss=" + LiveMapFeature.isInBoss() + ")"
                : "inRoom clayRot=" + java.util.Arrays.toString(LiveMapFeature.currentRoomClayAndRotation())
                + " candidates=" + CANDIDATES.size() + " activePairs=" + activePairs.size();
        if (!state.equals(lastLoggedState)) {
            LOGGER.info("[BeamsSolver] State: {}", state);
            lastLoggedState = state;
        }
    }

    private static void tickInner(Minecraft client) {
        // Boss check: NoammAddons e42d3316 "reset when entering boss" (2026-09-14 port).
        if (!BeamsSolverConfig.getInstance().isEnabled() || !DungeonState.isInDungeon() || LiveMapFeature.isInBoss()) {
            activePairs = new ArrayList<>();
            lastRoomEntry = null;
            return;
        }
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        if (current != lastRoomEntry) {
            lastRoomEntry = current;
            activePairs = new ArrayList<>();
        }
        if (current == null || !"Creeper Beams".equals(current.name)) {
            return;
        }
        int[] clayAndRotation = LiveMapFeature.currentRoomClayAndRotation();
        Level level = client.level;
        if (clayAndRotation == null || level == null) {
            return;
        }
        rescan(level, clayAndRotation);
    }

    private static void rescan(Level level, int[] clayAndRotation) {
        List<ActivePair> found = new ArrayList<>();
        for (int i = 0; i < CANDIDATES.size(); i++) {
            CandidatePair candidate = CANDIDATES.get(i);
            BlockPos a = realPos(candidate.x1(), candidate.y1(), candidate.z1(), clayAndRotation);
            BlockPos b = realPos(candidate.x2(), candidate.y2(), candidate.z2(), clayAndRotation);
            boolean litA = level.getBlockState(a).is(Blocks.SEA_LANTERN);
            boolean litB = level.getBlockState(b).is(Blocks.SEA_LANTERN);
            if (litA && litB) {
                found.add(new ActivePair(a, b, i % COLORS.length, false));
            } else if (litA != litB && (usedUp(level, a) || usedUp(level, b))) {
                // Misaligned (killer560, 2026-09-21: "if I misshoot something and they are not aligned properly, then
                // the blocks and line they make should be red"): one lantern of this solution pair was used up
                // with the WRONG partner (it turned to prismarine) while its right partner is still lit - that
                // lantern can no longer be finished correctly. Red box on both, red line.
                found.add(new ActivePair(a, b, i % COLORS.length, true));
            }
        }
        activePairs = found;
    }

    /** A lantern that has been connected already turns into prismarine. */
    private static boolean usedUp(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.is(Blocks.PRISMARINE) || state.is(Blocks.PRISMARINE_BRICKS) || state.is(Blocks.DARK_PRISMARINE);
    }

    private static BlockPos realPos(int x, int y, int z, int[] clayAndRotation) {
        RoomEntry.Pos relative = new RoomEntry.Pos();
        relative.x = x;
        relative.y = y;
        relative.z = z;
        return RoomDatabase.toRealCoord(relative, clayAndRotation[0], clayAndRotation[1], clayAndRotation[2]);
    }

    private static void onWorldRender(LevelRenderContext context) {
        BeamsSolverConfig cfg = BeamsSolverConfig.getInstance();
        if (!cfg.isEnabled() || activePairs.isEmpty()) {
            return;
        }
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        if (current == null || !"Creeper Beams".equals(current.name)) {
            return;
        }
        for (ActivePair pair : activePairs) {
            float[] color = pair.misaligned() ? new float[]{1.0f, 0.15f, 0.15f} : COLORS[pair.colorIndex()];
            SolverEspRender.renderWaypoint(context, new AABB(pair.a()), color[0], color[1], color[2], 2f);
            SolverEspRender.renderWaypoint(context, new AABB(pair.b()), color[0], color[1], color[2], 2f);
            if (cfg.isShowTracer() || pair.misaligned()) {
                List<Vec3> line = List.of(
                        new Vec3(pair.a().getX() + 0.5, pair.a().getY() + 0.5, pair.a().getZ() + 0.5),
                        new Vec3(pair.b().getX() + 0.5, pair.b().getY() + 0.5, pair.b().getZ() + 0.5));
                SolverEspRender.renderLineStrip(context, line, color[0], color[1], color[2], 1f, 2f);
            }
        }
    }
}
