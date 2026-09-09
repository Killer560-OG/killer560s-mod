package com.killer560.hub.screenshotcopy.mixin;

import com.killer560.hub.screenshotcopy.ScreenshotCopyFeature;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Consumer;

/** Wraps the {@code onSave} callback vanilla's own screenshot key hands to {@link Screenshot#grab} -
 *  that callback only ever runs once vanilla itself has confirmed the PNG is fully encoded and
 *  written to disk (confirmed via javap: {@code grab} chains an async GPU readback into an encode+
 *  write before invoking it), so piggybacking on it is the actual real completion signal rather than
 *  a real bug found (2026-09-08) in the first version of this feature: guessing a fixed delay after
 *  the keypress and hoping the write had finished by then, which killer560 reported "doesn't work" -
 *  most likely because the GPU readback + PNG encode for a full-resolution screenshot doesn't
 *  reliably land within that guessed window. */
@Mixin(Screenshot.class)
public abstract class ScreenshotMixin {

    @ModifyVariable(method = "grab(Ljava/io/File;Lcom/mojang/blaze3d/pipeline/RenderTarget;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"), argsOnly = true)
    private static Consumer<Component> killer560smod$wrapOnSave(Consumer<Component> onSave) {
        return onSave.andThen(ignored -> ScreenshotCopyFeature.onVanillaScreenshotSaved());
    }
}
