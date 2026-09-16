package com.killer560.hub.itemprotect.mixin;

import com.killer560.hub.itemprotect.ItemProtect;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Item Protection's "Prevent Hotbar Drops" guard. {@code LocalPlayer#drop(boolean)} is the client-only drop
 * path: verified with javap against {@code minecraft-merged-043a8b3edf-26.1.2.jar} that
 * {@code net.minecraft.client.player.LocalPlayer} declares {@code public boolean drop(boolean)} (the shared
 * {@code Player} class has no such method - only {@code drop(ItemStack, boolean)}), and that
 * {@code Minecraft#handleKeybinds} calls it exactly once, right after reading {@code Options.keyDrop}. So
 * cancelling here swallows the real drop key and nothing else; no server-driven item loss passes through.
 * <p>
 * Returning {@code false} is what vanilla itself returns when a drop doesn't happen, so no packet is sent
 * and no swing is played. The block is always announced in chat with a sound, and Confirm To Force lets a
 * second press inside 3 seconds through (see {@link ItemProtect#shouldBlockHotbarDrop}).
 * <p>
 * {@code require = 0}: a signature change after an MC update disables the guard rather than crashing.
 */
@Mixin(LocalPlayer.class)
public abstract class ItemProtectDropMixin {

    @Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$blockProtectedDrop(boolean fullStack, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (ItemProtect.shouldBlockHotbarDrop()) {
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) {
            // Never let an edge case in the protection check break dropping outright.
        }
    }
}
