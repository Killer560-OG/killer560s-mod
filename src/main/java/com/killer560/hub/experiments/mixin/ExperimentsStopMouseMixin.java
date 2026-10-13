package com.killer560.hub.experiments.mixin;

import com.killer560.hub.experiments.ExperimentsFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Auto E-Table stops on any click or scroll of his (killer560, 2026-10-13); moving the mouse is not input here.
 *  Raw, ahead of every screen, so Block Input cannot hide it. Targets verified with javap on 26.1.2 and 26.2:
 *  {@code private void onButton(long, MouseButtonInfo, int)} and {@code private void onScroll(long, double, double)}. */
@Mixin(MouseHandler.class)
public class ExperimentsStopMouseMixin {

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void killer560smod$etableStopOnButton(long window, MouseButtonInfo input, int action, CallbackInfo ci) {
        if (action == 1 && window == Minecraft.getInstance().getWindow().handle()
                && ExperimentsFeature.stopOnPlayerInput(ExperimentsFeature.mouseButtonName(input.button()))) {
            ci.cancel();
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void killer560smod$etableStopOnScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (window == Minecraft.getInstance().getWindow().handle() && ExperimentsFeature.stopOnPlayerInput("scroll wheel")) {
            ci.cancel();
        }
    }
}
