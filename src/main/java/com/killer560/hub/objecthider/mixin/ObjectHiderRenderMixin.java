package com.killer560.hub.objecthider.mixin;

import com.killer560.hub.objecthider.ObjectHiderFeature;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Object Hider pack's single entity cancel point. javap-verified against the 26.1.2 merged jar:
 * <pre>
 * public &lt;E extends Entity&gt; boolean shouldRender(E, Frustum, double, double, double);
 * </pre>
 * and {@code LevelRenderer}'s entity loop reads
 * {@code if (!entityRenderDispatcher.shouldRender(entity, frustum, camX, camY, camZ)
 *        && !entity.hasIndirectPassenger(minecraft.player)) continue;} - so returning false here skips
 * {@code extractEntity} and {@code submit} for that entity this frame and nothing else changes. The entity
 * stays in the world, keeps ticking, keeps its hitbox and stays visible to raycasts, this mod's own ESP/solver
 * entity scans and Hypixel's hit registration - which is exactly why this is used instead of Devonian's
 * {@code level.removeEntity(id, DISCARDED)}.
 * <p>
 * An entity carrying the local player is still rendered by vanilla regardless of this hook (the
 * {@code hasIndirectPassenger} clause above), which is the desired behaviour anyway.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class ObjectHiderRenderMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$objectHider$shouldRender(Entity entity, Frustum frustum, double camX, double camY,
                                                        double camZ, CallbackInfoReturnable<Boolean> cir) {
        if (ObjectHiderFeature.shouldHideEntity(entity)) {
            cir.setReturnValue(false);
        }
    }
}
