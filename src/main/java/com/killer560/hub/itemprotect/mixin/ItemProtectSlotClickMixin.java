package com.killer560.hub.itemprotect.mixin;

import com.killer560.hub.itemprotect.ItemProtect;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Item Protection's container guard. {@code AbstractContainerScreen#slotClicked(Slot, int, int,
 * ContainerInput)} is the single funnel every CLIENT-initiated container interaction goes through before
 * the packet is built - a plain click, shift-click, number-key swap, middle-click clone, Q-throw over a
 * slot, an outside click that drops the carried stack, and each step of a quick-craft drag. Cancelling at
 * HEAD means no packet is ever sent, so the client and server never disagree about where an item is.
 * <p>
 * Nothing the server does passes through here, so this can't silently swallow a server-side move - the
 * explicit rule for this feature. Every cancel also sends a chat line and plays a note, see
 * {@link ItemProtect#announceBlock}.
 * <p>
 * Verified with javap against {@code minecraft-merged-043a8b3edf-26.1.2.jar}: {@code protected void
 * slotClicked(net.minecraft.world.inventory.Slot, int, int, net.minecraft.world.inventory.ContainerInput)}
 * exists on {@code AbstractContainerScreen} and is called from 22 sites inside that class (its mouse and
 * keyboard handlers). {@code require = 0} so a signature change after an MC update just disables the guard
 * instead of crashing the game.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ItemProtectSlotClickMixin {

    @Inject(
            method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void killer560smod$blockProtectedSlotClick(Slot slot, int slotId, int button, ContainerInput type,
                                                       CallbackInfo ci) {
        try {
            if (ItemProtect.shouldBlockSlotClick((AbstractContainerScreen<?>) (Object) this, slot, slotId, button, type)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // Never let an edge case in the protection check break inventory interaction outright.
        }
    }
}
