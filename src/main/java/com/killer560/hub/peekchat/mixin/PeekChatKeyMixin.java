package com.killer560.hub.peekchat.mixin;

import com.killer560.hub.peekchat.PeekChatFeature;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Peek Chat's keys while a peek is shown: Escape closes it (no pause menu), Page Up / Page Down / Home / End scroll
 *  ({@link PeekChatFeature#onKey}); every other key reaches the game. {@code private void keyPress(long, int, KeyEvent)}
 *  (javap, 26.3 and 26.2). */
@Mixin(KeyboardHandler.class)
public abstract class PeekChatKeyMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$peekKey(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (window == Minecraft.getInstance().getWindow().handle() && PeekChatFeature.onKey(event.key(), action)) {
            ci.cancel();
        }
    }
}
