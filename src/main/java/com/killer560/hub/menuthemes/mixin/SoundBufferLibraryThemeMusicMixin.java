package com.killer560.hub.menuthemes.mixin;

import com.killer560.hub.menuthemes.ThemeMusicFiles;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/** Answers the sound engine's stream request for imported theme music from the music folder ({@link ThemeMusicFiles}).
 *  {@code getStream(Identifier, boolean)} is the same on 26.1.2 and 26.2 (javap). */
@Mixin(SoundBufferLibrary.class)
public abstract class SoundBufferLibraryThemeMusicMixin {

    @Inject(method = "getStream", at = @At("HEAD"), cancellable = true)
    private void killer560smod$importedThemeMusic(Identifier id, boolean looping,
                                                  CallbackInfoReturnable<CompletableFuture<AudioStream>> cir) {
        if (ThemeMusicFiles.isImported(id)) {
            cir.setReturnValue(ThemeMusicFiles.stream(id, looping));
        }
    }
}
