package com.killer560.hub.reforge.mixin;

import com.killer560.hub.reforge.ReforgeHelperFeature;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reforge Helper's Block When Wanted: the same funnel and injection point as
 * {@code itemprotect.mixin.ItemProtectSlotClickMixin} - {@code AbstractContainerScreen#slotClicked} at HEAD, before the
 * packet is built, so a cancelled click sends nothing. Only the player's own clicks pass here. {@code require = 0}.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ReforgeSlotClickMixin {

    @Inject(
            method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void killer560smod$reforge$blockWanted(Slot slot, int slotId, int button, ContainerInput type,
                                                   CallbackInfo ci) {
        try {
            if (ReforgeHelperFeature.shouldBlock((AbstractContainerScreen<?>) (Object) this, slotId)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // Never let the check break a menu click outright.
        }
    }
}
