package com.killer560.hub.pathfinding.mixin;

import com.killer560.hub.pathfinding.AutoWalker;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Auto Fairy Souls stops on any key he presses (killer560, 2026-10-17: "only if they press a key have it stop"). Only a
 *  fresh press counts (action 1), so a key he was already holding when it started is not one. The Start/Stop key and
 *  presses under a screen are left out in {@link AutoWalker#onPlayerInput}. The perspective key (F5 by default) is left
 *  out too (killer560, 2026-10-18: it changes the view, it does not take the walker from him). Never cancelled: the key
 *  does what it always does. Same target as {@code ExperimentsStopKeyMixin} (javap 26.1.2 and 26.2):
 *  {@code private void keyPress(long, int, KeyEvent)}. */
@Mixin(KeyboardHandler.class)
public class PathStopKeyMixin {

    @Inject(method = "keyPress", at = @At("HEAD"))
    private void killer560smod$fairyStopOnKey(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (action == 1 && window == Minecraft.getInstance().getWindow().handle()
                && !Minecraft.getInstance().options.keyTogglePerspective.matches(event)) {
            // The key's name is only for the stop line: spared on every press outside a session.
            AutoWalker.onPlayerInput(!AutoWalker.isSessionActive() ? ""
                    : "the " + InputConstants.Type.KEYSYM.getOrCreate(event.key()).getDisplayName().getString()
                    + " key", event.key());
        }
    }
}
