package com.killer560.hub.chunkcache.mixin;

import com.killer560.hub.chunkcache.ChunkCacheHolder;
import com.killer560.hub.chunkcache.ChunkCacheManager;
import com.killer560.hub.chunkcache.ChunkCacheStore;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Chunk Cache core (see {@link ChunkCacheManager}).
 * <ul>
 * <li>{@code <init>} gives every world's chunk source its own {@link ChunkCacheStore}, so the cache lives and dies
 * with the {@code ClientLevel} - nothing can survive an island switch, a dungeon, a respawn or a disconnect.
 * <li>{@code replaceWithPacketData} records the chunk object the server just applied, so the cache always holds the
 * newest copy of a position and can never shadow a live chunk with a stale one.
 * <li>{@code getChunk(II Lnet/minecraft/world/level/chunk/status/ChunkStatus;Z)} is injected at its two "not loaded"
 * returns only (ordinal 1 = the shared empty chunk when {@code load}, ordinal 2 = null), so the normal hit path is
 * untouched and no callback object is even allocated for it. Verified against 26.1.2 bytecode: the method has exactly
 * three ARETURNs - 42 (the live chunk), 52 (emptyChunk), 54 (null).
 * </ul>
 * Only {@code ChunkStatus.FULL} is served from the cache: {@code ChunkSource.getChunkForLighting} asks for
 * {@code EMPTY}, and the light engine must keep seeing exactly what vanilla sees.
 */
@Mixin(ClientChunkCache.class)
public abstract class ChunkCacheClientChunkCacheMixin implements ChunkCacheHolder {

    /** Volatile: {@code getChunk} (and therefore this field) is read from worker threads - see
     *  {@link ChunkCacheStore}'s class javadoc - while the write happens on the client thread inside {@code <init>}.
     *  Without it a worker can observe the default null after the constructor has already run. */
    @Unique
    private volatile ChunkCacheStore killer560smod$store;

    @Override
    public ChunkCacheStore killer560smod$chunkCacheStore() {
        return killer560smod$store;
    }

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void killer560smod$createStore(ClientLevel level, int viewDistance, CallbackInfo ci) {
        killer560smod$store = new ChunkCacheStore();
    }

    @SuppressWarnings("rawtypes")
    @Inject(method = "replaceWithPacketData", at = @At("RETURN"), require = 0)
    private void killer560smod$rememberChunk(int x, int z, FriendlyByteBuf buffer, Map heightmaps, Consumer consumer,
                                             CallbackInfoReturnable<LevelChunk> cir) {
        ChunkCacheManager.onChunkReceived(killer560smod$store, x, z, cir.getReturnValue());
    }

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;",
            at = {@At(value = "RETURN", ordinal = 1), @At(value = "RETURN", ordinal = 2)},
            cancellable = true, require = 0)
    private void killer560smod$serveCachedChunk(int x, int z, ChunkStatus status, boolean load,
                                                CallbackInfoReturnable<LevelChunk> cir) {
        if (status != ChunkStatus.FULL || !ChunkCacheManager.isActive() || ChunkCacheManager.isBypassed()) {
            return;
        }
        ChunkCacheStore store = killer560smod$store;
        if (store == null) {
            return;
        }
        LevelChunk cached = store.get(ChunkPos.pack(x, z));
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }
}
