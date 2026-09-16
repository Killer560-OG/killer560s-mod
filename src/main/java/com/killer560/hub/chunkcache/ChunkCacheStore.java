package com.killer560.hub.chunkcache;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;

/**
 * One world's chunk cache (killer560, 2026-09-15: "whenever I go too far away from chunks in Skyblock they unload...
 * it would be very useful for things like the Interactive Map to have every chunk always loaded after I've loaded it
 * once, and keep them stored in memory").
 * <p>
 * Every chunk the server sends is remembered here by packed {@link net.minecraft.world.level.ChunkPos} key, so once
 * vanilla's fixed-size {@code ClientChunkCache.Storage} ring drops it (view centre moved, or a forget packet), the
 * chunk object itself is still reachable and every world read (block states, block entities, heightmaps) keeps
 * working. The entry for a position is always the newest chunk object the server sent for it, so a live chunk is
 * never shadowed by a stale copy.
 * <p>
 * One store belongs to one {@code ClientChunkCache} instance (see {@link ChunkCacheHolder}), which belongs to one
 * {@code ClientLevel}. A world change (island switch, dungeon enter/leave, respawn, disconnect) builds a new
 * {@code ClientLevel} with a new chunk source, so nothing can leak from one world into the next; the manager also
 * clears the outgoing store explicitly.
 * <p>
 * Access-ordered LRU: {@link #get} moves the entry to the end, {@link #trim} evicts from the front. Every method is
 * synchronized because {@code ClientChunkCache.getChunk} is deliberately callable off the client thread (vanilla uses
 * an {@code AtomicReferenceArray} for exactly that reason, and this mod's own pathfinder raycasts world blocks from
 * worker threads).
 */
public final class ChunkCacheStore {

    /** Rough per-chunk overhead (heightmaps, biome container, block-entity map, object headers). */
    private static final int BASE_BYTES = 3072;
    /** Rough cost of one non-empty 16x16x16 section (paletted block states at 4-8 bits per block). */
    private static final int SECTION_BYTES = 3072;

    private record Entry(LevelChunk chunk, int bytes) {
    }

    private final Long2ObjectLinkedOpenHashMap<Entry> entries = new Long2ObjectLinkedOpenHashMap<>();
    private long estimatedBytes;

    /** @return the cached chunk for this packed chunk position, marking it as most-recently-used. */
    public synchronized LevelChunk get(long key) {
        Entry entry = entries.getAndMoveToLast(key);
        return entry == null ? null : entry.chunk();
    }

    /** Stores (or replaces) the chunk for a position. @return the chunk that was stored there before, if different. */
    public synchronized LevelChunk put(long key, LevelChunk chunk) {
        int bytes = estimateBytes(chunk);
        Entry previous = entries.putAndMoveToLast(key, new Entry(chunk, bytes));
        estimatedBytes += bytes;
        if (previous == null) {
            return null;
        }
        estimatedBytes -= previous.bytes();
        return previous.chunk() == chunk ? null : previous.chunk();
    }

    /** Evicts least-recently-used chunks until at most {@code max} remain. @return the evicted chunks. */
    public synchronized List<LevelChunk> trim(int max) {
        if (entries.size() <= max) {
            return List.of();
        }
        List<LevelChunk> evicted = new ArrayList<>(entries.size() - max);
        while (entries.size() > max) {
            Entry entry = entries.removeFirst();
            estimatedBytes -= entry.bytes();
            evicted.add(entry.chunk());
        }
        return evicted;
    }

    /** Drops everything. @return the chunks that were cached, so the caller can release their block entities. */
    public synchronized List<LevelChunk> clear() {
        if (entries.isEmpty()) {
            return List.of();
        }
        List<LevelChunk> all = new ArrayList<>(entries.size());
        for (Entry entry : entries.values()) {
            all.add(entry.chunk());
        }
        entries.clear();
        estimatedBytes = 0L;
        return all;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Deliberately rough (see the constants above) - it's a "how much RAM is this costing me" readout, not a measure. */
    public synchronized long estimatedBytes() {
        return estimatedBytes;
    }

    private static int estimateBytes(LevelChunk chunk) {
        int bytes = BASE_BYTES;
        try {
            LevelChunkSection[] sections = chunk.getSections();
            for (LevelChunkSection section : sections) {
                if (section != null && !section.hasOnlyAir()) {
                    bytes += SECTION_BYTES;
                }
            }
        } catch (RuntimeException ignored) {
            // A chunk mid-replace can throw here; the estimate is cosmetic, never let it break caching.
        }
        return bytes;
    }
}
