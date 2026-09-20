package com.killer560.hub.chunkcache.mixin;

import com.killer560.hub.chunkcache.ChunkCacheManager;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Light bookkeeping guard. {@code handleLevelChunkWithLight} queues a light update that later looks the chunk up
 * again with {@code getChunk(x, z, false)} and calls {@code enableChunkLight} on whatever it finds. If the chunk was
 * dropped in between (it happens on teleports), that lookup now finds the CACHED chunk, and vanilla would happily
 * re-enable lighting and mark sections dirty for a chunk nothing else considers loaded.
 * <p>
 * Cancelling it for a non-live chunk keeps the light engine's state exactly as it is without the Chunk Cache. When
 * the feature is off this never fires at all.
 */
@Mixin(ClientPacketListener.class)
public abstract class ChunkCachePacketListenerMixin {

    @Inject(method = "enableChunkLight", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$skipLightForCachedChunk(LevelChunk chunk, int x, int z, CallbackInfo ci) {
        try {
            if (ChunkCacheManager.isActive() && !ChunkCacheManager.isLive(chunk)) {
                ci.cancel();
            }
        } catch (RuntimeException e) {
            // Reached from handleLevelChunkWithLight: an exception here reaches
            // ClientCommonPacketListenerImpl.onPacketError, which disconnects the client with
            // disconnect.packetError ("Network Protocol Error"). Fail closed and let vanilla light the chunk.
            LoggerFactory.getLogger("killer560smod-chunkcache")
                    .error("[ChunkCache] enableChunkLight hook threw", e);
        }
    }
}
