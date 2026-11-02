package com.killer560.hub.pathfinding.mixin;

import com.killer560.hub.pathfinding.AutoWalker;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Auto Fairy Souls' Start/Stop key, raw (a fresh press, action 1). Everything else that stops it - a movement key,
 *  Escape, chat, the Emergency Cancel Key - is {@code macrostop/MacroStop}'s since 2026-11-01; until then this mixin
 *  stopped it on any key. Never canceled: the key does what it always does. Same target as {@code MacroStopKeyMixin}
 *  (javap 26.2 and 26.3): {@code private void keyPress(long, int, KeyEvent)}. */
@Mixin(KeyboardHandler.class)
public class PathStopKeyMixin {

    @Inject(method = "keyPress", at = @At("HEAD"))
    private void killer560smod$fairyStartKey(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (action == 1 && window == Minecraft.getInstance().getWindow().handle()) {
            AutoWalker.onPlayerInput(com.killer560.hub.compat.McInput.key(event));
        }
    }
}
