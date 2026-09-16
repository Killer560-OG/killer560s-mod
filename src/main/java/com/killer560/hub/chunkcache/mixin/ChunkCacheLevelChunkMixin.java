package com.killer560.hub.chunkcache.mixin;

import com.killer560.hub.chunkcache.ChunkCacheManager;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The two halves of "a cached chunk is inert": nothing in it registers itself with the level, and its block-entity
 * map is never mutated behind a reader's back. Both are scoped by {@link ChunkCacheManager#isCacheOnly} /
 * {@link ChunkCacheManager#keepsBlockEntities}, so a chunk vanilla still owns - and every chunk on a singleplayer
 * server - behaves exactly as it does without this mod.
 *
 * <h2>1. No tickers, no renderer registration (26.1.2 bytecode)</h2>
 * {@code getBlockEntity(pos, IMMEDIATE)} ends in {@code createBlockEntity} -> {@code addAndRegisterBlockEntity}, and
 * that method's gate is {@code isInLevel()}, which javap shows as {@code loaded || level.isClientSide()} - always
 * true on the client, evicted chunk or not. So any {@code level.getBlockEntity(...)} at a block-entity position
 * inside a cached chunk used to (a) register a {@code BoundTickingBlockEntity} in {@code tickersInLevel} and
 * {@code Level.blockEntityTickers}, and (b) call {@code ClientLevel.onBlockEntityAdded}, which adds
 * {@code shouldRenderOffScreen} block entities (beacons, end gateways) to {@code globallyRenderedBlockEntities} -
 * a set {@code LevelRenderer} draws every frame until the entry reports {@code isRemoved()}.
 * <p>
 * Cancelling {@code addAndRegisterBlockEntity} for a cache-only chunk drops both: the caller still gets the freshly
 * created block entity back (vanilla returns it either way), it is simply never stored, never ticked and never
 * rendered. Nothing is written to the chunk's map, so this stays safe on the worker threads the pathfinder and the
 * solvers read from.
 *
 * <h2>2. Block entities survive eviction without touching the map</h2>
 * {@code ClientLevel.unload} is {@code chunk.clearAllBlockEntities()} + {@code setLightEnabled(false)} +
 * {@code entityStorage.stopTicking()}. javap on {@code clearAllBlockEntities} shows four calls in this order:
 * {@code blockEntities.values().forEach(BlockEntity::setRemoved)} (Collection.forEach ordinal 0),
 * {@code blockEntities.clear()} (Map.clear ordinal 0), {@code tickersInLevel.values().forEach(w -> w.rebind(NULL_TICKER))}
 * (ordinal 1), {@code tickersInLevel.clear()} (ordinal 1).
 * <p>
 * Skipping only the two ordinal-0 calls for a chunk the cache owns leaves the block-entity map <em>untouched</em> -
 * no clear, no re-insert, nothing for a worker thread to race with - while the ticker half runs exactly as vanilla
 * wrote it, so an evicted chunk still stops ticking. The older snapshot/restore path in {@link ChunkCacheManager}
 * remains only as a fallback for the case where these redirects fail to apply.
 */
@Mixin(LevelChunk.class)
public abstract class ChunkCacheLevelChunkMixin {

    @Inject(method = "addAndRegisterBlockEntity", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$noRegistrationInCachedChunks(BlockEntity blockEntity, CallbackInfo ci) {
        LevelChunk self = (LevelChunk) (Object) this;
        if (!ChunkCacheManager.isCacheOnly(self)) {
            return;
        }
        // javap: the method's first call is setBlockEntity, which is what gives a freshly created block entity its
        // level before the registration half runs. The caller (getBlockEntity/promotePendingBlockEntity) is handed
        // that object back either way, so it must not go out with a null level - plenty of BlockEntity methods
        // dereference it. Only the level is set: the chunk's map is still never written, which is the whole point.
        if (blockEntity != null && !blockEntity.hasLevel()) {
            blockEntity.setLevel(self.getLevel());
        }
        ci.cancel();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(method = "clearAllBlockEntities",
            at = @At(value = "INVOKE", target = "Ljava/util/Collection;forEach(Ljava/util/function/Consumer;)V", ordinal = 0),
            require = 0)
    private void killer560smod$keepBlockEntitiesUnremoved(Collection values, Consumer action) {
        if (!ChunkCacheManager.keepsBlockEntities((LevelChunk) (Object) this, false)) {
            values.forEach(action);
        }
    }

    @SuppressWarnings("rawtypes")
    @Redirect(method = "clearAllBlockEntities",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;clear()V", ordinal = 0),
            require = 0)
    private void killer560smod$keepBlockEntityMap(Map map) {
        if (!ChunkCacheManager.keepsBlockEntities((LevelChunk) (Object) this, true)) {
            map.clear();
        }
    }
}
