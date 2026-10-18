package com.killer560.hub.mobskin.mixin;

import com.killer560.hub.mobskin.MobSkinRender;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Play as Mob's one swap point. javap, 26.1.2 and 26.2 alike: {@code public <E extends Entity> EntityRenderState
 * extractEntity(E, float)} is {@code getRenderer(entity).createRenderState(entity, partialTick)} (one {@code areturn}),
 * and {@code LevelRenderer.extractEntity} adds whatever it returns to the frame's {@code entityRenderStates}; the later
 * {@code submit(EntityRenderState, ...)} picks the renderer from the STATE ({@code getRenderer(S)}: by
 * {@code state.entityType}, players by skin model), so returning a mob's state here draws the mob with the mob's renderer.
 * At RETURN, so every player-state mixin (sizes, AP3's F5 yaw, names) has already run on the state that is copied from.
 * {@code require = 0}: a wrong target only leaves everyone drawn as a player.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class MobSkinDispatcherMixin {

    @Inject(method = "extractEntity(Lnet/minecraft/world/entity/Entity;F)"
            + "Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private void killer560smod$playAsMob(Entity entity, float partialTick, CallbackInfoReturnable<EntityRenderState> cir) {
        EntityRenderState original = cir.getReturnValue();
        EntityRenderState swapped = MobSkinRender.swap((EntityRenderDispatcher) (Object) this, entity, original, partialTick);
        if (swapped != original) {
            cir.setReturnValue(swapped);
        }
    }
}
