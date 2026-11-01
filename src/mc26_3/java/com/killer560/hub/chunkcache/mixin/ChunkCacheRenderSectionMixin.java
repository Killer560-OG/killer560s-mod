package com.killer560.hub.chunkcache.mixin;

import com.killer560.hub.chunkcache.ChunkCacheManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.SectionUpdateTracker;
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
 *
 * <b>This mixin has one copy per Minecraft version</b>, in {@code src/mc26_1/java} and
 * {@code src/mc26_2/java}, because the class it targets is not the same class on both - and a
 * {@code @Mixin} target is an annotation constant, so it cannot come from the {@code compat} facade the
 * rest of the port uses. Only one is ever compiled. <b>A change to one belongs in the other</b>, exactly as
 * for {@code compat/McCompat}: the mixin configs use {@code defaultRequire: 0}, so a copy left behind fails
 * SILENTLY and the feature simply stops running with nothing in the log.
 * <p>26.2: {@code doesChunkExistAt} left {@code SectionRenderDispatcher.RenderSection} entirely and is now
 * {@code net.minecraft.client.SectionUpdateTracker.doesChunkExistAt(ClientLevel, long)} - the level is a
 * parameter rather than a field read, but the body is the same {@code getChunk(x, z, FULL, false)} plus
 * {@code lightOnInColumn}, and the redirected INVOKE keeps its descriptor, so the handler does not change.
 * Found by grepping the 26.2 classes for {@code lightOnInColumn}, which has exactly one caller.
 */
@Mixin(SectionUpdateTracker.class)
public abstract class ChunkCacheRenderSectionMixin {

    @Redirect(method = "doesChunkExistAt",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;"),
            require = 0)
    private ChunkAccess killer560smod$vanillaChunkOnly(ClientLevel level, int x, int z, ChunkStatus status, boolean load) {
        return ChunkCacheManager.vanillaGetChunk(level, x, z, status, load);
    }
}
