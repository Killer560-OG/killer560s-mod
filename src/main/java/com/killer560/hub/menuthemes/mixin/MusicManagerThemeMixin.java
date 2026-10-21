package com.killer560.hub.menuthemes.mixin;

import com.killer560.hub.menuthemes.ThemeMusic;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla's menu music waits while a theme track plays (it is stopped when the track starts), so the two never overlap.
 *  {@code MusicManager.tick()} is the one place it picks and starts a song (javap 26.1.2). */
@Mixin(MusicManager.class)
public abstract class MusicManagerThemeMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void killer560smod$themeMusic(CallbackInfo ci) {
        if (ThemeMusic.playing()) {
            ci.cancel();
        }
    }
}
