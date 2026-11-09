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
 * The Object Hider pack's single entity cancel point, 26.3 copy; the 26.2 copy (src/mc26_2) explains the hook. 26.3's
 * {@code shouldRender} gained a trailing {@code float} (javap, 26.3:
 * {@code <E extends Entity> boolean shouldRender(E, Frustum, double, double, double, float)}, called from
 * {@code LevelExtractor}), so the handler's arguments differ and a single shared file would fail Mixin's descriptor
 * check at load on one version.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class ObjectHiderRenderMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$objectHider$shouldRender(Entity entity, Frustum frustum, double camX, double camY,
                                                        double camZ, float partialTick,
                                                        CallbackInfoReturnable<Boolean> cir) {
        if (ObjectHiderFeature.shouldHideEntity(entity) || com.killer560.hub.boss.LividSolverFeature.shouldHideEntity(entity)
                || com.killer560.hub.mobesp.GhostMobs.hidden(entity)) {
            cir.setReturnValue(false);
        }
    }
}
