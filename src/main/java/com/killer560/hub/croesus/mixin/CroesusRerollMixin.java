package com.killer560.hub.croesus.mixin;

import com.killer560.hub.croesus.CroesusExtras;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reroll Protection (2026-10-25). {@code AbstractContainerScreen#slotClicked(Slot, int, int, ContainerInput)} is the one
 * funnel every click he makes in a container goes through before the packet is built (a click, a number key, a drop key
 * over the slot) - the same target Item Protection's guard uses. Canceling at HEAD sends nothing. Auto Croesus clicks
 * through {@code MultiPlayerGameMode#handleContainerInput} directly, so it never passes here.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class CroesusRerollMixin {

    @Inject(method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$rerollProtection(Slot slot, int slotId, int button, ContainerInput type, CallbackInfo ci) {
        try {
            if (CroesusExtras.shouldBlockSlotClick((AbstractContainerScreen<?>) (Object) this, slot, slotId, type)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // Never let the check break clicking in a menu.
        }
    }
}
