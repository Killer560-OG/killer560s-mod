package com.killer560.hub.clicksound.mixin;

import com.killer560.hub.clicksound.ClickSounds;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every button click is a {@code minecraft:ui.button.click} instance handed to {@code SoundManager.play}, and every Sound
 * Effect a game sound handed to it (or to {@code playDelayed}, a far sound with a distance delay). {@link ClickSounds#replace}
 * picks what plays: the same instance (nothing changes), another one (the game's is cancelled and that one is played
 * instead, through this same method, where replace hands it back untouched) or null (the game's is cancelled and nothing
 * plays). The game's instance never reaches the sound engine once it has been replaced.
 */
@Mixin(SoundManager.class)
public abstract class SoundManagerClickMixin {

    @Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;",
            at = @At("HEAD"), cancellable = true)
    private void killer560smod$clickSound(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        SoundInstance out = ClickSounds.replace(instance);
        if (out == instance) {
            return;
        }
        cir.setReturnValue(out == null ? SoundEngine.PlayResult.NOT_STARTED : ((SoundManager) (Object) this).play(out));
    }

    @Inject(method = "playDelayed(Lnet/minecraft/client/resources/sounds/SoundInstance;I)V", at = @At("HEAD"),
            cancellable = true)
    private void killer560smod$delayedSound(SoundInstance instance, int delay, CallbackInfo ci) {
        SoundInstance out = ClickSounds.replace(instance);
        if (out == instance) {
            return;
        }
        ci.cancel();
        if (out != null) {
            ((SoundManager) (Object) this).playDelayed(out, delay);
        }
    }
}
