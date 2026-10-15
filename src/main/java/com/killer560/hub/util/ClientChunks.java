package com.killer560.hub.util;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * "Does the client actually have this chunk?" - the one place the mod asks it.
 *
 * <p>{@code ClientLevel.hasChunk(x, z)} is hard-wired to {@code true} (javap, 26.1.2 and 26.2: {@code iconst_1;
 * ireturn}), and so is everything built on it: {@code LevelReader.hasChunkAt(x, z)} and {@code hasChunkAt(BlockPos)}
 * call it. Meanwhile a chunk the client does not have reads as the shared empty chunk - all air - so a scanner gated
 * on {@code level.hasChunk} reads a missing chunk as "no door / no secret / no crypt / no wall". (Found by {@code /mage},
 * 2026-10-15, which captured a forgotten chunk as a hole.) {@code Level.isLoaded(BlockPos)} is NOT affected: it asks
 * the chunk source.
 *
 * <p>This asks the chunk source for the FULL chunk without loading it - one ring-buffer index on
 * {@code ClientChunkCache}, plus, with the mod's Chunk Cache on, one hash lookup in its store. So "loaded" here means
 * exactly "a block read here returns real blocks": a live chunk, or one the Chunk Cache still holds (whose blocks
 * {@code getBlockState} serves as well, see {@code ChunkCacheManager}). It costs one call per CHUNK; never call it per
 * block. On a server level it is that level's own {@code hasChunk}, which is real there.
 */
public final class ClientChunks {

    private ClientChunks() {
    }

    /** Whether chunk ({@code cx}, {@code cz}) has real blocks to read (chunk coordinates). */
    public static boolean isLoaded(Level level, int cx, int cz) {
        if (level == null) {
            return false;
        }
        if (level instanceof ClientLevel client) {
            return client.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) != null;
        }
        return level.hasChunk(cx, cz);
    }

    /**
     * Whether the server is sending this chunk NOW: vanilla's own answer ({@code ChunkSource.hasChunk}, which the
     * Chunk Cache routes past its store), so a chunk only the cache still holds is not live. For code that must not
     * read a snapshot that may be stale (the room capture).
     */
    public static boolean isLive(Level level, int cx, int cz) {
        return level != null && level.getChunkSource().hasChunk(cx, cz);
    }

    /** {@link #isLoaded(Level, int, int)} for the chunk holding block column ({@code x}, {@code z}). */
    public static boolean isLoadedAt(Level level, int x, int z) {
        return isLoaded(level, x >> 4, z >> 4);
    }

    /** Whether every chunk under the block box {@code [minX..maxX] x [minZ..maxZ]} has real blocks to read. */
    public static boolean allLoaded(Level level, int minX, int minZ, int maxX, int maxZ) {
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                if (!isLoaded(level, cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }
}
