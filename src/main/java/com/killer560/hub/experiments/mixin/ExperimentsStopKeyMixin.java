package com.killer560.hub.experiments.mixin;

import com.killer560.hub.experiments.ExperimentsFeature;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Auto E-Table stops on any key of his (killer560, 2026-10-13: it replaced the Emergency Cancel Key). Raw, ahead of
 *  every screen, so Block Input cannot hide it. Target verified with javap on 26.1.2 and 26.2:
 *  {@code private void keyPress(long, int, KeyEvent)}. */
@Mixin(KeyboardHandler.class)
public class ExperimentsStopKeyMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void killer560smod$etableStopOnKey(long window, int action, KeyEvent event, CallbackInfo ci) {
        // armed first: the key's display name is built per press, and nearly every press is outside a run
        if (action == 1 && ExperimentsFeature.isArmed() && window == Minecraft.getInstance().getWindow().handle()
                && ExperimentsFeature.stopOnPlayerInput("the " + InputConstants.Type.KEYSYM.getOrCreate(event.key())
                .getDisplayName().getString() + " key")) {
            ci.cancel();
        }
    }
}
