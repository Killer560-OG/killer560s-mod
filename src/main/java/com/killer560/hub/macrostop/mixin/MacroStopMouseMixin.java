package com.killer560.hub.macrostop.mixin;

import com.killer560.hub.macrostop.MacroStop;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every fresh mouse button press to {@link MacroStop}: only an Emergency Cancel Key bound to a mouse button stops a
 *  macro (a click never does since 2026-11-01), but a running Bazaar Flip still swallows clicks in its menus. Same target
 *  as the old per-feature mouse mixins: {@code private void onButton(long, MouseButtonInfo, int)} (javap 26.2 and 26.3). */
@Mixin(MouseHandler.class)
public class MacroStopMouseMixin {

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void killer560smod$macroStopOnButton(long window, MouseButtonInfo input, int action, CallbackInfo ci) {
        if (action == 1 && window == Minecraft.getInstance().getWindow().handle()
                && MacroStop.onMouseButton(com.killer560.hub.compat.McInput.button(input))) {
            ci.cancel();
        }
    }
}
