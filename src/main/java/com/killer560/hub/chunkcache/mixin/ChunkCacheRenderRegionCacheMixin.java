package com.killer560.hub.chunkcache.mixin;

import com.killer560.hub.chunkcache.ChunkCacheManager;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Second half of the "renderer keeps seeing vanilla" rule (see {@link ChunkCacheRenderSectionMixin}).
 * {@code RenderRegionCache.getSectionDataCopy} copies section data straight out of {@code Level.getChunk(x, z)} for
 * the section compiler; a section that was already compiled and then goes dirty is rebuilt without going through
 * {@code doesChunkExistAt}, so this call has to be bypassed too or a forgotten chunk would come back unlit instead
 * of disappearing like it does in vanilla.
 * <p>
 * The call lives in the {@code computeIfAbsent} lambda. It is deliberately NOT selected by the synthetic name
 * {@code lambda$getSectionDataCopy$0}: synthetic lambda names are not in the intermediary mappings, so that selector
 * resolves in a dev run and can silently match nothing in a remapped production jar - and a miss here is NOT
 * harmless. Missing this redirect leaves the section compiler reading the CACHED (lighting-disabled) chunk, i.e.
 * pitch-black far terrain; it does not fall back to vanilla. {@code method = "*"} is safe because javap on the real
 * 26.1.2 jar shows exactly one {@code Level.getChunk(II)} call site in the whole class, inside
 * {@code private static SectionCopy lambda$getSectionDataCopy$0(Level, int, int, int, long)} - {@code createRegion}
 * and {@code getSectionDataCopy} contain none - and the handler is static like that lambda.
 */
@Mixin(RenderRegionCache.class)
public abstract class ChunkCacheRenderRegionCacheMixin {

    @Redirect(method = "*",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"),
            require = 0)
    private static LevelChunk killer560smod$vanillaChunkOnly(Level level, int x, int z) {
        return ChunkCacheManager.vanillaGetChunk(level, x, z);
    }
}
