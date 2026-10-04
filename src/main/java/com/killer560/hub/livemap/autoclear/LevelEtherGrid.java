package com.killer560.hub.livemap.autoclear;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.CauldronBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The world as {@link EtherSearch} reads it: one byte of flags per block, a 4096-byte array per chunk section.
 *
 * <p>Filled a section at a time, straight from the section ({@code hasOnlyAir()} first, then
 * {@code LevelChunkSection.getBlockState} with the flags of the previous state reused while the state repeats),
 * because a ray walk is a section scan in disguise - see the CLAUDE.md lesson about Secret Waypoints. And KEPT
 * between searches: a click reads a few dozen sections (median 38, p90 92 measured by
 * {@code tools/bench/EtherSearchBench}), about a millisecond to fill, and the next click reads mostly the same
 * ones. An entry is only reused while the chunk still holds the very same section object, and the packet hooks
 * in {@code LiveMapPacketListenerMixin} drop a section the moment a block in it changes ({@link #invalidate}) or
 * its chunk is resent or forgotten ({@link #invalidateColumn}); anything older than {@link #MAX_AGE_MS} is
 * refilled regardless, so a change that slips past the hooks cannot outlive a few seconds.
 *
 * <p>The floor graph ({@link WarpGraph}) keeps edges worked out from these flags, so it has to hear about
 * changes. A packet hook does not tell it directly: the section's old flags are set aside, and on the planner
 * thread {@link #processChanges} reads the section again and reports it only if the FLAGS differ - a lever, a
 * chest lid or a chunk re-sent unchanged costs the graph nothing. The 15 s refill reports a difference too.
 *
 * <p>One instance per search and one thread per instance (the graph's warm-up workers each make their own); the
 * shared cache is the only concurrent part.
 */
public final class LevelEtherGrid implements EtherSearch.Grid {

    private static final long MAX_AGE_MS = 15_000;
    // Air sections are cached too (one shared array), and a rays-eye view of a floor reaches ~60 blocks past it:
    // about 10,000 sections. Overflowing clears the floor graph, so this must stay well above that.
    private static final int MAX_ENTRIES = 40_000;

    /** A section with nothing in it, and anything outside the world: all air. */
    private static final byte[] AIR_SECTION = new byte[4096];

    static {
        Arrays.fill(AIR_SECTION, (byte) (EtherSearch.PASSABLE | EtherSearch.AIR));
    }

    private record Entry(LevelChunkSection section, byte[] flags, long madeAtMs) {
    }

    private static final ConcurrentHashMap<Long, Entry> CACHE = new ConcurrentHashMap<>();
    private static volatile Level cacheLevel;

    /** Flags per block-state id, -1 until first seen. Racy writes are harmless: every writer writes the same. */
    private static volatile int[] stateFlags;

    private final Level level;

    // A small per-search open-addressing table: section key -> flags array.
    private long[] keys = new long[256];
    private byte[][] vals = new byte[256][];
    private int size;
    private long lastKey = Long.MIN_VALUE;
    private byte[] last;
    // A direct window of section refs around where the search starts - 33 x 33 columns by 32 sections, which
    // covers any dungeon - so the common case is an array index rather than a hash probe. Outside it, the table.
    private static final int WIN_XZ = 33;
    private static final int WIN_Y = 32;
    private byte[][] window;
    private int winX;
    private int winY;
    private int winZ;

    /** Sections this search had to fill rather than take from the cache, for the timing log. */
    int filled;

    LevelEtherGrid(Level level) {
        this.level = level;
        Arrays.fill(keys, Long.MIN_VALUE);
        if (cacheLevel != level) {
            CACHE.clear();
            DIRTY.clear();
            PREVIOUS.clear();
            cacheLevel = level;
        }
    }

    private static long sectionKey(int sx, int sy, int sz) {
        return ((long) (sx & 0x3FFFFF) << 42) | ((long) (sz & 0x3FFFFF) << 20) | (sy & 0xFFFFFL);
    }

    @Override
    public int flags(int x, int y, int z) {
        int sx = x >> 4;
        int sy = y >> 4;
        int sz = z >> 4;
        long key = sectionKey(sx, sy, sz);
        byte[] arr;
        if (key == lastKey) {
            arr = last;
        } else {
            if (window == null) {
                window = new byte[WIN_XZ * WIN_XZ * WIN_Y][];
                winX = sx - WIN_XZ / 2;
                winY = sy - WIN_Y / 2;
                winZ = sz - WIN_XZ / 2;
            }
            int wx = sx - winX;
            int wy = sy - winY;
            int wz = sz - winZ;
            int wi = (wx | wy | wz) >= 0 && wx < WIN_XZ && wz < WIN_XZ && wy < WIN_Y
                    ? (wy * WIN_XZ + wz) * WIN_XZ + wx : -1;
            arr = wi >= 0 ? window[wi] : null;
            if (arr == null) {
                arr = localGet(key);
                if (arr == null) {
                    arr = load(sx, sy, sz, key);
                    localPut(key, arr);
                }
                if (wi >= 0) {
                    window[wi] = arr;
                }
            }
            lastKey = key;
            last = arr;
        }
        return arr[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] & 0xFF;
    }

    private byte[] localGet(long key) {
        int mask = keys.length - 1;
        int i = (int) (key ^ (key >>> 29) ^ (key >>> 43)) & mask;
        while (keys[i] != Long.MIN_VALUE) {
            if (keys[i] == key) {
                return vals[i];
            }
            i = (i + 1) & mask;
        }
        return null;
    }

    private void localPut(long key, byte[] val) {
        if ((size + 1) * 2 > keys.length) {
            long[] oldK = keys;
            byte[][] oldV = vals;
            keys = new long[oldK.length * 2];
            vals = new byte[oldK.length * 2][];
            Arrays.fill(keys, Long.MIN_VALUE);
            size = 0;
            for (int i = 0; i < oldK.length; i++) {
                if (oldK[i] != Long.MIN_VALUE) {
                    localPut(oldK[i], oldV[i]);
                }
            }
        }
        int mask = keys.length - 1;
        int i = (int) (key ^ (key >>> 29) ^ (key >>> 43)) & mask;
        while (keys[i] != Long.MIN_VALUE && keys[i] != key) {
            i = (i + 1) & mask;
        }
        if (keys[i] == Long.MIN_VALUE) {
            size++;
        }
        keys[i] = key;
        vals[i] = val;
    }

    private byte[] load(int sx, int sy, int sz, long key) {
        if (mirrorOn) {
            long col = columnKey(sx, sz);
            if (MIRROR_COLUMNS.contains(col) && !FRESH_COLUMNS.contains(col)) {
                byte[] m = MIRROR.get(key);
                return m != null ? m : AIR_SECTION;
            }
        }
        LevelChunk chunk = level.getChunk(sx, sz);
        LevelChunkSection section = null;
        if (chunk != null) {
            LevelChunkSection[] sections = chunk.getSections();
            int idx = chunk.getSectionIndex(sy << 4);
            if (idx >= 0 && idx < sections.length) {
                section = sections[idx];
            }
        }
        // Air is cached too (with the section it was, or null for no chunk), so that when a chunk arrives the
        // floor graph can be told which sections really changed rather than every one it had read as air.
        long now = System.currentTimeMillis();
        Entry e = CACHE.get(key);
        if (e != null && e.section() == section && now - e.madeAtMs() < MAX_AGE_MS) {
            return e.flags();
        }
        byte[] out = section == null || section.hasOnlyAir() ? AIR_SECTION
                : fill(level, section, sx << 4, sy << 4, sz << 4);
        if (out != AIR_SECTION) {
            filled++;
        }
        if (CACHE.size() > MAX_ENTRIES) {
            CACHE.clear();
            WarpGraph g = listener;
            if (g != null) {
                g.clear();   // what it read can no longer be compared against
            }
        }
        if (e != null && !Arrays.equals(e.flags(), out)) {
            // A change the packet hooks did not report, caught by the 15 s refill.
            WarpGraph g = listener;
            if (g != null) {
                g.sectionChanged(sx, sy, sz);
            }
        }
        CACHE.put(key, new Entry(section, out, now));
        return out;
    }

    // ------------------------------------------------------------------------------------------- the floor graph

    /** The floor graph to tell about sections whose flags changed (EtherwarpPathfinder sets it). */
    static volatile WarpGraph listener;

    /** Sections a packet touched, and the flags they had before, until the planner compares them. */
    private static final java.util.concurrent.ConcurrentLinkedQueue<Long> DIRTY =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<Long, byte[]> PREVIOUS = new ConcurrentHashMap<>();

    private static void markDirty(long key) {
        Entry old = CACHE.remove(key);
        if (old != null) {
            PREVIOUS.putIfAbsent(key, old.flags());
            DIRTY.add(key);
        }
    }

    /** Whether a packet touched a section the planner has read and not compared since. */
    static boolean hasPendingChanges() {
        return !DIRTY.isEmpty();
    }

    /**
     * Planner thread: reads every section a packet touched again and tells the floor graph about the ones whose
     * FLAGS changed. A lever flipping or a chunk re-sent unchanged changes nothing the search reads, so it costs
     * the graph nothing; a door opening drops exactly the nodes whose rays crossed it. A section the planner never
     * read cannot have anything depending on it and is not queued at all.
     */
    static void processChanges(Level level) {
        if (DIRTY.isEmpty()) {
            return;
        }
        WarpGraph g = listener;
        LevelEtherGrid grid = new LevelEtherGrid(level);
        java.util.Set<Long> done = new java.util.HashSet<>();
        Long key;
        while ((key = DIRTY.poll()) != null) {
            if (!done.add(key)) {
                continue;
            }
            byte[] before = PREVIOUS.remove(key);
            if (before == null) {
                continue;
            }
            int sx = (int) (key >>> 42) << 10 >> 10;
            int sz = (int) ((key >>> 20) & 0x3FFFFF) << 10 >> 10;
            int sy = (int) (key & 0xFFFFFL) << 12 >> 12;
            byte[] now = grid.load(sx, sy, sz, key);
            if (g != null && !Arrays.equals(before, now)) {
                g.sectionChanged(sx, sy, sz);
            }
        }
    }

    private static byte[] fill(Level level, LevelChunkSection section, int bx, int by, int bz) {
        byte[] out = new byte[4096];
        BlockState lastState = null;
        int lastFlags = 0;
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int ly = 0; ly < 16; ly++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    BlockState s = section.getBlockState(lx, ly, lz);
                    if (s != lastState) {
                        lastState = s;
                        lastFlags = flagsFor(level, s, mut.set(bx + lx, by + ly, bz + lz));
                    }
                    out[(ly << 8) | (lz << 4) | lx] = (byte) lastFlags;
                }
            }
        }
        return out;
    }

    /**
     * The byte for one state: {@link TeleportUtils}' passable/feet flags, the blacklist
     * {@code EtherwarpPathfinder} used to apply per hit, air, and the collision top {@code traverseVoxels} reads
     * off the shape on every hit. Worked out once per state and remembered - the collision top is taken at the
     * first position the state is met at, which is exact for every block whose shape does not depend on where it
     * stands (all but a handful of plants nobody lands on).
     */
    private static int flagsFor(Level level, BlockState state, BlockPos pos) {
        int id = Block.getId(state);
        int[] table = stateFlags;
        if (table == null || id >= table.length) {
            int[] fresh = new int[Math.max(id + 1, Block.BLOCK_STATE_REGISTRY.size())];
            Arrays.fill(fresh, -1);
            if (table != null) {
                System.arraycopy(table, 0, fresh, 0, table.length);
            }
            stateFlags = table = fresh;
        }
        int known = id >= 0 ? table[id] : -1;
        if (known >= 0) {
            return known;
        }
        int f = TeleportUtils.flagsOf(state) & (EtherSearch.PASSABLE | EtherSearch.BLOCKS_FEET);
        if (state.isAir()) {
            f |= EtherSearch.AIR;
        }
        if (blackListed(state)) {
            f |= EtherSearch.BLACKLIST;
        }
        if ((f & EtherSearch.PASSABLE) == 0) {
            double top = state.getCollisionShape(level, pos).max(Direction.Axis.Y);
            int ceil = (int) Math.max(1.0, Math.ceil(top));
            f |= Math.min(3, ceil) << EtherSearch.TOP_SHIFT;
        }
        if (id >= 0) {
            table[id] = f;
        }
        return f;
    }

    /** QUOI's landing blacklist, formerly {@code EtherwarpPathfinder.blackListed}. */
    static boolean blackListed(BlockState state) {
        if (state == null) {
            return true;
        }
        var block = state.getBlock();
        boolean bottomSlab = block instanceof SlabBlock && state.hasProperty(SlabBlock.TYPE)
                && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
        return bottomSlab || block instanceof CarpetBlock || block instanceof WallBlock || block instanceof FenceBlock
                || block instanceof FenceGateBlock || block instanceof HopperBlock || block instanceof CauldronBlock
                || block instanceof BannerBlock;
    }

    // ------------------------------------------------------------------------------------------- invalidation

    /** A block changed: forget its section. Called on the client thread after vanilla has applied it. */
    public static void invalidate(BlockPos pos) {
        markDirty(sectionKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
        if (mirrorOn && FRESH_COLUMNS.add(columnKey(pos.getX() >> 4, pos.getZ() >> 4))) {
            // The server only sends block changes for a chunk it has sent him, so his copy is current - and the
            // whole column now reads from it instead of the snapshot, which the graph cannot compare against.
            columnSwitched(pos.getX() >> 4, pos.getZ() >> 4);
        }
    }

    /** A batch of block changes in one section. Kept out of the mixin so the mixin holds no lambda. */
    public static void invalidate(net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket packet) {
        packet.runUpdates((pos, state) -> invalidate(pos));
    }

    /** A chunk was resent or forgotten: forget every section of the column. */
    public static void invalidateColumn(int chunkX, int chunkZ) {
        if (mirrorOn && FRESH_COLUMNS.add(columnKey(chunkX, chunkZ))) {
            columnSwitched(chunkX, chunkZ);
        }
        markColumnDirty(chunkX, chunkZ);
    }

    private static void columnSwitched(int chunkX, int chunkZ) {
        if (MIRROR_COLUMNS.contains(columnKey(chunkX, chunkZ))) {
            WarpGraph g = listener;
            if (g != null) {
                g.columnChanged(chunkX, chunkZ);
            }
        }
    }

    private static void markColumnDirty(int chunkX, int chunkZ) {
        long sx = chunkX & 0x3FFFFF;
        long sz = chunkZ & 0x3FFFFF;
        for (Long k : CACHE.keySet()) {
            if (((k >>> 42) & 0x3FFFFF) == sx && ((k >>> 20) & 0x3FFFFF) == sz) {
                markDirty(k);
            }
        }
    }

    // ------------------------------------------------------------------------------------------- sim mirror

    /**
     * THE SIM ONLY: the floor as the integrated server holds it, for the chunks his client has not been sent
     * since the floor was built.
     *
     * <p>killer560's log (2026-10-04): "Room hop 2 of 3 failed: no warp chain from ..." for doors 30 blocks away,
     * right after a floor was built, and fine again once he had teleported over there. The sim writes a floor
     * without telling clients (SimBuildQueue: chunks are streamed when he is put back), and with Keep Chunks
     * Loaded / the Chunk Cache his client keeps every chunk it ever had - so every chunk he has not been sent
     * since the rebuild still holds the PREVIOUS floor, at the previous floor's altitude, and the search was
     * planning through walls that are not there and doorways that are. A chunk he was never sent at all reads
     * as air, which fails the same way.
     *
     * <p>{@link #mirror} is called on the server thread when a sim build has finished; a column then reads from
     * this snapshot until his client receives that chunk (or a block change in it), after which the client's own
     * copy - kept current by the server from then on - is used again. {@link #dropMirror} on leaving the sim.
     * Nothing outside the sim ever calls {@link #mirror}, so on Hypixel {@code mirrorOn} is false and the grid
     * reads exactly what it always read. Not covered: a change the server makes in a column he has not been sent
     * since the build (out of his view) is not in the snapshot.
     */
    private static final ConcurrentHashMap<Long, byte[]> MIRROR = new ConcurrentHashMap<>();
    private static final java.util.Set<Long> MIRROR_COLUMNS = ConcurrentHashMap.newKeySet();
    private static final java.util.Set<Long> FRESH_COLUMNS = ConcurrentHashMap.newKeySet();
    private static volatile boolean mirrorOn;

    private static long columnKey(int sx, int sz) {
        return ((long) sx << 32) ^ (sz & 0xFFFFFFFFL);
    }

    /**
     * Snapshots every chunk column touching the block box from the server's own level. SERVER THREAD ONLY
     * ({@code getChunkNow} answers null anywhere else). Returns how many non-empty sections it copied.
     */
    public static int mirror(net.minecraft.server.level.ServerLevel serverLevel,
                             int minX, int minZ, int maxX, int maxZ) {
        MIRROR.clear();
        MIRROR_COLUMNS.clear();
        FRESH_COLUMNS.clear();
        CACHE.clear();
        DIRTY.clear();
        PREVIOUS.clear();
        WarpGraph g = listener;
        if (g != null) {
            g.clear();   // a new floor
        }
        int copied = 0;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;   // not loaded on the server either: leave this column to the client
                }
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection section = sections[i];
                    if (section == null || section.hasOnlyAir()) {
                        continue;
                    }
                    int sy = chunk.getSectionYFromSectionIndex(i);
                    MIRROR.put(sectionKey(cx, sy, cz), fill(serverLevel, section, cx << 4, sy << 4, cz << 4));
                    copied++;
                }
                MIRROR_COLUMNS.add(columnKey(cx, cz));
            }
        }
        mirrorOn = true;
        return copied;
    }

    /** Leaving the sim: back to reading only the client's world. */
    public static void dropMirror() {
        mirrorOn = false;
        MIRROR.clear();
        MIRROR_COLUMNS.clear();
        FRESH_COLUMNS.clear();
        CACHE.clear();
        DIRTY.clear();
        PREVIOUS.clear();
        WarpGraph g = listener;
        if (g != null) {
            g.clear();
        }
    }
}
