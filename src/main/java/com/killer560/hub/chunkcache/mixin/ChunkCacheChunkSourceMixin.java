package com.killer560.hub.chunkcache.mixin;

import com.killer560.hub.chunkcache.ChunkCacheManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps "is this chunk loaded?" a vanilla question. javap on 26.1.2 shows
 * {@code ChunkSource.hasChunk(x, z)} is {@code getChunk(x, z, FULL, false) != null} (and {@code ClientChunkCache}
 * does not override it), which {@code Level.isLoaded(BlockPos)} funnels straight into - so without this redirect the
 * Chunk Cache would silently answer "loaded" for every chunk this client has ever seen, changing vanilla decisions
 * (block updates, entity bookkeeping, anything gated on {@code isLoaded}) far from the player.
 * <p>
 * The mod's own features never lost anything by this: the handful that deliberately want "loaded, or cached" ask
 * {@link ChunkCacheManager#isLoadedOrCached} instead. Block reads ({@code getBlockState}, {@code getBlockEntity},
 * heightmaps) still see cached chunks - only the loaded-ness answer is vanilla again.
 */
@Mixin(ChunkSource.class)
public abstract class ChunkCacheChunkSourceMixin {

    @Redirect(method = "hasChunk",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkSource;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;"),
            require = 0)
    private ChunkAccess killer560smod$vanillaChunkOnly(ChunkSource source, int x, int z, ChunkStatus status, boolean load) {
        return ChunkCacheManager.vanillaGetChunk(source, x, z, status, load);
    }
}
