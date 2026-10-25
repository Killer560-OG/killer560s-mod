package com.killer560.hub.leftclickew.mixin;

import com.killer560.hub.leftclickew.LeftClickEtherwarp;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Left Click Etherwarp: sneak in this tick's input while a click waits for it (the same rewrite as
 * {@code livemap.mixin.LiveMapKeyboardInputMixin}). A discrete key state, never a fractional input; the client crouches
 * exactly as if he pressed sneak, so his own movement and the server's agree.
 */
@Mixin(KeyboardInput.class)
public abstract class LeftClickEtherwarpInputMixin {

    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void killer560smod$leftClickEwSneak(CallbackInfo ci) {
        if (!LeftClickEtherwarp.forceSneak()) {
            return;
        }
        ClientInput self = (ClientInput) (Object) this;
        Input k = self.keyPresses;
        if (!k.shift()) {
            self.keyPresses = new Input(k.forward(), k.backward(), k.left(), k.right(), k.jump(), true, k.sprint());
        }
    }
}
