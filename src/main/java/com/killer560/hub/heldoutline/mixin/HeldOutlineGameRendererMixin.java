package com.killer560.hub.heldoutline.mixin;

import com.killer560.hub.heldoutline.HeldOutlineRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Held Item Outline hooks on {@code GameRenderer} (javap, 26.1.2 and 26.2):
 * <ul>
 * <li>{@code renderItemInHand(CameraRenderState, float, Matrix4fc)} HEAD - identical descriptor in both versions; the
 * {@code Matrix4fc} is what it multiplies onto the model-view stack around the hands.</li>
 * <li>{@code renderLevel(DeltaTracker)} just before the screen effect is submitted, which follows
 * {@code renderItemInHand} directly in both: 26.1.2 calls {@code ScreenEffectRenderer.renderScreenEffect}, 26.2 renamed
 * it {@code submit} with the same descriptor, so there is one injector per name and exactly one of them matches.</li>
 * </ul>
 */
@Mixin(GameRenderer.class)
public abstract class HeldOutlineGameRendererMixin {

    @Inject(method = "renderItemInHand", at = @At("HEAD"), require = 0)
    private void killer560smod$heldOutlineBegin(CameraRenderState camera, float partialTick, Matrix4fc view,
                                                CallbackInfo ci) {
        HeldOutlineRenderer.beginHands(view);
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;renderScreenEffect(ZZFLnet/minecraft/client/renderer/SubmitNodeCollector;Z)V"),
            require = 0)
    private void killer560smod$heldOutlineAfterHands(DeltaTracker deltaTracker, CallbackInfo ci) {
        HeldOutlineRenderer.afterHands();
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;submit(ZZFLnet/minecraft/client/renderer/SubmitNodeCollector;Z)V"),
            require = 0)
    private void killer560smod$heldOutlineAfterHands262(DeltaTracker deltaTracker, CallbackInfo ci) {
        HeldOutlineRenderer.afterHands();
    }
}
