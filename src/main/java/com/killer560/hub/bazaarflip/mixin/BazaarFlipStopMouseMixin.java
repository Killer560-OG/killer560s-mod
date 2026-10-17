package com.killer560.hub.bazaarflip.mixin;

import com.killer560.hub.bazaarflip.BazaarFlipFeature;
import com.killer560.hub.util.KeyUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A mouse button he presses while Bazaar Flip runs stops it too, wherever the cursor is - same reasoning as
 * {@link BazaarFlipStopKeyMixin}; the flipper's own clicks are packets, never mouse events. Target verified with javap
 * on 26.1.2 and 26.2: {@code private void onButton(long, MouseButtonInfo, int)}.
 */
@Mixin(MouseHandler.class)
public class BazaarFlipStopMouseMixin {

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void killer560smod$bazaarFlipStopOnButton(long window, MouseButtonInfo input, int action, CallbackInfo ci) {
        if (action == 1 && window == Minecraft.getInstance().getWindow().handle()
                && BazaarFlipFeature.onRawKeyPress(KeyUtil.codeForMouseButton(input.button()))) {
            ci.cancel();
        }
    }
}
