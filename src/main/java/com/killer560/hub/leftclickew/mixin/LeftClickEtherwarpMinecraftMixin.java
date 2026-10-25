package com.killer560.hub.leftclickew.mixin;

import com.killer560.hub.leftclickew.LeftClickEtherwarp;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Left Click Etherwarp's hooks in {@code Minecraft} (javap 26.1.2 and 26.2: {@code private boolean startAttack()},
 * {@code private void continueAttack(boolean)}, {@code private void handleKeybinds()}). handleKeybinds reads the attack
 * clicks ({@code startAttack}), then the use clicks, then {@code continueAttack} for a held button - all before the
 * player's tick sends this tick's input and movement packets.
 */
@Mixin(Minecraft.class)
public abstract class LeftClickEtherwarpMinecraftMixin {

    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void killer560smod$leftClickEwPending(CallbackInfo ci) {
        LeftClickEtherwarp.beforeKeybinds((Minecraft) (Object) this);
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void killer560smod$leftClickEw(CallbackInfoReturnable<Boolean> cir) {
        if (LeftClickEtherwarp.onLeftClick((Minecraft) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void killer560smod$leftClickEwHeld(boolean leftClick, CallbackInfo ci) {
        if (LeftClickEtherwarp.suppressHeldAttack((Minecraft) (Object) this, leftClick)) {
            ci.cancel();
        }
    }
}
