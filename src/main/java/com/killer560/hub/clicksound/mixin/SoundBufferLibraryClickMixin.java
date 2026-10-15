package com.killer560.hub.clicksound.mixin;

import com.killer560.hub.clicksound.ClickSoundBuffers;
import com.mojang.blaze3d.audio.SoundBuffer;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/** Answers the sound engine's buffer request for an imported click sound from the import folder. */
@Mixin(SoundBufferLibrary.class)
public abstract class SoundBufferLibraryClickMixin {

    @Inject(method = "getCompleteBuffer", at = @At("HEAD"), cancellable = true)
    private void killer560smod$importedClick(Identifier id, CallbackInfoReturnable<CompletableFuture<SoundBuffer>> cir) {
        if (ClickSoundBuffers.isImported(id)) {
            cir.setReturnValue(ClickSoundBuffers.bufferFor(id));
        }
    }
}
