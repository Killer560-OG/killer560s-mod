package com.killer560.hub.macrostop.mixin;

import com.killer560.hub.macrostop.MacroStop;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every fresh key press, raw, ahead of every screen, to {@link MacroStop} (Escape, the Emergency Cancel Key, and for a
 *  movement macro a movement or chat key). Took over from the per-feature ExperimentsStopKeyMixin and
 *  BazaarFlipStopKeyMixin on 2026-11-01, same target: {@code private void keyPress(long, int, KeyEvent)} (javap 26.2
 *  and 26.3). The code is {@code McInput.key}, the stored (GLFW) number, never the raw event key (SDL on 26.3). */
@Mixin(KeyboardHandler.class)
public class MacroStopKeyMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void killer560smod$macroStopOnKey(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (action == 1 && window == Minecraft.getInstance().getWindow().handle()
                && MacroStop.onKey(event, com.killer560.hub.compat.McInput.key(event))) {
            ci.cancel();
        }
    }
}
