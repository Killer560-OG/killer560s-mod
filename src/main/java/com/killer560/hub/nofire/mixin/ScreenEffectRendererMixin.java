package com.killer560.hub.nofire.mixin;

import com.killer560.hub.nofire.NoFireConfig;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels the full-screen fire overlay draw when "No Fire" is enabled - verified via javap against the real
 * jars that the private static method this cancels is called only when {@code player.isOnFire()}, so cancelling
 * it here leaves the water/portal/confusion/pumpkin overlays (and the actual on-fire game state and damage)
 * untouched.
 *
 * <p><b>Two target names on purpose.</b> 26.1.2 calls it {@code renderFire(PoseStack, MultiBufferSource,
 * TextureAtlasSprite)}; 26.2 renamed the whole class's draw path to submits and it is
 * {@code submitFire(PoseStack, SubmitNodeCollector, TextureAtlasSprite)}. Both names are listed so the one
 * present is matched, and {@code require = 1} makes it an error for NEITHER to match rather than the silent
 * no-op {@code defaultRequire: 0} would otherwise give - the failure mode where the feature just never runs and
 * nothing in the log says so.
 *
 * <p><b>Why the handler takes only {@code CallbackInfo}.</b> The second parameter's TYPE differs between the
 * versions ({@code MultiBufferSource} does not exist in 26.2 at all), so a handler mirroring the target's
 * arguments cannot compile on both. Mixin permits a callback that captures no arguments: checked in
 * {@code CallbackInjector$Callback.checkDescriptor} in sponge-mixin 0.17.3, which accepts either the full
 * descriptor or {@code Target.getSimpleCallbackDescriptor()} - literally {@code (LCallbackInfo;)V} - and simply
 * sets {@code captureArgs = false}. It is all-or-nothing, not a prefix: a handler taking just the
 * {@code PoseStack} plus {@code CallbackInfo} would NOT match. This cancel needs none of the arguments anyway.
 */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {

    @Inject(method = {"renderFire", "submitFire"}, at = @At("HEAD"), cancellable = true, require = 1)
    private static void killer560smod$cancelFireOverlay(CallbackInfo ci) {
        if (NoFireConfig.getInstance().isEnabled()) {
            ci.cancel();
        }
    }
}
