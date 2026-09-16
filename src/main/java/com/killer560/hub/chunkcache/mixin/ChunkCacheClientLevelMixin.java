package com.killer560.hub.chunkcache.mixin;

import com.killer560.hub.chunkcache.ChunkCacheManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps a cached chunk's block entities alive. 26.1.2's {@code ClientLevel.unload(LevelChunk)} (called from
 * {@code ClientChunkCache$Storage.replace} and {@code .drop}) starts with {@code chunk.clearAllBlockEntities()},
 * which marks every block entity removed and empties the chunk's map - so without this, a cached chunk would still
 * have its blocks but would answer null for every chest/skull/sign in it.
 * <p>
 * The map is snapshotted at HEAD and restored at RETURN, with each block entity un-removed but NOT re-registered
 * (no tickers), so nothing in an evicted chunk ticks or renders. Both halves run on the client thread (packet
 * handling), which is the only place vanilla unloads chunks from.
 */
@Mixin(ClientLevel.class)
public abstract class ChunkCacheClientLevelMixin {

    @Inject(method = "unload(Lnet/minecraft/world/level/chunk/LevelChunk;)V", at = @At("HEAD"), require = 0)
    private void killer560smod$captureBlockEntities(LevelChunk chunk, CallbackInfo ci) {
        ChunkCacheManager.beforeUnload(chunk);
    }

    @Inject(method = "unload(Lnet/minecraft/world/level/chunk/LevelChunk;)V", at = @At("RETURN"), require = 0)
    private void killer560smod$restoreBlockEntities(LevelChunk chunk, CallbackInfo ci) {
        ChunkCacheManager.afterUnload(chunk);
    }
}
