package com.killer560.hub.autokick.mixin;

import com.killer560.hub.autokick.QueuedFloorDetector;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Auto Kick: a click on a Party Finder listing remembers that listing's floor (the party he is about to join). */
@Mixin(AbstractContainerScreen.class)
public abstract class PfJoinClickMixin {

    @Inject(method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V",
            at = @At("HEAD"), require = 0)
    private void killer560smod$pfJoinClick(Slot slot, int slotId, int button, ContainerInput type, CallbackInfo ci) {
        try {
            QueuedFloorDetector.onSlotClick((AbstractContainerScreen<?>) (Object) this, slot);
        } catch (Throwable ignored) {
            // Never let the lookup break clicking in a menu.
        }
    }
}
