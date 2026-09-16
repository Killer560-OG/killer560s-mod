package com.killer560.hub.chunkcache;

/** Implemented on {@code net.minecraft.client.multiplayer.ClientChunkCache} by
 *  {@link com.killer560.hub.chunkcache.mixin.ChunkCacheClientChunkCacheMixin}, so each world's chunk source carries
 *  its own {@link ChunkCacheStore} and the cache dies with the world it belongs to. */
public interface ChunkCacheHolder {

    ChunkCacheStore killer560smod$chunkCacheStore();
}
