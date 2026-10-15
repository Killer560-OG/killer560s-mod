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
 * <p><b>What it sees.</b> Every {@link #SAMPLE_EVERY_TICKS} ticks a grid of rays leaves his eyes across his field of
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
 * blocks that change during a run). A candidate that disagrees with more than {@link #MISMATCH_FLOOR} plus
 * {@link #MISMATCH_RATE} of what it was compared with is ruled out, for good.
 *
 * <p><b>What it says.</b> For a tile with at least {@link #MIN_EVIDENCE} seen blocks: if every candidate still standing
 * is the same room, that is the room. The tiles EVERY standing candidate covers are certain and are filled in; tiles
 * only some of them cover are "?". So a room whose turn he cannot tell yet shows only the tiles he can be sure of, and
 * the rest follows as soon as something he sees rules the other turns out. Two different rooms still standing: nothing.
 * Doors are never predicted - the map draws only doors the held map shows.
 */
public final class RoomPredictor {

    private static final Logger LOGGER = ModLog.get("killer560smod-livemap");

    static final int SAMPLE_EVERY_TICKS = 4;
    static final int RAYS_X = 24;
    static final int RAYS_Y = 14;
    static final double RAY_RANGE = 48.0;
    /** Seen blocks a tile needs before anything is said about it. */
    static final int MIN_EVIDENCE = 120;
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

    private static int generation = Integer.MIN_VALUE;
    private static Object level = null;
    private static int ticks = 0;
    private static int sampleSeed = 0;
    private static String status = "idle";
    private static boolean dirty = false;

    private RoomPredictor() {
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
            return;
        }
        boolean sim = com.killer560.hub.roomsim.SimState.isActive();
        if (!(sim || com.killer560.hub.secrets.DungeonState.isInDungeon()) || LiveMapFeature.isInBoss()) {
            setStatus("not in a dungeon clear");
            return;
        }
        if (!RoomLibrary.isReady()) {
            RoomLibrary.loadAsync();
            setStatus("waiting for the room library");
            return;
        }
        if (!RoomDatabase.isReady()) {
            RoomDatabase.ensureLoading();
        }
        if (++ticks % SAMPLE_EVERY_TICKS == 0) {
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
        obsCount = 0;
        OBS_INDEX.clear();
        Arrays.fill(EVIDENCE, 0);
        ALL.clear();
        Arrays.fill(BY_TILE, null);
        PREDICTIONS.clear();
        dirty = false;
    }

    // ------------------------------------------------------------------------------------------------- seeing

    /** One sweep of rays across his view. Only the first block each ray hits is read. */
    static void sample(Minecraft client) {
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
        for (int i = 0; i < RAYS_X; i++) {
            for (int j = 0; j < RAYS_Y; j++) {
                double u = ((i + jx) / RAYS_X) * 2 - 1;
                double v = ((j + jy) / RAYS_Y) * 2 - 1;
                Vec3 dir = f.add(right.scale(u * tanH)).add(up.scale(v * tanV)).normalize();
                Vec3 end = eye.add(dir.scale(RAY_RANGE));
                HitResult hit = client.level.clip(new ClipContext(eye, end, ClipContext.Block.VISUAL,
                        ClipContext.Fluid.NONE, client.player));
                if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
                    continue;
                }
                record(client, bhr.getBlockPos(), eye, bhr.getLocation(), floor);
            }
        }
    }

    private static void record(Minecraft client, BlockPos pos, Vec3 eye, Vec3 hitAt, int[] floor) {
        int tx = Math.floorDiv(pos.getX() - ORIGIN, TILE_PITCH);
        int tz = Math.floorDiv(pos.getZ() - ORIGIN, TILE_PITCH);
        if (tx < 0 || tz < 0 || tx >= floor[0] || tz >= floor[1]) {
            return;
        }
        int lx = pos.getX() - ORIGIN - tx * TILE_PITCH;
        int lz = pos.getZ() - ORIGIN - tz * TILE_PITCH;
        if (lx < EDGE || lz < EDGE || lx > RoomLibrary.TILE - 1 - EDGE || lz > RoomLibrary.TILE - 1 - EDGE) {
            return;
        }
        int gridIdx = tz * 2 * DungeonLayout.GRID + tx * 2;
        if (!isTarget(client, gridIdx)) {
            return;
        }
        long key = pos.asLong();
        if (OBS_INDEX.containsKey(key) || obsCount >= MAX_OBSERVATIONS) {
            return;
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
                BlockPos p = BlockPos.of(obsPos[i]);
                int expected = expectedAt(c, p.getX(), p.getY() - off, p.getZ());
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

    /** The block id candidate {@code c} has at world x/z and CAPTURE y, or -1 where its capture has nothing. */
    private static int expectedAt(Candidate c, int wx, int cy, int wz) {
        RoomLibrary.Room r = c.room;
        int lx = wx - c.worldX0;
        int lz = wz - c.worldZ0;
        int x;
        int z;
        switch (c.rot) {
            case 90 -> {
                x = lz;
                z = r.sizeZ - 1 - lx;
            }
            case 180 -> {
                x = r.sizeX - 1 - lx;
                z = r.sizeZ - 1 - lz;
            }
            case 270 -> {
                x = r.sizeX - 1 - lz;
                z = lx;
            }
            default -> {
                x = lx;
                z = lz;
            }
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
            if (pending || standing.isEmpty()) {
                continue;
            }
            Set<String> names = new LinkedHashSet<>();
            for (Candidate c : standing) {
                names.add(c.room.name);
            }
            if (names.size() != 1) {
                continue;
            }
            long all = -1L;
            long any = 0L;
            Set<String> turns = new LinkedHashSet<>();
            for (Candidate c : standing) {
                all &= c.tiles;
                any |= c.tiles;
                turns.add(c.rot + "@" + c.ax + "," + c.az);
            }
            String name = names.iterator().next();
            RoomEntry entry = RoomDatabase.lookupByName(name);
            String type = entry != null && entry.type != null ? entry.type.toUpperCase(Locale.ROOT) : "NORMAL";
            int gridIdx = (t / TILES) * 2 * DungeonLayout.GRID + (t % TILES) * 2;
            next.put(gridIdx, new Prediction(gridIdx, name, type, tilesOf(all), tilesOf(any & ~all),
                    turns.size() == 1, standing.size(), EVIDENCE[t]));
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
        PREDICTIONS.clear();
        PREDICTIONS.putAll(next);
        setStatus(obsCount + " block(s) seen, " + PREDICTIONS.size() + " prediction(s)");
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
