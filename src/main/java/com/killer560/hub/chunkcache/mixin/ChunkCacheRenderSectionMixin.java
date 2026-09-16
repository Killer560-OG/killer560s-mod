package com.killer560.hub.chunkcache.mixin;

import com.killer560.hub.chunkcache.ChunkCacheManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps the terrain renderer on vanilla's answer. {@code RenderSection.doesChunkExistAt(long)} gates section
 * building on {@code level.getChunk(x, z, FULL, false) != null && lightEngine.lightOnInColumn(...)}; a cached chunk
 * has had its lighting disabled by {@code ClientLevel.unload}, so letting the renderer see it would mean rebuilding
 * far sections as pitch-black terrain. Routing this one call through a bypassing lookup keeps the rendered world
 * byte-for-byte what it is without the Chunk Cache, while every non-render world read still gets the cached chunk.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class ChunkCacheRenderSectionMixin {

    @Redirect(method = "doesChunkExistAt",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;"),
            require = 0)
    private ChunkAccess killer560smod$vanillaChunkOnly(ClientLevel level, int x, int z, ChunkStatus status, boolean load) {
        return ChunkCacheManager.vanillaGetChunk(level, x, z, status, load);
    }
}
