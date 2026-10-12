package com.killer560.hub.supporters.mixin;

import com.killer560.hub.supporters.PlayerNameDisplay;
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
        boolean local = entity == net.minecraft.client.Minecraft.getInstance().player;
        float factor = PlayerNameDisplay.modelScaleFor(entity.getUUID(), local);
        if (factor != 1.0f) {
            state.scale *= factor;
        }
        // The nametag follows the model (killer560, 2026-10-11: "if they are big right now it is in the middle of their
        // body if they are small it is nowhere near their head"). nameTagAttachment comes from the entity's own
        // dimensions in EntityRenderer.extractRenderState, which this TAIL runs after (javap, 26.1.2 and 26.2), and
        // the model is scaled from the feet, so scaling the attachment by the same factor puts the tag over the head at
        // any size. Your own Height stretch (CosmeticsModelShapeMixin) moves the head too. The tag's own 0.5-block lift
        // (EntityRenderer's submit adds it, unscaled) is scaled as well by adding 0.25 per unit of size, so the gap
        // above the head grows and shrinks with the player. Testkit 816 (screenshot, 5 blocks away, looking up) measures
        // the sky gap at 0.11 / 0.19 / 0.11 blocks for 0.5x / 1x / 2x; with the attachment scaled alone the 2x tag sat
        // on the head (0.01), and unscaled (the old code) it was inside the body at 2x.
        float tagY = factor * (local ? com.killer560.hub.supporters.SupportersConfig.getInstance().getModelHeight() : 1.0f);
        net.minecraft.world.phys.Vec3 att = state.nameTagAttachment;
        if (att != null && (factor != 1.0f || tagY != 1.0f)) {
            state.nameTagAttachment = new net.minecraft.world.phys.Vec3(att.x * factor,
                    att.y * tagY + 0.25 * (tagY - 1.0f), att.z * factor);
        }
    }
}
