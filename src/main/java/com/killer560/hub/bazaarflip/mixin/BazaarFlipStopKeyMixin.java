package com.killer560.hub.bazaarflip.mixin;

import com.killer560.hub.bazaarflip.BazaarFlipFeature;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Any key he presses while Bazaar Flip runs stops it (killer560, 2026-10-16: "right now only escape stopped it").
 * Raw, at the head of {@code KeyboardHandler.keyPress}, ahead of every screen - the Bazaar menus, the reskin over
 * them, the Custom Amount sign editor and the world alike - the same place Auto E-Table's stop key sits. The amount
 * the flipper types into the sign goes straight to the screen's {@code charTyped}, never through here, so it can never
 * stop itself. Target verified with javap on 26.1.2 and 26.2: {@code private void keyPress(long, int, KeyEvent)}.
 */
@Mixin(KeyboardHandler.class)
public class BazaarFlipStopKeyMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void killer560smod$bazaarFlipStopOnKey(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (action == 1 && window == Minecraft.getInstance().getWindow().handle()
                && BazaarFlipFeature.onRawKeyPress(event.key())) {
            ci.cancel();
        }
    }
}
