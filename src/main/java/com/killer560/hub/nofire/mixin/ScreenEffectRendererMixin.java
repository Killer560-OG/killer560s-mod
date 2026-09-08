package com.killer560.hub.nofire.mixin;

import com.killer560.hub.nofire.NoFireConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cancels the full-screen fire overlay draw when "No Fire" is enabled - verified via javap against
 *  the real 26.1.2 jar that {@code ScreenEffectRenderer.renderScreenEffect} calls this private static
 *  {@code renderFire} method only when {@code player.isOnFire()}, so cancelling it here leaves the
 *  water/portal/confusion/pumpkin overlays (and the actual on-fire game state/damage) untouched. */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {

    @Inject(method = "renderFire", at = @At("HEAD"), cancellable = true)
    private static void killer560smod$cancelFireOverlay(PoseStack poseStack, MultiBufferSource bufferSource,
                                                          TextureAtlasSprite sprite, CallbackInfo ci) {
        if (NoFireConfig.getInstance().isEnabled()) {
            ci.cancel();
        }
    }
}
