package com.killer560.hub.fastleap.mixin;

import com.killer560.hub.fastleap.FastLeapInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fast Leap left-click cancel + input blocking (see {@link FastLeapInput}). Targets verified with javap on the 26.1.2
 *  jar: {@code private void onButton(long, MouseButtonInfo, int)}, {@code private void onScroll(long, double, double)},
 *  {@code private void turnPlayer(double)}. Same HEAD-cancel approach as QUOI's own MouseHandlerMixin. */
@Mixin(MouseHandler.class)
public class FastLeapMouseHandlerMixin {

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$fastLeapOnButton(long window, MouseButtonInfo input, int action, CallbackInfo ci) {
        if (window != Minecraft.getInstance().getWindow().handle()) {
            return;
        }
        if (FastLeapInput.shouldCancelMouseButton(input.button(), action)) {
            ci.cancel();
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$fastLeapOnScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (window == Minecraft.getInstance().getWindow().handle() && FastLeapInput.shouldCancelScroll()) {
            ci.cancel();
        }
    }

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$fastLeapTurnPlayer(double movementTime, CallbackInfo ci) {
        if (FastLeapInput.shouldCancelTurn()) {
            ci.cancel();
        }
    }
}
