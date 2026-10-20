package com.killer560.hub.livemap.predict;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.HeldMap;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.roomsim.RoomLShape;
import com.killer560.hub.roomsim.RoomLibrary;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Predictive Rooms: names a room he has not entered from what he has SEEN of it, and fills in only as much of it as
 * that settles. killer560, 2026-10-15: "if your player could see into a room and see enough of the room to know the
 * orientation of the room or something then it can predtermine it without scanning and show it on their map", "Be
 * careful deciding orientation of rooms if you cannot tell. It should not scan only go off of base knowledge stuff",
 * "I can definitely tell the block on the map right in front of me and the one after that so those would become pirate
 * and the left and right side would be question marks if its not possible to tell".
 *
 * <p><b>What it sees.</b> Every {@link #SAMPLE_EVERY_TICKS} ticks ({@link #SAMPLE_WHILE_SEEING_TICKS} while the last
 * sweep met an unopened room, with extra rays round the ones that did, {@link #FOCUS_MAX_RAYS}) a grid of rays leaves his eyes across his field of
 * view ({@link ClipContext}, the game's own line-of-sight test). The first block each ray hits, if it stands inside a
 * room tile the held map does not show opened, is recorded with its block id. That is the only block read: the blocks
 * a ray passes through on the way are air by definition, and no other block in the world is ever looked at. A block
 * behind a wall, round a corner or under the floor is never seen and never recorded.
 *
 * <p><b>What it knows.</b> The room library that ships in the jar (the sim's 135 captured rooms, {@link RoomLibrary}):
 * every block of every room. A candidate is one room, turned one of four ways, placed so that its footprint covers the
 * tile the block was seen in and no tile the map shows opened, inside the floor. Every seen block inside a candidate's
 * footprint is compared with the block that room has there (block ids only - states turn with the room; the edges of
 * each tile, where doorways differ between floors, are skipped, and so are chests, heads, levers' neighbours and door
 * blocks that change during a run, and a puzzle's moving parts - {@link #VOLATILE}, Water Board's board). A candidate
 * that disagrees with more than {@link #MISMATCH_FLOOR} plus
 * {@link #MISMATCH_RATE} of what it was compared with is ruled out, for good.
 *
 * <p><b>What it says.</b> For a tile with at least {@link #MIN_EVIDENCE} seen blocks: if every candidate still standing
 * is the same room, and each has matched at least {@link #MIN_MATCHES} blocks, that is the room. The tiles EVERY standing candidate covers are certain and are filled in; tiles
 * only some of them cover are "?". So a room whose turn he cannot tell yet shows only the tiles he can be sure of, and
 * the rest follows as soon as something he sees rules the other turns out. Two different rooms still standing: nothing.
 * Doors are never predicted - the map draws only doors the held map shows.
 */
public final class RoomPredictor {

    private static final Logger LOGGER = ModLog.get("killer560smod-livemap");

    static final int SAMPLE_EVERY_TICKS = 4;
    /** The sweep interval while the previous sweep met an unopened room ({@link #FOCUS_MAX_RAYS}). */
    static final int SAMPLE_WHILE_SEEING_TICKS = 2;
    private static boolean lastSweepProductive = false;
    static final int RAYS_X = 24;
    static final int RAYS_Y = 14;
    /**
     * How far a ray reaches. It was 48 until 2026-10-19, and that was why the SECOND room through a doorway never got a
     * name (killer560, 14:46:59: "I can see not just 1 room ahead but 2. They should both be filled"): from inside his
     * room the next one spans about 5-36 blocks ahead and the one after it about 37-68, so a 48-block ray stopped in the
     * air short of nearly all of it and the tile never reached {@link #MIN_EVIDENCE}. 96 covers two whole tiles ahead
     * from anywhere in the tile he stands in (31 + 2 x 32). A ray still records only the first block it meets.
     */
    static final double RAY_RANGE = 96.0;
    /**
     * Seen blocks a tile needs before anything is said about it. It was 120 until 2026-10-18, and that was why Predictive
     * Rooms "doesnt work" on Hypixel (to-do item 22): a player glancing through a doorway as he passes gives a tile 50-120
     * blocks, and by then every room but the right one is long ruled out. 1018-predict-hypixel-walk walked a whole sim floor
     * the way a player does and found 15 tiles closing with exactly one room standing and fewer than 120 seen (54-116), so
     * the map named none of them; 1726 never saw it because it stares through each doorway for 150 ticks. What makes a
     * name safe is that every other room, at every turn and anchor, has been ruled out ({@link #decide}) and that the one
     * left has matched {@link #MIN_MATCHES} blocks, not a count of blocks seen.
     */
    static final int MIN_EVIDENCE = 40;
    /** Blocks every placement still standing must have matched before its room is named. */
    static final int MIN_MATCHES = 30;
    static final int MISMATCH_FLOOR = 3;
    static final double MISMATCH_RATE = 0.04;
    /** Blocks from a tile's edge that are never compared: its wall and doorways. */
    static final int EDGE = 2;
    static final int MAX_OBSERVATIONS = 80_000;
    /** (candidate, seen block) comparisons per client tick. */
    static final int WORK_PER_TICK = 250_000;

    private static final int TILES = 6;
    private static final int TILE_PITCH = 32;
    /** World x (or z) where tile 0's 31 blocks begin. */
    private static final int ORIGIN = LiveMapFeature.START_X - RoomLibrary.TILE / 2;

    /** Prediction for one target tile. Tiles are 11x11 grid indices (even cells). */
    public record Prediction(int tile, String name, String type, int[] certainTiles, int[] uncertainTiles,
                             boolean turnKnown, int candidates, int evidence) {
    }

    // ---- what he has seen: append-only, one entry per block ----
    private static long[] obsPos = new long[4096];
    private static int[] obsBlock = new int[4096];
    private static int[] obsTile = new int[4096];
    private static float[] obsEye = new float[4096 * 3];
    private static float[] obsHit = new float[4096 * 3];
    private static int obsCount = 0;
    private static final Map<Long, Integer> OBS_INDEX = new HashMap<>();
    private static final int[] EVIDENCE = new int[TILES * TILES];

    // ---- block ids ----
    private static final Map<String, Integer> BLOCK_IDS = new HashMap<>();
    private static final List<String> BLOCK_NAMES = new ArrayList<>();
    private static boolean[] ignored = new boolean[64];

    // ---- candidates ----
    private static final class Candidate {
        final RoomLibrary.Room room;
        final int[] norm;        // palette index -> block id, -1 = never compared
        final int rot;
        final int ax;
        final int az;
        final long tiles;        // bit tz * 6 + tx
        final int worldX0;
        final int worldZ0;
        /** {@link #VOLATILE} boxes of this room, or null. */
        final int[][] volatileBoxes;
        /** {@link #volatilePalette} of this room, or null. */
        final java.util.BitSet volatilePalette;
        int matches;
        int mismatches;
        int cursor;
        boolean dead;

        Candidate(RoomLibrary.Room room, int[] norm, int rot, int ax, int az, long tiles) {
            this.room = room;
            this.norm = norm;
            this.rot = rot;
            this.ax = ax;
            this.az = az;
            this.tiles = tiles;
            this.worldX0 = ORIGIN + ax * TILE_PITCH - room.margin;
            this.worldZ0 = ORIGIN + az * TILE_PITCH - room.margin;
            this.volatileBoxes = volatileBoxes(room);
            this.volatilePalette = volatilePalette(room);
        }

        String key() {
            return room.name + "@" + rot + "@" + ax + "," + az;
        }
    }

    private static final Map<String, Candidate> ALL = new HashMap<>();
    @SuppressWarnings("unchecked")
    private static final List<Candidate>[] BY_TILE = new List[TILES * TILES];
    private static final Map<String, int[]> NORM_CACHE = new HashMap<>();
    private static final Map<Integer, Prediction> PREDICTIONS = new HashMap<>();
    /** Bumped whenever {@link #PREDICTIONS} is replaced or cleared (the Dungeon Map's draw cache keys on it). */
    private static int predictionsVersion = 0;

    private static int generation = Integer.MIN_VALUE;
    private static Object level = null;
    private static int ticks = 0;
    private static int sampleSeed = 0;
    private static String status = "idle";
    private static boolean dirty = false;

    private RoomPredictor() {
    }

    /** Logging only: tiles whose "every placement ruled out" was logged this floor, the full store, and the gate. */
    private static final boolean[] LOGGED_RULED_OUT = new boolean[TILES * TILES];
    private static boolean loggedFull = false;
    private static String loggedGate;

    private static void logGate(String gate) {
        if (!gate.equals(loggedGate)) {
            loggedGate = gate;
            LOGGER.info("[Predict] predictive rooms: {}", gate);
        }
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("RoomPredictor", RoomPredictor::tick));
    }

    // ------------------------------------------------------------------------------------------------- ticking

    private static void tick(Minecraft client) {
        int gen = LiveMapFeature.resetGeneration();
        if (gen != generation || client.level != level) {
            generation = gen;
            level = client.level;
            reset();
        }
        if (!PredictiveRoomsConfig.getInstance().isEnabled() || client.player == null || client.level == null) {
            logGate(!PredictiveRoomsConfig.getInstance().isEnabled() ? "off" : "no player or level");
            return;
        }
        boolean sim = com.killer560.hub.roomsim.SimState.isActive();
        if (!(sim || com.killer560.hub.secrets.DungeonState.isInDungeon()) || LiveMapFeature.isInBoss()) {
            logGate(LiveMapFeature.isInBoss() ? "in boss" : "not in a dungeon");
            setStatus("not in a dungeon clear");
            return;
        }
        if (!RoomLibrary.isReady()) {
            logGate("waiting for the room library");
            RoomLibrary.loadAsync();
            setStatus("waiting for the room library");
            return;
        }
        logGate(!RoomDatabase.isReady() ? "running (room database not loaded yet)" : sim ? "running (sim)" : "running");
        if (!RoomDatabase.isReady()) {
            RoomDatabase.ensureLoading();
        }
        // Every other tick while the last sweep found a room he can see into, so a glance gathers its blocks twice as
        // fast; every fourth otherwise, which is nearly always (2026-10-18, killer560 wants a name near instantly).
        if (++ticks >= (lastSweepProductive ? SAMPLE_WHILE_SEEING_TICKS : SAMPLE_EVERY_TICKS)) {
            ticks = 0;
            sample(client);
        }
        evaluate();
        if (dirty) {
            dirty = false;
            decide();
        }
    }

    /** Forgets everything seen: a new floor or a new world. */
    public static void reset() {
        if (obsCount > 0 || !PREDICTIONS.isEmpty()) {
            LOGGER.info("[Predict] reset (new floor or world): forgetting {} block(s) seen, {} placement(s), {} prediction(s)",
                    obsCount, ALL.size(), PREDICTIONS.size());
        }
        Arrays.fill(LOGGED_RULED_OUT, false);
        loggedFull = false;
        obsCount = 0;
        OBS_INDEX.clear();
        Arrays.fill(EVIDENCE, 0);
        Arrays.fill(REPORTED, false);
        ALL.clear();
        Arrays.fill(BY_TILE, null);
        PREDICTIONS.clear();
        predictionsVersion++;
        dirty = false;
    }

    // ------------------------------------------------------------------------------------------------- seeing

    /** One sweep of rays across his view. Only the first block each ray hits is read. */
    static void sample(Minecraft client) {
        lastSweepProductive = false;
        Vec3 eye = client.player.getEyePosition();
        Vec3 f = client.player.getViewVector(1.0f);
        Vec3 right = f.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1.0e-6) {
            right = new Vec3(1, 0, 0);
        }
        right = right.normalize();
        Vec3 up = right.cross(f).normalize();
        double vfov = Math.toRadians(Math.max(30, Math.min(110, client.options.fov().get())));
        double aspect = client.getWindow() == null || client.getWindow().getHeight() == 0 ? 16.0 / 9.0
                : (double) client.getWindow().getWidth() / client.getWindow().getHeight();
        double tanV = Math.tan(vfov / 2);
        double tanH = tanV * aspect;
        // A different sub-grid offset each sweep, so successive sweeps land between the last one's rays.
        sampleSeed = (sampleSeed + 1) % 16;
        double jx = ((sampleSeed % 4) + 0.5) / 4.0;
        double jy = ((sampleSeed / 4) + 0.5) / 4.0;
        int[] floor = HeldMap.floorTiles();
        if (obsCount >= MAX_OBSERVATIONS) {
            if (!loggedFull) {
                loggedFull = true;
                LOGGER.info("[Predict] observation store full ({} blocks): no more sweeps this floor", obsCount);
            }
            return; // record() keeps nothing more, whatever a ray meets
        }
        // Which tiles a hit may be recorded in, for this sweep (he does not move during it): isTarget, once per tile.
        boolean anyTarget = false;
        for (int tz = 0; tz < TILES; tz++) {
            for (int tx = 0; tx < TILES; tx++) {
                boolean t = tx < floor[0] && tz < floor[1] && isTarget(client, tz * 2 * DungeonLayout.GRID + tx * 2);
                if (!t && BY_TILE[tz * TILES + tx] != null && !REPORTED[tz * TILES + tx]) {
                    reportClosed(tz * TILES + tx);
                }
                SWEEP_TARGET[tz * TILES + tx] = t;
                anyTarget |= t;
            }
        }
        if (!anyTarget) {
            return; // every hit would be thrown away (all opened, or only the tile he stands in)
        }
        int hits = 0;
        for (int i = 0; i < RAYS_X; i++) {
            for (int j = 0; j < RAYS_Y; j++) {
                double u = ((i + jx) / RAYS_X) * 2 - 1;
                double v = ((j + jy) / RAYS_Y) * 2 - 1;
                int t = castRay(client, eye, f, right, up, tanH, tanV, u, v, floor);
                if (t >= 0) {
                    HIT_U[hits] = u;
                    HIT_V[hits] = v;
                    HIT_TILE[hits] = t;
                    hits++;
                }
            }
        }
        lastSweepProductive = hits > 0;
        // Which of those rays get the extra rays round them: the ones into the tile that has the least to go on, first.
        // Until 2026-10-19 it was the first 42 in grid order, so with the room ahead filling the doorway every extra ray
        // went to it (already named, often) and the room beyond it, a few rays deep in the same slot, got none.
        int productive = Math.min(hits, FOCUS_SEEDS.length / 2);
        Integer[] order = new Integer[hits];
        for (int k = 0; k < hits; k++) {
            order[k] = k;
        }
        Arrays.sort(order, (a, b) -> Integer.compare(focusNeed(HIT_TILE[a]), focusNeed(HIT_TILE[b])));
        for (int k = 0; k < productive; k++) {
            FOCUS_SEEDS[k * 2] = HIT_U[order[k]];
            FOCUS_SEEDS[k * 2 + 1] = HIT_V[order[k]];
        }
        // Where the grid found a room he can see into - in a corridor, through a doorway that is a narrow slot in his
        // view, a handful of its 336 rays - the gaps round each of those rays are filled in, eight more rays a third of
        // the grid's spacing apart. Until 2026-10-18 the slot got only its handful a sweep, and the 40 blocks a name
        // needs took seconds to gather (killer560: about 20 s after he stopped in a corridor, and he wants it near
        // instant). The extra rays go only where the room is already known to show, so a sweep that sees no unopened
        // room casts none, and at most {@link #FOCUS_MAX_RAYS} more however much of one it sees.
        double du = 2.0 / RAYS_X / 3.0;
        double dv = 2.0 / RAYS_Y / 3.0;
        int extra = 0;
        for (int k = 0; k < productive && extra + 8 <= FOCUS_MAX_RAYS; k++) {
            double u0 = FOCUS_SEEDS[k * 2];
            double v0 = FOCUS_SEEDS[k * 2 + 1];
            for (int a = -1; a <= 1; a++) {
                for (int b = -1; b <= 1; b++) {
                    if (a != 0 || b != 0) {
                        castRay(client, eye, f, right, up, tanH, tanV, u0 + a * du, v0 + b * dv, floor);
                        extra++;
                    }
                }
            }
        }
    }

    /** Extra rays a sweep may cast round the grid rays that met an unopened room ({@link #sample}). */
    static final int FOCUS_MAX_RAYS = 336;
    private static final double[] FOCUS_SEEDS = new double[2 * (FOCUS_MAX_RAYS / 8)];
    /** This sweep's grid rays that met a tile being looked into: screen position and tile. */
    private static final double[] HIT_U = new double[RAYS_X * RAYS_Y];
    private static final double[] HIT_V = new double[RAYS_X * RAYS_Y];
    private static final int[] HIT_TILE = new int[RAYS_X * RAYS_Y];

    /**
     * How little a tile has to go on, lowest first: a tile with no name yet before one that has one, and among those the
     * one with the fewest blocks seen. Orders which rays get the extra rays ({@link #sample}).
     */
    private static int focusNeed(int t) {
        boolean named = PREDICTIONS.containsKey((t / TILES) * 2 * DungeonLayout.GRID + (t % TILES) * 2);
        return (named ? MAX_OBSERVATIONS : 0) + EVIDENCE[t];
    }

    /**
     * Casts one ray at screen position {@code u, v} (each -1..1 across the view) and records what it meets. Returns the
     * tile (tz * TILES + tx) when it met a block inside a tile being looked into (seen before or not), else -1.
     */
    private static int castRay(Minecraft client, Vec3 eye, Vec3 f, Vec3 right, Vec3 up, double tanH, double tanV,
                                   double u, double v, int[] floor) {
        // The same arithmetic as f.add(right.scale(u * tanH)).add(up.scale(v * tanV)).normalize() and
        // eye.add(dir.scale(RAY_RANGE)), operation for operation, without the five Vec3 between them.
        double su = u * tanH;
        double sv = v * tanV;
        double dx = f.x + right.x * su + up.x * sv;
        double dy = f.y + right.y * su + up.y * sv;
        double dz = f.z + right.z * su + up.z * sv;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-5F) {
            dx = 0;
            dy = 0;
            dz = 0;
        } else {
            dx /= len;
            dy /= len;
            dz /= len;
        }
        double ex = eye.x + dx * RAY_RANGE;
        double ey = eye.y + dy * RAY_RANGE;
        double ez = eye.z + dz * RAY_RANGE;
        // A ray whose whole reach (plus the block a hit can round to) lies over tiles none of which is a
        // target can only meet blocks record() throws away: not cast. Exact, never skips a ray that could count.
        if (!reachesTarget(Math.min(eye.x, ex), Math.min(eye.z, ez), Math.max(eye.x, ex), Math.max(eye.z, ez))) {
            return -1;
        }
        HitResult hit = client.level.clip(new ClipContext(eye, new Vec3(ex, ey, ez), ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE, client.player));
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
            return -1;
        }
        return record(client, bhr.getBlockPos(), eye, bhr.getLocation(), floor);
    }

    /** Tiles whose outcome {@link #reportClosed} has logged, once a floor. */
    private static final boolean[] REPORTED = new boolean[TILES * TILES];

    /**
     * One line when a tile he looked into stops being looked into (he walked in, or the map opened it): what Predictive
     * Rooms made of it and, when nothing, why - too little seen, comparisons still running, every placement ruled out,
     * too few matches, or more than one room still fitting. The line an in-game check reads (to-do item 22, 2026-10-18).
     */
    private static void reportClosed(int t) {
        REPORTED[t] = true;
        String where = (t % TILES) + "," + (t / TILES);
        Prediction p = PREDICTIONS.get((t / TILES) * 2 * DungeonLayout.GRID + (t % TILES) * 2);
        if (p != null) {
            LOGGER.info("[Predict] tile {} closed: named {} from {} block(s) seen", where, p.name(), EVIDENCE[t]);
            return;
        }
        int alive = 0;
        int behind = 0;
        int fewestMatches = Integer.MAX_VALUE;
        Set<String> names = new LinkedHashSet<>();
        for (Candidate c : BY_TILE[t]) {
            if (c.dead) {
                continue;
            }
            alive++;
            behind += c.cursor < obsCount ? 1 : 0;
            fewestMatches = Math.min(fewestMatches, c.matches);
            if (names.size() < 4) {
                names.add(c.room.name);
            }
        }
        String why = EVIDENCE[t] < MIN_EVIDENCE ? "too little seen"
                : behind > 0 ? "comparisons still running"
                : alive == 0 ? "every placement ruled out"
                : names.size() > 1 ? "more than one room fits"
                : "too few matches";
        LOGGER.info("[Predict] tile {} closed: no answer ({}) - {} block(s) seen, {} of {} placement(s) standing{}", where,
                why, EVIDENCE[t], alive, BY_TILE[t].size(),
                names.isEmpty() ? "" : ", rooms " + names + ", fewest matches " + fewestMatches);
    }

    /** {@link #isTarget} per tile (tz * TILES + tx) for the sweep in progress; false off the floor. */
    private static final boolean[] SWEEP_TARGET = new boolean[TILES * TILES];

    /** Whether any tile under the x/z box (widened a block each way) is a target this sweep. */
    private static boolean reachesTarget(double minX, double minZ, double maxX, double maxZ) {
        int tx0 = Math.max(0, Math.floorDiv((int) Math.floor(minX) - 1 - ORIGIN, TILE_PITCH));
        int tz0 = Math.max(0, Math.floorDiv((int) Math.floor(minZ) - 1 - ORIGIN, TILE_PITCH));
        int tx1 = Math.min(TILES - 1, Math.floorDiv((int) Math.floor(maxX) + 1 - ORIGIN, TILE_PITCH));
        int tz1 = Math.min(TILES - 1, Math.floorDiv((int) Math.floor(maxZ) + 1 - ORIGIN, TILE_PITCH));
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                if (SWEEP_TARGET[tz * TILES + tx]) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Records the block a ray met; returns its tile when it lies inside a tile being looked into (seen before or not),
     *  else -1. */
    private static int record(Minecraft client, BlockPos pos, Vec3 eye, Vec3 hitAt, int[] floor) {
        int tx = Math.floorDiv(pos.getX() - ORIGIN, TILE_PITCH);
        int tz = Math.floorDiv(pos.getZ() - ORIGIN, TILE_PITCH);
        if (tx < 0 || tz < 0 || tx >= floor[0] || tz >= floor[1]) {
            return -1;
        }
        int lx = pos.getX() - ORIGIN - tx * TILE_PITCH;
        int lz = pos.getZ() - ORIGIN - tz * TILE_PITCH;
        if (lx < EDGE || lz < EDGE || lx > RoomLibrary.TILE - 1 - EDGE || lz > RoomLibrary.TILE - 1 - EDGE) {
            return -1;
        }
        if (!SWEEP_TARGET[tz * TILES + tx]) {
            return -1; // isTarget, worked out once for the sweep
        }
        long key = pos.asLong();
        if (OBS_INDEX.containsKey(key) || obsCount >= MAX_OBSERVATIONS) {
            return tz * TILES + tx;
        }
        String id = BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(pos).getBlock()).toString();
        int block = blockId(id);
        if (obsCount == obsPos.length) {
            int n = obsPos.length * 2;
            obsPos = Arrays.copyOf(obsPos, n);
            obsBlock = Arrays.copyOf(obsBlock, n);
            obsTile = Arrays.copyOf(obsTile, n);
            obsEye = Arrays.copyOf(obsEye, n * 3);
            obsHit = Arrays.copyOf(obsHit, n * 3);
        }
        int t = tz * TILES + tx;
        obsPos[obsCount] = key;
        obsBlock[obsCount] = block;
        obsTile[obsCount] = t;
        obsEye[obsCount * 3] = (float) eye.x;
        obsEye[obsCount * 3 + 1] = (float) eye.y;
        obsEye[obsCount * 3 + 2] = (float) eye.z;
        obsHit[obsCount * 3] = (float) hitAt.x;
        obsHit[obsCount * 3 + 1] = (float) hitAt.y;
        obsHit[obsCount * 3 + 2] = (float) hitAt.z;
        OBS_INDEX.put(key, obsCount);
        obsCount++;
        if (!ignored(block)) {
            EVIDENCE[t]++;
        }
        if (BY_TILE[t] == null) {
            buildCandidates(client, tx, tz, floor);
        }
        dirty = true;
        return t;
    }

    /** A tile is worth looking at when the held map does not show it opened and he is not standing in it. */
    private static boolean isTarget(Minecraft client, int gridIdx) {
        if (HeldMap.available() && HeldMap.roomOpened(gridIdx)) {
            return false;
        }
        // Inside the tile's own 31 blocks; standing in the doorway gap between two tiles is in neither.
        BlockPos c = DungeonLayout.cellCenter(gridIdx);
        return !(Math.abs(client.player.getX() - (c.getX() + 0.5)) < 15.5 && Math.abs(client.player.getZ() - (c.getZ() + 0.5)) < 15.5);
    }

    // ------------------------------------------------------------------------------------------------- candidates

    private static void buildCandidates(Minecraft client, int tx, int tz, int[] floor) {
        List<Candidate> list = new ArrayList<>();
        BY_TILE[tz * TILES + tx] = list;
        for (String name : RoomLibrary.names()) {
            RoomLibrary.Room room = RoomLibrary.get(name);
            if (room == null || !room.usable()) {
                continue;
            }
            int tilesX = Math.max(1, (room.sizeX - 1) / (RoomLibrary.TILE + 1));
            int tilesZ = Math.max(1, (room.sizeZ - 1) / (RoomLibrary.TILE + 1));
            RoomEntry entry = RoomDatabase.lookupByName(room.name);
            boolean isL = entry != null && entry.shape != null && "L".equalsIgnoreCase(entry.shape.trim());
            if (tilesX == 2 && tilesZ == 2 && entry == null) {
                continue; // an L or a 2x2 cannot be told apart before the room database is in
            }
            if (VOLATILE.containsKey(room.name) && !RoomDatabase.isReady()) {
                continue; // its moving parts are placed through the capture's turn, which needs the database
            }
            int[] norm = NORM_CACHE.computeIfAbsent(room.name, n -> normalise(room));
            for (int rot = 0; rot < 360; rot += 90) {
                int w = rot % 180 == 0 ? tilesX : tilesZ;
                int h = rot % 180 == 0 ? tilesZ : tilesX;
                int[] missing = isL ? RoomLShape.missingTilePasted(room, rot) : null;
                if (isL && missing == null) {
                    continue;
                }
                for (int ax = tx - w + 1; ax <= tx; ax++) {
                    for (int az = tz - h + 1; az <= tz; az++) {
                        if (ax < 0 || az < 0 || ax + w > floor[0] || az + h > floor[1]) {
                            continue;
                        }
                        long mask = 0;
                        boolean ok = true;
                        for (int i = 0; i < w && ok; i++) {
                            for (int k = 0; k < h; k++) {
                                if (missing != null && missing[0] == i && missing[1] == k) {
                                    continue;
                                }
                                int gx = ax + i;
                                int gz = az + k;
                                if (HeldMap.available() && HeldMap.roomOpened(gz * 2 * DungeonLayout.GRID + gx * 2)) {
                                    ok = false;
                                    break;
                                }
                                mask |= 1L << (gz * TILES + gx);
                            }
                        }
                        if (!ok || (mask & (1L << (tz * TILES + tx))) == 0) {
                            continue;
                        }
                        Candidate c = new Candidate(room, norm, rot, ax, az, mask);
                        Candidate existing = ALL.putIfAbsent(c.key(), c);
                        list.add(existing != null ? existing : c);
                    }
                }
            }
        }
        LOGGER.info("[Predict] tile {},{}: {} candidate placement(s)", tx, tz, list.size());
    }

    /** Palette index to block id, block-state properties dropped and every kind of air folded into one. */
    private static int[] normalise(RoomLibrary.Room room) {
        int[] out = new int[room.palette.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = blockId(room.palette.get(i));
        }
        return out;
    }

    /** Runs every standing candidate over what has been seen since it last looked, within a budget. */
    static void evaluate() {
        int work = 0;
        int off = DungeonLayout.simYOffset();
        for (Candidate c : ALL.values()) {
            if (c.dead || c.cursor >= obsCount) {
                continue;
            }
            int end = Math.min(obsCount, c.cursor + Math.max(1, WORK_PER_TICK - work));
            for (int i = c.cursor; i < end; i++) {
                if ((c.tiles & (1L << obsTile[i])) == 0) {
                    continue;
                }
                int observed = obsBlock[i];
                if (ignored(observed)) {
                    continue;
                }
                // The packed position read in place: a BlockPos per observation per candidate was most of this
                // tick's allocation (95-perf-features ALL ON, 2026-10-15).
                long packed = obsPos[i];
                int expected = expectedAt(c, BlockPos.getX(packed), BlockPos.getY(packed) - off, BlockPos.getZ(packed));
                if (expected == VOLATILE_EXPECTED) {
                    // A puzzle's moving part: its arrangement differs run to run, the blocks it is made of do not. One
                    // of those blocks agrees with the room; anything else says nothing (never a mismatch).
                    if (c.volatilePalette != null && c.volatilePalette.get(observed)) {
                        c.matches++;
                    }
                    continue;
                }
                if (expected < 0 || ignored(expected)) {
                    continue;
                }
                if (expected == observed) {
                    c.matches++;
                } else {
                    c.mismatches++;
                    if (c.mismatches > MISMATCH_FLOOR + MISMATCH_RATE * (c.matches + c.mismatches)) {
                        c.dead = true;
                        break;
                    }
                }
            }
            work += end - c.cursor;
            c.cursor = c.dead ? obsCount : end;
            dirty = true;
            if (work >= WORK_PER_TICK) {
                return;
            }
        }
    }

    /**
     * Room-relative boxes {x0, y0, z0, x1, y1, z1} (inclusive; the room database's own frame, which the puzzle solvers
     * use) of rooms whose blocks there differ from run to run, and during a run, for reasons that say nothing about which
     * room it is. A block seen inside one is never a mismatch; it is a match when the capture's box holds that block
     * somewhere ({@link #volatilePalette}).
     *
     * <p>Water Board (killer560, 2026-10-18: "it didnt work on water board"; his Mod Only Test log 19:25:02, "every
     * placement ruled out - 330 block(s) seen, 0 of 412 placement(s) standing"). The front glass is see-through to a ray
     * (glass has no visual shape), so most of what a look into the room meets is the board behind it, and that board is
     * different on every run: Hypixel has four layouts (the identifier blocks at relative (14|16, 77|78, 27) say which),
     * and twenty-seven sticky pistons move its ore gates as the levers are flicked. Every capture of the room there is
     * holds the same frame of board 0, every colour retracted. Under the walkway glass, three of the five colour columns
     * are pushed out on every run. Decoded off the bundled {@code Water_Board.json} (capture x = relative z + 1, capture z
     * = 31 - relative x, its own turn 270): the front glass is relative z 25; the maze plane, the ore wall, the pistons and
     * the redstone behind them are relative z 26..30 from y 59 up; the colour columns are relative z 15..19, x 12..18,
     * y 54..57 (the wool and the pistons beside it). The room's shell, floors, stairs, walkway and lever plinths are
     * compared as for any room, and they are what names it.
     *
     * <p>Relative, not capture, coordinates on purpose: his Mod Only Test instance holds its own capture of the room,
     * turned a quarter from the bundled one, and a usable disk capture wins ({@code RoomLibrary}). Each loaded capture's
     * boxes are worked out from its own turn ({@link #volatileBoxes}).
     */
    private static final Map<String, int[][]> VOLATILE = Map.of(
            "Water Board", new int[][]{
                    {-1, 59, 26, 31, 100, 31},
                    {12, 54, 15, 18, 57, 19}});

    /** {@link #VOLATILE} turned into each loaded capture's own coordinates, by capture (a library reload replaces them). */
    private static final Map<RoomLibrary.Room, int[][]> VOLATILE_CAPTURE = new java.util.IdentityHashMap<>();
    /** Every block id (not air, not {@link #changesDuringARun}) a capture has inside its {@link #VOLATILE} boxes. */
    private static final Map<RoomLibrary.Room, java.util.BitSet> VOLATILE_PALETTE = new java.util.IdentityHashMap<>();
    /** What {@link #expectedAt} returns inside a {@link #VOLATILE} box. */
    private static final int VOLATILE_EXPECTED = -2;

    /**
     * The blocks {@code room}'s moving parts are made of, from its capture: a seen block inside a {@link #VOLATILE} box
     * that is one of these counts as a match (the board's layout and gates move, its blocks do not - a look down a
     * corridor at Water Board meets the board and little else, and with the board left out entirely a 10-block-back
     * glance gathered 22 comparable blocks, under {@link #MIN_MATCHES}); any other block there says nothing. Never a
     * mismatch, so another layout can never rule the room out. Null when the room has no boxes.
     */
    static java.util.BitSet volatilePalette(RoomLibrary.Room room) {
        int[][] boxes = volatileBoxes(room);
        if (boxes == null) {
            return null;
        }
        return VOLATILE_PALETTE.computeIfAbsent(room, r -> {
            int[] norm = NORM_CACHE.computeIfAbsent(r.name, n -> normalise(r));
            java.util.BitSet out = new java.util.BitSet();
            for (int[] b : boxes) {
                for (int y = b[1]; y <= b[4]; y++) {
                    for (int z = b[2]; z <= b[5]; z++) {
                        for (int x = b[0]; x <= b[3]; x++) {
                            short idx = r.at(x, y, z);
                            if (idx >= 0 && !"minecraft:air".equals(BLOCK_NAMES.get(norm[idx])) && !ignored(norm[idx])) {
                                out.set(norm[idx]);
                            }
                        }
                    }
                }
            }
            return out;
        });
    }

    /**
     * {@code room}'s {@link #VOLATILE} boxes in its capture's coordinates, or null when it has none. Pasted at rotation 0
     * the capture's block (cx, cz) stands at (ORIGIN - margin + cx, ORIGIN - margin + cz) of tile (0, 0), and the room's
     * relative frame there is the one {@code RoomCaptureRotation} reads off the capture (the sim places every secret and
     * puzzle part through the same pair); each box's corners go through {@code RoomDatabase.toRealCoord} into it. Needs
     * the room database (the capture's turn is voted on with its secrets): callers skip the room until it is ready.
     */
    static int[][] volatileBoxes(RoomLibrary.Room room) {
        int[][] rel = VOLATILE.get(room.name);
        if (rel == null) {
            return null;
        }
        return VOLATILE_CAPTURE.computeIfAbsent(room, r -> {
            int turn = com.killer560.hub.roomsim.RoomCaptureRotation.of(r);
            int[] clay = com.killer560.hub.roomsim.SimSecrets.clayCorner(r, 0, 0, 0, turn);
            int x0 = ORIGIN - r.margin;
            int z0 = ORIGIN - r.margin;
            int[][] out = new int[rel.length][];
            for (int i = 0; i < rel.length; i++) {
                int[] b = rel[i];
                BlockPos a = RoomDatabase.toRealCoord(relPos(b[0], b[1], b[2]), clay[0], clay[1], turn);
                BlockPos c = RoomDatabase.toRealCoord(relPos(b[3], b[4], b[5]), clay[0], clay[1], turn);
                out[i] = new int[]{Math.min(a.getX(), c.getX()) - x0, b[1], Math.min(a.getZ(), c.getZ()) - z0,
                        Math.max(a.getX(), c.getX()) - x0, b[4], Math.max(a.getZ(), c.getZ()) - z0};
            }
            LOGGER.info("[Predict] {}: capture turned {}, volatile capture box(es) {}", r.name, turn,
                    java.util.Arrays.deepToString(out));
            return out;
        });
    }

    private static RoomEntry.Pos relPos(int x, int y, int z) {
        RoomEntry.Pos p = new RoomEntry.Pos();
        p.x = x;
        p.y = y;
        p.z = z;
        return p;
    }

    /** Whether capture position x, cy, z lies in one of {@code boxes} (a room's {@link #VOLATILE} entry, or null). */
    static boolean volatileAt(int[][] boxes, int x, int cy, int z) {
        if (boxes == null) {
            return false;
        }
        for (int[] b : boxes) {
            if (x >= b[0] && x <= b[3] && cy >= b[1] && cy <= b[4] && z >= b[2] && z <= b[5]) {
                return true;
            }
        }
        return false;
    }

    /** Capture x of the block {@code lx, lz} from candidate {@code c}'s paste corner, turned with it. */
    private static int captureX(Candidate c, int lx, int lz) {
        return switch (c.rot) {
            case 90 -> lz;
            case 180 -> c.room.sizeX - 1 - lx;
            case 270 -> c.room.sizeX - 1 - lz;
            default -> lx;
        };
    }

    /** Capture z of the block {@code lx, lz} from candidate {@code c}'s paste corner, turned with it. */
    private static int captureZ(Candidate c, int lx, int lz) {
        return switch (c.rot) {
            case 90 -> c.room.sizeZ - 1 - lx;
            case 180 -> c.room.sizeZ - 1 - lz;
            case 270 -> lx;
            default -> lz;
        };
    }

    /** The block id candidate {@code c} has at world x/z and CAPTURE y, or -1 where its capture has nothing. */
    private static int expectedAt(Candidate c, int wx, int cy, int wz) {
        RoomLibrary.Room r = c.room;
        int x = captureX(c, wx - c.worldX0, wz - c.worldZ0);
        int z = captureZ(c, wx - c.worldX0, wz - c.worldZ0);
        if (volatileAt(c.volatileBoxes, x, cy, z)) {
            return VOLATILE_EXPECTED; // differs between runs of this room: judged by its palette alone (evaluate)
        }
        short idx = r.at(x, cy, z);
        return idx < 0 ? -1 : c.norm[idx];
    }

    // ------------------------------------------------------------------------------------------------- deciding

    private static void decide() {
        Map<Integer, Prediction> next = new HashMap<>();
        for (int t = 0; t < TILES * TILES; t++) {
            List<Candidate> list = BY_TILE[t];
            if (list == null || EVIDENCE[t] < MIN_EVIDENCE) {
                continue;
            }
            List<Candidate> standing = new ArrayList<>();
            for (Candidate c : list) {
                if (!c.dead && c.cursor >= obsCount && !touchesOpened(c)) {
                    standing.add(c);
                }
            }
            boolean pending = false;
            for (Candidate c : list) {
                if (!c.dead && c.cursor < obsCount) {
                    pending = true;
                    break;
                }
            }
            if (!pending && standing.isEmpty() && !LOGGED_RULED_OUT[t]) {
                LOGGED_RULED_OUT[t] = true;
                LOGGER.info("[Predict] tile {},{}: every one of {} placement(s) ruled out or touching an opened room ({} block(s) seen)",
                        t % TILES, t / TILES, list.size(), EVIDENCE[t]);
            }
            if (pending || standing.isEmpty()) {
                continue;
            }
            Set<String> names = new LinkedHashSet<>();
            int fewestMatches = Integer.MAX_VALUE;
            for (Candidate c : standing) {
                names.add(c.room.name);
                fewestMatches = Math.min(fewestMatches, c.matches);
            }
            if (names.size() != 1 || fewestMatches < MIN_MATCHES) {
                continue;
            }
            long all = -1L;
            long any = 0L;
            // One placement (turn and anchor) left among them; it was a set of "rot@ax,az" strings, one per candidate.
            boolean oneTurn = true;
            Candidate first = standing.get(0);
            for (Candidate c : standing) {
                all &= c.tiles;
                any |= c.tiles;
                oneTurn &= c.rot == first.rot && c.ax == first.ax && c.az == first.az;
            }
            String name = names.iterator().next();
            RoomEntry entry = RoomDatabase.lookupByName(name);
            String type = entry != null && entry.type != null ? entry.type.toUpperCase(Locale.ROOT) : "NORMAL";
            int gridIdx = (t / TILES) * 2 * DungeonLayout.GRID + (t % TILES) * 2;
            next.put(gridIdx, new Prediction(gridIdx, name, type, tilesOf(all), tilesOf(any & ~all),
                    oneTurn, standing.size(), EVIDENCE[t]));
        }
        for (Map.Entry<Integer, Prediction> e : next.entrySet()) {
            Prediction old = PREDICTIONS.get(e.getKey());
            Prediction p = e.getValue();
            if (old == null || !old.name().equals(p.name()) || old.certainTiles().length != p.certainTiles().length) {
                LOGGER.info("[Predict] tile {}: {} ({} certain tile(s), {} unsure, {} placement(s) left, {} block(s) seen)",
                        e.getKey(), p.name(), p.certainTiles().length, p.uncertainTiles().length, p.candidates(),
                        p.evidence());
            }
        }
        for (Map.Entry<Integer, Prediction> e : PREDICTIONS.entrySet()) {
            if (!next.containsKey(e.getKey())) {
                LOGGER.info("[Predict] tile {}: prediction {} withdrawn (no longer a single room with enough matches)",
                        e.getKey(), e.getValue().name());
            }
        }
        if (!sameDrawn(PREDICTIONS, next)) {
            predictionsVersion++;   // only when the map would draw something else: it repaints on every bump
        }
        PREDICTIONS.clear();
        PREDICTIONS.putAll(next);
        setStatus(obsCount + " block(s) seen, " + PREDICTIONS.size() + " prediction(s)");
    }

    /** Whether two prediction sets draw the same: per tile, the same name, type, certain and unsure tiles. */
    private static boolean sameDrawn(Map<Integer, Prediction> a, Map<Integer, Prediction> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<Integer, Prediction> e : a.entrySet()) {
            Prediction x = e.getValue();
            Prediction y = b.get(e.getKey());
            if (y == null || !x.name().equals(y.name()) || !x.type().equals(y.type())
                    || !Arrays.equals(x.certainTiles(), y.certainTiles())
                    || !Arrays.equals(x.uncertainTiles(), y.uncertainTiles())) {
                return false;
            }
        }
        return true;
    }

    private static boolean touchesOpened(Candidate c) {
        if (!HeldMap.available()) {
            return false;
        }
        for (int t = 0; t < TILES * TILES; t++) {
            if ((c.tiles & (1L << t)) != 0 && HeldMap.roomOpened((t / TILES) * 2 * DungeonLayout.GRID + (t % TILES) * 2)) {
                return true;
            }
        }
        return false;
    }

    private static int[] tilesOf(long mask) {
        List<Integer> out = new ArrayList<>();
        for (int t = 0; t < TILES * TILES; t++) {
            if ((mask & (1L << t)) != 0) {
                out.add((t / TILES) * 2 * DungeonLayout.GRID + (t % TILES) * 2);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    // ------------------------------------------------------------------------------------------------- blocks

    private static int blockId(String state) {
        String id = state;
        int bracket = id.indexOf('[');
        if (bracket >= 0) {
            id = id.substring(0, bracket);
        }
        if (id.equals("minecraft:cave_air") || id.equals("minecraft:void_air")) {
            id = "minecraft:air";
        }
        Integer known = BLOCK_IDS.get(id);
        if (known != null) {
            return known;
        }
        int n = BLOCK_NAMES.size();
        BLOCK_IDS.put(id, n);
        BLOCK_NAMES.add(id);
        if (n >= ignored.length) {
            ignored = Arrays.copyOf(ignored, ignored.length * 2);
        }
        ignored[n] = changesDuringARun(id);
        return n;
    }

    private static boolean ignored(int block) {
        return block >= 0 && block < ignored.length && ignored[block];
    }

    /**
     * Blocks that differ between this run and the capture for reasons that say nothing about which room it is:
     * secrets (chests, heads, the redstone key's block), doors and gates, crypts' and Superboom walls' stand-ins,
     * fluids and fire, planks (the room database's own core hash ignores them too), and barriers.
     */
    private static boolean changesDuringARun(String id) {
        return id.endsWith("chest") || id.endsWith("_head") || id.endsWith("_skull") || id.endsWith("_planks")
                || id.equals("minecraft:coal_block") || id.equals("minecraft:red_terracotta")
                || id.startsWith("minecraft:infested_") || id.equals("minecraft:barrier")
                || id.equals("minecraft:redstone_block") || id.equals("minecraft:water") || id.equals("minecraft:lava")
                || id.endsWith("fire") || id.equals("minecraft:light") || id.equals("minecraft:structure_void")
                || id.equals("minecraft:piston_head") || id.equals("minecraft:moving_piston");
    }

    // ------------------------------------------------------------------------------------------------- reading

    /** Every current prediction, by the tile it was made for. */
    public static List<Prediction> predictions() {
        return new ArrayList<>(PREDICTIONS.values());
    }

    public static int predictionsVersion() {
        return predictionsVersion;
    }

    public static Prediction predictionFor(int gridIdx) {
        return PREDICTIONS.get(gridIdx);
    }

    public static String status() {
        return status;
    }

    private static void setStatus(String s) {
        if (!s.equals(status)) {
            status = s;
        }
    }

    /** Test hook: how many blocks he has seen. */
    public static int observationCount() {
        return obsCount;
    }

    /** Test hook: the block id seen at a position, or null when that block was never seen. */
    public static String observedAt(BlockPos pos) {
        Integer i = OBS_INDEX.get(pos.asLong());
        return i == null ? null : BLOCK_NAMES.get(obsBlock[i]);
    }

    /** Test hook: every seen block with the eye it was seen from and where the ray met it,
     *  {bx, by, bz, ex, ey, ez, hx, hy, hz}. */
    public static List<double[]> observations() {
        List<double[]> out = new ArrayList<>(obsCount);
        for (int i = 0; i < obsCount; i++) {
            BlockPos p = BlockPos.of(obsPos[i]);
            out.add(new double[]{p.getX(), p.getY(), p.getZ(), obsEye[i * 3], obsEye[i * 3 + 1], obsEye[i * 3 + 2],
                    obsHit[i * 3], obsHit[i * 3 + 1], obsHit[i * 3 + 2]});
        }
        return out;
    }

    /** Test hook: seen blocks counted towards a tile (grid index). */
    public static int evidence(int gridIdx) {
        int t = (gridIdx / DungeonLayout.GRID / 2) * TILES + (gridIdx % DungeonLayout.GRID) / 2;
        return t >= 0 && t < EVIDENCE.length ? EVIDENCE[t] : 0;
    }

    /** Test hook: the placements still standing for a tile, "name@rotation@ax,az". */
    public static List<String> standing(int gridIdx) {
        int t = (gridIdx / DungeonLayout.GRID / 2) * TILES + (gridIdx % DungeonLayout.GRID) / 2;
        List<String> out = new ArrayList<>();
        if (t < 0 || t >= BY_TILE.length || BY_TILE[t] == null) {
            return out;
        }
        for (Candidate c : BY_TILE[t]) {
            if (!c.dead) {
                out.add(c.key() + " " + c.matches + "/" + c.mismatches);
            }
        }
        return out;
    }

    /**
     * Test hook: every seen block placement {@code key} ("name@rotation@ax,az", as {@link #standing} prints it, dead or not)
     * disagrees with, "x,y,z seen=<id> expected=<id>", up to {@code max}. Empty when the placement was never built.
     */
    public static List<String> disagreements(String key, int max) {
        List<String> out = new ArrayList<>();
        Candidate c = ALL.get(key);
        if (c == null) {
            return out;
        }
        int off = DungeonLayout.simYOffset();
        for (int i = 0; i < obsCount && out.size() < max; i++) {
            if ((c.tiles & (1L << obsTile[i])) == 0 || ignored(obsBlock[i])) {
                continue;
            }
            long packed = obsPos[i];
            int expected = expectedAt(c, BlockPos.getX(packed), BlockPos.getY(packed) - off, BlockPos.getZ(packed));
            if (expected < 0 || ignored(expected) || expected == obsBlock[i]) {
                continue;
            }
            int lx = BlockPos.getX(packed) - c.worldX0;
            int lz = BlockPos.getZ(packed) - c.worldZ0;
            out.add(BlockPos.getX(packed) + "," + BlockPos.getY(packed) + "," + BlockPos.getZ(packed) + " (capture "
                    + captureX(c, lx, lz) + "," + (BlockPos.getY(packed) - off) + "," + captureZ(c, lx, lz) + ") seen="
                    + BLOCK_NAMES.get(obsBlock[i]) + " expected=" + BLOCK_NAMES.get(expected));
        }
        return out;
    }

    /** Test hook: runs the comparisons and the decision now, as many ticks' worth as it takes. */
    public static void settle() {
        for (int i = 0; i < 400; i++) {
            boolean pending = false;
            for (Candidate c : ALL.values()) {
                if (!c.dead && c.cursor < obsCount) {
                    pending = true;
                    break;
                }
            }
            if (!pending) {
                break;
            }
            evaluate();
        }
        decide();
    }
}
