package com.killer560.hub.supporters.mixin;

import com.killer560.hub.supporters.SupportersFeature;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Scale half of item 8.5. {@code AvatarRenderer} is 26.1.2's player-model renderer - javap-confirmed
 * (against the real merged jar) to be the {@code LivingEntityRenderer<Avatar & ClientAvatarEntity,
 * AvatarRenderState, PlayerModel>} the game actually uses for player entities (the older
 * "PlayerRenderer" name is gone in this version). {@code extractRenderState(Avatar, AvatarRenderState,
 * float)} is where that per-frame render-state object's own {@code scale} field - declared on the shared
 * {@code LivingEntityRenderState} base, the SAME field vanilla's own baby/age scaling multiplies into - gets
 * populated; injecting at {@code TAIL} and multiplying it there is the least invasive scale hook available,
 * since the later {@code scale(AvatarRenderState, PoseStack)} pose-stack transform (never touched here) just
 * reads that field back out unchanged.
 * <p>
 * Purely a transient render-state number, rebuilt from scratch every frame from the live entity: the
 * entity's actual hitbox/dimensions/collision are never touched, so this cannot be seen by the server or
 * change combat - visual only, exactly as required. {@code require = 0}: a wrong target here only leaves
 * supporters at normal scale, never a crash.
 */
@Mixin(AvatarRenderer.class)
public abstract class SupportersScaleMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;"
            + "Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"), require = 0)
    private void killer560smod$supportersScale(Avatar entity, AvatarRenderState state, float partialTick,
                                                CallbackInfo ci) {
        float factor = SupportersFeature.scaleFor(entity.getUUID());
        if (factor != 1.0f) {
            state.scale *= factor;
        }
    }
}
