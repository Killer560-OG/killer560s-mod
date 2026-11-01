package com.killer560.hub.heldoutline.mixin;

import com.killer560.hub.heldoutline.HeldOutlineRenderer;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Held Item Outline hooks on {@code GameRenderer}, 26.3 copy (javap, 26.3):
 * <ul>
 * <li>{@code renderItemInHand(CameraRenderState, PlayerRenderState, GpuTextureView depth)} HEAD. 26.3 builds the hand
 * pose from {@code inverse(camera.viewRotationMatrix)} and multiplies {@code camera.viewRotationMatrix} onto the
 * model-view stack around the hands, so that matrix is the hand view (26.2 passed it in as the third argument).</li>
 * <li>{@code render3dHud(CameraRenderState, PlayerRenderState, OptionsRenderState, boolean)} just before
 * {@code ScreenEffectRenderer.submit(float, SubmitNodeCollector, PlayerRenderState, CameraRenderState, boolean)}: 26.3
 * moved the hands out of {@code renderLevel} into {@code render3dHud}, which calls {@code renderItemInHand} (whose
 * prepared frame is closed by then) and then that submit. The hud projection is set at that point, as on 26.2.</li>
 * </ul>
 */
@Mixin(GameRenderer.class)
public abstract class HeldOutlineGameRendererMixin {

    @Inject(method = "renderItemInHand", at = @At("HEAD"), require = 0)
    private void killer560smod$heldOutlineBegin(CameraRenderState camera, PlayerRenderState player,
                                                GpuTextureView depth, CallbackInfo ci) {
        HeldOutlineRenderer.beginHands(camera.viewRotationMatrix);
    }

    @Inject(method = "render3dHud", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;submit(FLnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Z)V"),
            require = 0)
    private void killer560smod$heldOutlineAfterHands(CallbackInfo ci) {
        HeldOutlineRenderer.afterHands();
    }
}
