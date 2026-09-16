package com.killer560.hub.chunkcache;

import com.killer560.hub.livemap.LiveMapConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Chunk Cache (killer560, 2026-09-15): keeps every chunk this client has loaded reachable in memory for the rest of
 * the world session, so features that read the world - the Interactive Map and its etherwarp pathfinder, secret
 * waypoints, the solvers, etherwarp raycasts - keep seeing blocks in rooms already visited instead of air.
 *
 * <h2>Why this is needed on 26.1.2</h2>
 * {@code ClientChunkCache} stores chunks in a fixed {@code (2r+1)^2} ring ({@code r = serverViewDistance + 3}) around
 * the view centre, and {@code getChunk} refuses anything outside that ring ({@code Storage.inRange}) - so walking
 * away is enough to make a chunk unreadable even before the server's {@code ClientboundForgetLevelChunkPacket}
 * arrives. Cancelling that packet (the Live Map's existing "Keep Chunks Loaded") therefore cannot be enough on its own.
 *
 * <h2>How it works</h2>
 * Every chunk the server sends is recorded in the world's {@link ChunkCacheStore} as it is applied
 * ({@code ClientChunkCache.replaceWithPacketData}), and {@code getChunk} falls back to that store whenever vanilla
 * would have answered "not loaded" (null / the shared empty chunk). Because the store always holds the newest chunk
 * object the server sent for a position, a chunk that is still live in vanilla's ring is returned by vanilla itself
 * and is never shadowed by a stale copy; when the server re-sends a chunk, the cached copy is replaced.
 * <p>
 * Block entities survive the eviction: {@code ClientLevel.unload} clears a chunk's block-entity map, so the map is
 * snapshotted before it runs and put back (un-removed) afterwards, without re-registering any tickers.
 *
 * <h2>Known limitation</h2>
 * The server sends no block updates for chunks it no longer tracks, so a cached chunk is a snapshot from the moment
 * it was last sent. Blocks broken or placed there afterwards are not reflected until the server sends that chunk
 * again, which replaces the cached copy.
 *
 * <h2>What is deliberately NOT changed</h2>
 * Rendering. Cached chunks have had their lighting disabled by vanilla, so drawing them would paint pitch-black
 * terrain; the two renderer lookups ({@code SectionRenderDispatcher$RenderSection.doesChunkExistAt} and
 * {@code RenderRegionCache}'s section copy) are routed back to a vanilla-only lookup, so the world looks exactly like
 * it does without this feature. Entity handling and the light engine (which asks for {@code ChunkStatus.EMPTY}, never
 * {@code FULL}) are untouched for the same reason.
 */
public final class ChunkCacheManager {

    /** Set while a renderer lookup is running, so the {@code getChunk} fallback stays out of its way. */
    private static final ThreadLocal<boolean[]> BYPASS = ThreadLocal.withInitial(() -> new boolean[1]);

    /** Recomputed once per client tick so the hot {@code getChunk} path is one volatile read, never a config read. */
    private static volatile boolean active;
    private static volatile int maxChunks = ChunkCacheConfig.DEFAULT_CHUNKS;

    private static WeakReference<ClientLevel> lastLevel = new WeakReference<>(null);
    /** Block entities of the chunk currently inside {@code ClientLevel.unload} (client thread only). */
    private static Map<BlockPos, BlockEntity> pendingBlockEntities;

    private ChunkCacheManager() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ChunkCacheManager::onClientTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            active = false;
            onLevelChanged(null);
        });
    }

    // ---------------------------------------------------------------- state

    /** True when the feature (or the Live Map's "Keep Chunks Loaded", which defers to it) is currently on. */
    public static boolean isActive() {
        return active;
    }

    public static boolean isBypassed() {
        return BYPASS.get()[0];
    }

    private static boolean keepChunksLoadedRaw() {
        LiveMapConfig live = LiveMapConfig.getInstance();
        return live.isKeepChunksLoadedRaw() && (live.isPathingEnabledRaw() || live.isBloodRushEnabledRaw());
    }

    private static void onClientTick(Minecraft client) {
        ChunkCacheConfig cfg = ChunkCacheConfig.getInstance();
        // The Live Map's "Keep Chunks Loaded" keeps cancelling forget packets exactly as before AND turns this cache
        // on, since cancelling that packet alone does not survive the view centre moving.
        active = cfg.isEnabled() || LiveMapConfig.getInstance().isKeepChunksLoaded();
        maxChunks = cfg.getMaxChunks();

        ClientLevel level = client.level;
        if (level != lastLevel.get()) {
            onLevelChanged(level);
            return;
        }
        ChunkCacheStore store = storeOf(level);
        if (store == null) {
            return;
        }
        if (!cfg.isEnabledRaw() && !keepChunksLoadedRaw()) {
            // Turned off in the GUI: hand the memory back. A "Skyblock Only" flicker only stops caching, it never
            // throws away what is already cached (the raw toggles are what is checked here).
            releaseAll(store);
            return;
        }
        trim(store);
    }

    private static void onLevelChanged(ClientLevel level) {
        // If ClientLevel.unload ever threw between the HEAD and RETURN injections, this still holds that chunk's
        // block entities (and through them the old level). Never carry it across a world change.
        pendingBlockEntities = null;
        ClientLevel previous = lastLevel.get();
        if (previous != null) {
            releaseAll(storeOf(previous));
        }
        lastLevel = new WeakReference<>(level);
    }

    // ---------------------------------------------------------------- store access

    public static ChunkCacheStore storeOf(ClientLevel level) {
        if (level == null) {
            return null;
        }
        Object source = level.getChunkSource();
        return source instanceof ChunkCacheHolder holder ? holder.killer560smod$chunkCacheStore() : null;
    }

    /** Cached chunk count for the world the player is in right now (0 when there is no world). */
    public static int cachedCount() {
        ChunkCacheStore store = storeOf(Minecraft.getInstance().level);
        return store == null ? 0 : store.size();
    }

    /** Rough memory estimate for {@link #cachedCount()}, in bytes. */
    public static long estimatedBytes() {
        ChunkCacheStore store = storeOf(Minecraft.getInstance().level);
        return store == null ? 0L : store.estimatedBytes();
    }

    /** "Clear Cache" button. Chunks vanilla still has stay perfectly usable - they are re-cached as they are re-sent. */
    public static void clearCache() {
        releaseAll(storeOf(Minecraft.getInstance().level));
    }

    // ---------------------------------------------------------------- mixin entry points

    /** {@code ClientChunkCache.replaceWithPacketData} returned: this object is now the newest copy of that chunk. */
    public static void onChunkReceived(ChunkCacheStore store, int x, int z, LevelChunk chunk) {
        if (!active || store == null || chunk == null) {
            return;
        }
        LevelChunk replaced = store.put(ChunkPos.pack(x, z), chunk);
        if (replaced != null) {
            release(replaced);
        }
        trim(store);
    }

    /** HEAD of {@code ClientLevel.unload} - vanilla is about to clear this chunk's block entities. */
    public static void beforeUnload(LevelChunk chunk) {
        pendingBlockEntities = null;
        if (!active || chunk == null) {
            return;
        }
        Map<BlockPos, BlockEntity> blockEntities = chunk.getBlockEntities();
        if (!blockEntities.isEmpty()) {
            pendingBlockEntities = new HashMap<>(blockEntities);
        }
    }

    /** RETURN of {@code ClientLevel.unload} - put the block entities back so cached chests/skulls stay readable. */
    public static void afterUnload(LevelChunk chunk) {
        Map<BlockPos, BlockEntity> snapshot = pendingBlockEntities;
        pendingBlockEntities = null;
        if (snapshot == null || !active || chunk == null) {
            return;
        }
        Map<BlockPos, BlockEntity> blockEntities = chunk.getBlockEntities();
        for (Map.Entry<BlockPos, BlockEntity> entry : snapshot.entrySet()) {
            BlockEntity blockEntity = entry.getValue();
            if (blockEntity == null) {
                continue;
            }
            // Un-remove, but deliberately NOT through setBlockEntity/addAndRegisterBlockEntity: no tickers are
            // registered for a cached chunk, so nothing in an evicted chunk ticks.
            blockEntity.clearRemoved();
            blockEntities.put(entry.getKey(), blockEntity);
        }
    }

    /** Vanilla-only lookup (renderer paths), bypassing the cache fallback. */
    public static ChunkAccess vanillaGetChunk(ClientLevel level, int x, int z, ChunkStatus status, boolean load) {
        if (!active) {
            return level.getChunk(x, z, status, load);
        }
        boolean[] flag = BYPASS.get();
        boolean previous = flag[0];
        flag[0] = true;
        try {
            return level.getChunk(x, z, status, load);
        } finally {
            flag[0] = previous;
        }
    }

    /** Vanilla-only lookup (renderer paths), bypassing the cache fallback. */
    public static LevelChunk vanillaGetChunk(Level level, int x, int z) {
        if (!active) {
            return level.getChunk(x, z);
        }
        boolean[] flag = BYPASS.get();
        boolean previous = flag[0];
        flag[0] = true;
        try {
            return level.getChunk(x, z);
        } finally {
            flag[0] = previous;
        }
    }

    /** @return whether vanilla itself still holds this chunk (as opposed to it only existing in the cache). */
    public static boolean isLive(LevelChunk chunk) {
        if (chunk == null) {
            return false;
        }
        Level level = chunk.getLevel();
        if (!(level instanceof ClientLevel clientLevel)) {
            return false;
        }
        ChunkPos pos = chunk.getPos();
        return vanillaGetChunk(clientLevel, pos.x(), pos.z(), ChunkStatus.FULL, false) == chunk;
    }

    // ---------------------------------------------------------------- memory

    private static void trim(ChunkCacheStore store) {
        for (LevelChunk evicted : store.trim(maxChunks)) {
            release(evicted);
        }
    }

    private static void releaseAll(ChunkCacheStore store) {
        if (store == null || store.isEmpty()) {
            return;
        }
        for (LevelChunk chunk : store.clear()) {
            release(chunk);
        }
    }

    /** Lets go of a chunk this cache was the last owner of, including any block-entity ticker created while cached. */
    private static void release(LevelChunk chunk) {
        if (chunk == null || isLive(chunk)) {
            return;
        }
        try {
            chunk.clearAllBlockEntities();
        } catch (RuntimeException ignored) {
            // Releasing memory must never take the client down with it.
        }
    }
}
