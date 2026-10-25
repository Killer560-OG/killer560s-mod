package com.killer560.hub.spotify.mixin;

import com.killer560.hub.spotify.SongTagDraw;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Song Tag: draws the song line above the name. TAIL of {@code EntityRenderer}'s final
 * {@code submitNameDisplay(S, PoseStack, SubmitNodeCollector, CameraRenderState, int)}, where vanilla submits
 * {@code scoreText} and then {@code nameTag}; the descriptor is the same on 26.1.2 and 26.2 (javap), and both a player's
 * renderer ({@code AvatarRenderer.submitNameDisplay}, with its ears offset) and a Play as Mob stand-in's renderer reach it.
 * The pose is back to where it was on entry by TAIL. Only the inner {@code submitNameTag} differs between versions, and
 * that goes through {@code McRender}. {@code require = 0}.
 */
@Mixin(EntityRenderer.class)
public abstract class SongTagSubmitMixin {

    @Inject(method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
            + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;I)V",
            at = @At("TAIL"), require = 0)
    private void killer560smod$songTag(EntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                                       CameraRenderState camera, int yOffset, CallbackInfo ci) {
        SongTagDraw.submit(state, poseStack, collector, camera, yOffset);
    }
}
