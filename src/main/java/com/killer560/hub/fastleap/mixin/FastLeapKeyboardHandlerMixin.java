package com.killer560.hub.fastleap.mixin;

import com.killer560.hub.fastleap.FastLeapInput;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fast Leap / I4 Leap key blocking (see {@link FastLeapInput}). Target verified with javap on the 26.1.2 jar:
 *  {@code private void keyPress(long, int, KeyEvent)}. */
@Mixin(KeyboardHandler.class)
public class FastLeapKeyboardHandlerMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$fastLeapKeyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (window == Minecraft.getInstance().getWindow().handle() && FastLeapInput.shouldCancelKey(event, action)) {
            ci.cancel();
        }
    }
}
