package com.killer560.hub.itemprotect.mixin;

import com.killer560.hub.itemprotect.ItemProtect;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Item Protection's "Prevent Hotbar Drops" guard, 26.3 copy. 26.3 removed {@code LocalPlayer#drop(boolean)}; the drop
 * key now runs {@code MultiPlayerGameMode#dropItem(LocalPlayer, boolean)} (javap 26.3: {@code Minecraft#handleKeybinds}
 * calls it right after reading {@code Options.keyDrop}, and nothing else does), which removes the stack from the
 * selected slot and sends the DROP_ITEM / DROP_ALL_ITEMS action. Canceling at HEAD therefore leaves the inventory
 * untouched and sends nothing, as the 26.2 copy's {@code return false} did. See the 26.2 copy for the rest.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class ItemProtectDropMixin {

    @Inject(method = "dropItem", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$blockProtectedDrop(LocalPlayer player, boolean fullStack, CallbackInfo ci) {
        try {
            if (ItemProtect.shouldBlockHotbarDrop()) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // Never let an edge case in the protection check break dropping outright.
        }
    }
}
