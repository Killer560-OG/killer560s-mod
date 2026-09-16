package com.killer560.hub.chunkcache.mixin;

import com.killer560.hub.chunkcache.ChunkCacheManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps collision a vanilla question. javap on 26.1.2 shows {@code Level.getChunkForCollisions(x, z)} is exactly
 * {@code getChunk(x, z, FULL, false)} (one call site), and {@code BlockCollisions} skips a column whose chunk comes
 * back null - that null is how vanilla decides an entity or particle simply passes through un-loaded terrain.
 * <p>
 * With the Chunk Cache serving that lookup, entities and particles at the edge of the client's storage ring would
 * start colliding with terrain the client is only remembering. Bypassing the cache here keeps physics identical to
 * a run without this feature; no mod code calls {@code getChunkForCollisions}.
 */
@Mixin(Level.class)
public abstract class ChunkCacheLevelMixin {

    @Redirect(method = "getChunkForCollisions",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;"),
            require = 0)
    private ChunkAccess killer560smod$vanillaChunkOnly(Level level, int x, int z, ChunkStatus status, boolean load) {
        return ChunkCacheManager.vanillaGetChunk(level, x, z, status, load);
    }
}
