package com.killer560.hub.clicksound.mixin;

import com.killer560.hub.clicksound.ClickSounds;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Every button click is a {@code minecraft:ui.button.click} instance handed to {@code SoundManager.play}; swap it here. */
@Mixin(SoundManager.class)
public abstract class SoundManagerClickMixin {

    @ModifyVariable(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;",
            at = @At("HEAD"), argsOnly = true)
    private SoundInstance killer560smod$clickSound(SoundInstance instance) {
        return ClickSounds.replace(instance);
    }
}
