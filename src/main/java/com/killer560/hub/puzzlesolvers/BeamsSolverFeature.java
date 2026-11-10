package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.util.FeatureGuard;
import com.google.gson.reflect.TypeToken;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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

    private record ActivePair(BlockPos a, BlockPos b, int colorIndex, boolean misaligned, int candidate) {
    }

    /**
     * One color per pair, every one clearly apart from the others (killer560, 2026-11-01: "sometimes the beam colors can be
     * very similar. Make sure they are not. I just had one with two yellows in it"). The old list had gold AND yellow, and
     * a pair took {@code candidate index % 8}, so two pairs whose indices were 8 apart drew the same color. Now each pair
     * in the room takes the first palette color no other pair in the room holds ({@link #colorFor}), and the palette is
     * eight hues far apart; a room has four pairs, so the first four (yellow, green, cyan, magenta) are what is seen. Red
     * is not in it: red only ever means a misaligned pair.
     */
    static final float[][] COLORS = {
            {1.0f, 1.0f, 0.0f}, // yellow
            {0.1f, 1.0f, 0.1f}, // green
            {0.0f, 0.9f, 1.0f}, // cyan
            {1.0f, 0.2f, 1.0f}, // magenta
            {1.0f, 0.5f, 0.0f}, // orange
            {0.25f, 0.35f, 1.0f}, // blue
            {1.0f, 1.0f, 1.0f}, // white
            {0.55f, 0.25f, 0.0f}, // brown
    };

    /** Candidate index -> palette index, for this visit to the room: a pair keeps its color until it is done. */
    private static final java.util.Map<Integer, Integer> assigned = new java.util.HashMap<>();
    /** The pairs One At A Time / Next shows, in the order they were picked (candidate indices). */
    private static final List<Integer> shownOrder = new ArrayList<>();
    /** The creeper's feet while One At A Time / Next has not picked its first pair yet; null when not seen. */
    private static Vec3 creeperFeet;
    /** Whether this visit already opened with the creeper's pair: only the FIRST set gets it. */
    private static boolean openingUsed;
    /** Scans that found no creeper yet; after {@link #CREEPER_WAIT_SCANS} the nearest pair opens for good. */
    private static int creeperMisses;
    private static final int CREEPER_WAIT_SCANS = 40;
    /** Most blocks sideways each lantern of the opening pair may sit from the creeper (feet and about straight above). */
    private static final double OPENING_REACH = 3.5;

    private static final List<CandidatePair> CANDIDATES = loadCandidates();
    private static List<ActivePair> activePairs = new ArrayList<>();
    private static RoomEntry lastRoomEntry = null;

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-puzzles");
    /** Logging only: last room state and last scan result logged, so a line is written on CHANGE, never per tick. */
    private static final SolverLog LOG = new SolverLog(LOGGER, "[BeamsSolver]");

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
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("BeamsSolverFeature.tick", com.killer560.hub.util.FeatureBits.register("Beams Solver tick", () -> BeamsSolverConfig.getInstance().isEnabledRaw()), BeamsSolverFeature::tick));
        SolverEspRender.registerRender("Beams Solver", () -> BeamsSolverConfig.getInstance().isEnabledRaw(), BeamsSolverFeature::onWorldRender);
    }

    private static List<CandidatePair> loadCandidates() {
        List<int[]> raw = SolverData.load("creeper-beams-solutions.json", new TypeToken<List<int[]>>() {
        }.getType(), null, "killer560smod-puzzles", "[BeamsSolver]",
                "candidate list resource missing - the solver will never highlight anything", "candidates");
        if (raw == null) return List.of();
        List<CandidatePair> pairs = new ArrayList<>();
        for (int[] entry : raw) {
            if (entry.length == 6) {
                pairs.add(new CandidatePair(entry[0], entry[1], entry[2], entry[3], entry[4], entry[5]));
            }
        }
        com.killer560.hub.util.ModLog.get("killer560smod-puzzles").info(
                "[BeamsSolver] loaded {} candidate pairs ({} raw entries)", pairs.size(), raw.size());
        return pairs;
    }

    private static void tick(Minecraft client) { tickInner(client); }

    private static void tickInner(Minecraft client) {
        // Boss check: NoammAddons e42d3316 "reset when entering boss" (2026-09-14 port).
        if (!BeamsSolverConfig.getInstance().isEnabled() || !DungeonState.isInDungeon() || LiveMapFeature.isInBoss()) {
            if (!"none".equals(LOG.roomKey)) {
                LOG.room("none", "solver idle: enabled=" + BeamsSolverConfig.getInstance().isEnabled()
                        + " inDungeon=" + DungeonState.isInDungeon() + " inBoss=" + LiveMapFeature.isInBoss()
                        + " - highlights cleared");
            }
            if (!activePairs.isEmpty()) {
                activePairs = new ArrayList<>();
            }
            lastRoomEntry = null;
            return;
        }
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        if (current != lastRoomEntry) {
            lastRoomEntry = current;
            activePairs = new ArrayList<>();
            assigned.clear();
            shownOrder.clear();
            creeperFeet = null;
            openingUsed = false;
            creeperMisses = 0;
        }
        if (current == null || !"Creeper Beams".equals(current.name)) {
            if (!"none".equals(LOG.roomKey)) {
                LOG.room("none", "left Creeper Beams: now in " + (current == null ? "no known room" : current.name));
            }
            return;
        }
        int[] clayAndRotation = LiveMapFeature.currentRoomClayAndRotation();
        Level level = client.level;
        if (clayAndRotation == null || level == null) {
            LOG.room("wait", "in Creeper Beams but not solving: clay/rotation known=" + (clayAndRotation != null)
                    + " level=" + (level != null) + " - waiting for the map to resolve the room");
            return;
        }
        String roomKey = "room " + clayAndRotation[0] + "," + clayAndRotation[1] + " rot " + clayAndRotation[2];
        if (!roomKey.equals(LOG.roomKey)) {
            LOG.room(roomKey, "Creeper Beams detected: clay=" + clayAndRotation[0] + "," + clayAndRotation[1]
                    + " rotation=" + clayAndRotation[2] + " candidatePairs=" + CANDIDATES.size());
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
                found.add(new ActivePair(a, b, -1, false, i));
            } else if (litA != litB && (usedUp(level, a) || usedUp(level, b))) {
                // Misaligned (killer560, 2026-09-21: "if I misshoot something and they are not aligned properly, then
                // the blocks and line they make should be red"): one lantern of this solution pair was used up
                // with the WRONG partner (it turned to prismarine) while its right partner is still lit - that
                // lantern can no longer be finished correctly. Red box on both, red line.
                found.add(new ActivePair(a, b, -1, true, i));
            }
        }
        // A pair that is no longer lit is done (or was never lit): free its color for a pair seen later.
        java.util.Set<Integer> live = new java.util.HashSet<>();
        for (ActivePair p : found) {
            live.add(p.candidate());
        }
        assigned.keySet().retainAll(live);
        shownOrder.retainAll(live);
        List<ActivePair> colored = new ArrayList<>(found.size());
        for (ActivePair p : found) {
            colored.add(new ActivePair(p.a(), p.b(), colorFor(p.candidate()), p.misaligned(), p.candidate()));
        }
        activePairs = colored;
        // Look the creeper up only while the first pick is still to make (an entity query, not every tick forever).
        if (!openingUsed && BeamsSolverConfig.getInstance().getShowMode() != BeamsSolverConfig.ShowMode.ALL
                && !colored.isEmpty()) {
            BlockPos c = colored.get(0).a();
            var creepers = level.getEntitiesOfClass(net.minecraft.world.entity.monster.Creeper.class,
                    new AABB(c).inflate(24));
            creeperFeet = creepers.isEmpty() ? null : creepers.get(0).position();
            if (creeperFeet == null && ++creeperMisses >= CREEPER_WAIT_SCANS) openingUsed = true;
        }
        logScan(found);
    }

    /** The palette color for a candidate pair: the one it already holds, else the first no other live pair holds. */
    private static int colorFor(int candidate) {
        Integer have = assigned.get(candidate);
        if (have != null) return have;
        java.util.Set<Integer> taken = new java.util.HashSet<>(assigned.values());
        int pick = 0;
        for (int c = 0; c < COLORS.length; c++) {
            if (!taken.contains(c)) {
                pick = c;
                break;
            }
        }
        assigned.put(candidate, pick);
        return pick;
    }

    /**
     * The lit pairs to draw under the Show setting (killer560, 2026-11-01: "an option for one at a time where it only shows
     * one beam at a time till you are done. Add a next option as well where itll only show 2"). All: every pair. One At A
     * Time: one pair until it is done (its lanterns go out), then the next. Next: that pair and the one after it. A pair
     * once shown stays shown until done; a new one is the lit pair nearest {@code eye}. Misaligned (red) pairs are always
     * drawn: they are a mistake to see, not a step to take.
     */
    static List<ActivePair> pairsToDraw(List<ActivePair> pairs, BeamsSolverConfig.ShowMode mode, Vec3 eye) {
        if (mode == BeamsSolverConfig.ShowMode.ALL) return pairs;
        int limit = mode == BeamsSolverConfig.ShowMode.ONE ? 1 : 2;
        List<ActivePair> lit = new ArrayList<>();
        for (ActivePair p : pairs) {
            if (!p.misaligned()) {
                lit.add(p);
            }
        }
        if (!openingUsed && creeperFeet == null) {
            // Waiting for the creeper to load (at most CREEPER_WAIT_SCANS scans): draw no step yet, because the nearest
            // pair shown now would be replaced by the creeper's pair a few ticks later (killer560, 2026-11-09).
            lit.clear();
        }
        if (!openingUsed) {
            // Until the creeper is found (or given up on) nothing is locked in: a pick made before it loaded would
            // keep the nearest pair first for good (the 2026-11-08 "not the one below it every time" case).
            shownOrder.clear();
        }
        if (!openingUsed && !lit.isEmpty()) {
            ActivePair opening = openingPair(lit, creeperFeet);
            if (opening != null) {
                shownOrder.add(opening.candidate());
            }
            openingUsed = creeperFeet != null;
        }
        while (shownOrder.size() < limit) {
            ActivePair nearest = null;
            double best = Double.MAX_VALUE;
            for (ActivePair p : lit) {
                if (shownOrder.contains(p.candidate())) continue;
                double d = eye == null ? 0 : Math.min(eye.distanceToSqr(Vec3.atCenterOf(p.a())),
                        eye.distanceToSqr(Vec3.atCenterOf(p.b())));
                if (d < best) {
                    best = d;
                    nearest = p;
                }
            }
            if (nearest == null) break;
            shownOrder.add(nearest.candidate());
        }
        List<ActivePair> out = new ArrayList<>();
        for (int i = 0; i < shownOrder.size() && i < limit; i++) {
            for (ActivePair p : lit) {
                if (p.candidate() == shownOrder.get(i)) {
                    out.add(p);
                }
            }
        }
        for (ActivePair p : pairs) {
            if (p.misaligned()) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * The pair One At A Time / Next opens with (killer560, 2026-11-04: "have the first set always include the one that
     * the creepers feet and about straight above it"): the lit pair whose lower lantern is at the creeper's feet and upper
     * lantern about straight above it - both within {@link #OPENING_REACH} blocks sideways of the creeper, least total
     * sideways distance wins. Null with no creeper seen or no such pair; then the nearest pair opens as before.
     */
    static ActivePair openingPair(List<ActivePair> lit, Vec3 feet) {
        if (feet == null) return null;
        ActivePair best = null;
        double bestScore = Double.MAX_VALUE;
        for (ActivePair p : lit) {
            double da = sideways(p.a(), feet);
            double db = sideways(p.b(), feet);
            if (da > OPENING_REACH || db > OPENING_REACH) continue;
            if (da + db < bestScore) {
                bestScore = da + db;
                best = p;
            }
        }
        return best;
    }

    private static double sideways(BlockPos pos, Vec3 feet) {
        double dx = pos.getX() + 0.5 - feet.x;
        double dz = pos.getZ() + 0.5 - feet.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** For the testkit: the pairs drawn now, as {a, b, colorIndex} with misaligned ones colorIndex -1. */
    public static List<Object[]> drawnPairsForTest(Vec3 eye) {
        List<Object[]> out = new ArrayList<>();
        for (ActivePair p : pairsToDraw(activePairs, BeamsSolverConfig.getInstance().getShowMode(), eye)) {
            out.add(new Object[]{p.a(), p.b(), p.misaligned() ? -1 : p.colorIndex()});
        }
        return out;
    }

    /** For the testkit: the color (r, g, b) of palette index {@code i}. */
    public static float[] paletteColor(int i) { return COLORS[i].clone(); }

    /** Logging only: one line when the set of lit / misaligned pairs changes. */
    private static void logScan(List<ActivePair> found) {
        StringBuilder key = new StringBuilder();
        int lit = 0;
        int misaligned = 0;
        for (ActivePair pair : found) {
            if (pair.misaligned()) {
                misaligned++;
            } else {
                lit++;
            }
            key.append(pair.misaligned() ? " RED " : " ").append(pair.a().toShortString()).append("<->")
                    .append(pair.b().toShortString()).append(';');
        }
        String k = key.toString();
        if (k.equals(LOG.scanKey)) return;
        boolean first = LOG.scanKey == null;
        LOG.scanKey = k;
        LOGGER.info("[BeamsSolver] {}: litPairs={} misalignedPairs={} of {} candidates:{}",
                first ? "scan" : "solution changed", lit, misaligned, CANDIDATES.size(), k.isEmpty() ? " none" : k);
    }

    /** A lantern that has been connected already turns into prismarine. */
    private static boolean usedUp(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.is(Blocks.PRISMARINE) || state.is(Blocks.PRISMARINE_BRICKS) || state.is(Blocks.DARK_PRISMARINE);
    }

    /** Its own copy of {@link PuzzleCoords#real}, now delegating: the shared one carries the sim's floor
     *  shift, and four private copies of the same three lines is how three of them came to be missing it. */
    private static BlockPos realPos(int x, int y, int z, int[] clayAndRotation) {
        return PuzzleCoords.real(x, y, z, clayAndRotation);
    }

    private static void onWorldRender(LevelRenderContext context) {
        BeamsSolverConfig cfg = BeamsSolverConfig.getInstance();
        if (!cfg.isEnabled() || activePairs.isEmpty()) return;
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        if (current == null || !"Creeper Beams".equals(current.name)) return;
        Minecraft client = Minecraft.getInstance();
        Vec3 eye = client.player == null ? null : client.player.getEyePosition();
        for (ActivePair pair : pairsToDraw(activePairs, cfg.getShowMode(), eye)) {
            float[] color = pair.misaligned() ? new float[]{1.0f, 0.15f, 0.15f} : COLORS[pair.colorIndex()];
            SolverEspRender.renderWaypoint(com.killer560.hub.puzzlesolvers.SolverEspConfig.Solver.BEAMS, context, new AABB(pair.a()), color[0], color[1], color[2], 2f);
            SolverEspRender.renderWaypoint(com.killer560.hub.puzzlesolvers.SolverEspConfig.Solver.BEAMS, context, new AABB(pair.b()), color[0], color[1], color[2], 2f);
            if (cfg.isShowTracer() || pair.misaligned()) {
                List<Vec3> line = List.of(
                        new Vec3(pair.a().getX() + 0.5, pair.a().getY() + 0.5, pair.a().getZ() + 0.5),
                        new Vec3(pair.b().getX() + 0.5, pair.b().getY() + 0.5, pair.b().getZ() + 0.5));
                SolverEspRender.renderLineStrip(com.killer560.hub.puzzlesolvers.SolverEspConfig.Solver.BEAMS, context, line, color[0], color[1], color[2], 1f, 2f);
            }
        }
    }
}
