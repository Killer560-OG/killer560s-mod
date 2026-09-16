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
 * {@code ClientChunkCache$Storage.replace} and {@code .drop}, both of which take the chunk out of vanilla's storage
 * array <em>before</em> calling this) starts with {@code chunk.clearAllBlockEntities()}, which marks every block
 * entity removed and empties the chunk's map - so without this, a cached chunk would still have its blocks but would
 * answer null for every chest/skull/sign in it.
 * <p>
 * HEAD marks the chunk as "the one being evicted into the cache", which is what makes
 * {@link ChunkCacheLevelChunkMixin} skip the block-entity half of {@code clearAllBlockEntities} - the map is then
 * never mutated at all, so nothing can race with the worker threads that read cached chunks. RETURN un-registers
 * those block entities from the level's off-screen render set, and restores the map by hand only in the fallback
 * case where that redirect did not apply. Both halves run on the client thread (packet handling), which is the only
 * place vanilla unloads chunks from.
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
