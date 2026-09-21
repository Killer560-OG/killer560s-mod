package com.killer560.hub.storagesearch;

import com.killer560.hub.chunkcache.ChunkCacheManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds the chest blocks around killer560 - including ones he is nowhere near any more.
 * <p>
 * This is the half of island-chest search that needs {@link ChunkCacheManager}: vanilla's
 * {@code ClientChunkCache} only answers for chunks inside its fixed ring around the view centre, so walking to the
 * other end of the island is enough to make a chest you opened five seconds ago unreadable. With the Chunk Cache on,
 * {@code Level.getChunk(..., FULL, false)} falls back to the cached chunk object (and its block entities, which that
 * feature deliberately keeps alive through unload), so a chest stays discoverable for the rest of the world session.
 * With it off this still works - just only within vanilla's own ring, which is what the search screen warns about.
 * <p>
 * Deliberately on demand: the scan runs when the search index is built (and at most once every
 * {@link #CACHE_MS}), never per frame. A naive per-frame version of this walks every cached chunk's block-entity map
 * thousands of times a second, which is exactly the kind of thing that cost this mod its frame rate before.
 */
public final class IslandChestScanner {

    /** How long one scan's result is reused before the next one is allowed to run. */
    private static final long CACHE_MS = 10_000L;

    private static long lastScanAt = 0L;
    private static int lastRadius = -1;
    private static ClientLevel lastLevel = null;
    private static List<BlockPos> lastResult = List.of();

    private IslandChestScanner() {
    }

    /** True when the Chunk Cache is on, i.e. when chests outside vanilla's own chunk ring are discoverable. */
    public static boolean chunkCacheActive() {
        return ChunkCacheManager.isActive();
    }

    /** Every chest/barrel/shulker block position in a {@code radiusChunks} chunk square around the player that this
     *  client can still read (loaded, or kept by the Chunk Cache). Memoised - see the class doc. */
    public static List<BlockPos> scan(int radiusChunks) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null || client.player == null) {
            lastResult = List.of();
            return lastResult;
        }
        long now = System.currentTimeMillis();
        if (level == lastLevel && radiusChunks == lastRadius && now - lastScanAt < CACHE_MS) {
            return lastResult;
        }
        List<BlockPos> found = new ArrayList<>();
        BlockPos playerPos = client.player.blockPosition();
        int centerX = playerPos.getX() >> 4;
        int centerZ = playerPos.getZ() >> 4;
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                LevelChunk chunk = readableChunk(level, centerX + dx, centerZ + dz);
                if (chunk == null) {
                    continue;
                }
                for (var entry : chunk.getBlockEntities().entrySet()) {
                    if (isChestLike(entry.getValue())) {
                        found.add(entry.getKey().immutable());
                    }
                }
            }
        }
        lastLevel = level;
        lastRadius = radiusChunks;
        lastScanAt = now;
        lastResult = found;
        return found;
    }

    /**
     * @return the cache keys of remembered chests whose block is provably gone - the chunk is readable right now
     *         and there is no chest-like block entity at that position any more. A chest in a chunk this client
     *         cannot read is left alone (absence of evidence, not evidence of absence).
     */
    public static List<String> scanForMissing(List<IslandChestCache.ChestEntry> entries) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        List<String> missing = new ArrayList<>();
        if (level == null || entries.isEmpty()) {
            return missing;
        }
        for (IslandChestCache.ChestEntry entry : entries) {
            BlockPos pos = entry.pos();
            LevelChunk chunk = readableChunk(level, pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) {
                continue;
            }
            if (!isChestLike(chunk.getBlockEntities().get(pos))) {
                missing.add(entry.key());
            }
        }
        return missing;
    }

    /** Positions from {@link #scan} that no remembered chest covers - "N nearby chests you've never opened". */
    public static int countUnopened(List<BlockPos> scanned, List<IslandChestCache.ChestEntry> remembered) {
        if (scanned.isEmpty()) {
            return 0;
        }
        Set<BlockPos> known = new HashSet<>();
        for (IslandChestCache.ChestEntry entry : remembered) {
            known.add(entry.pos());
        }
        int count = 0;
        for (BlockPos pos : scanned) {
            if (!known.contains(pos)) {
                count++;
            }
        }
        return count;
    }

    /** A double chest reports one block entity per half, so this can legitimately return two positions for what
     *  looks like one chest - that matches how the contents were captured (per opened screen, keyed on the half
     *  killer560 actually clicked). */
    public static boolean isChestLike(BlockEntity blockEntity) {
        return blockEntity instanceof ChestBlockEntity
                || blockEntity instanceof BarrelBlockEntity
                || blockEntity instanceof ShulkerBoxBlockEntity;
    }

    private static LevelChunk readableChunk(ClientLevel level, int chunkX, int chunkZ) {
        try {
            ChunkAccess chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            return chunk instanceof LevelChunk levelChunk ? levelChunk : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
