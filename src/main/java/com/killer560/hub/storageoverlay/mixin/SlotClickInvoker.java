package com.killer560.hub.storageoverlay.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes {@code AbstractContainerScreen}'s own real slot-click handling (real, confirmed via javap:
 *  {@code protected void slotClicked(Slot, int, int, ContainerInput)} - the same method vanilla itself
 *  calls, which does the actual menu-click logic and sends the real network packet) so a click on the
 *  relocated "Inventory" panel (per killer560's "move the inventory portion all the way to the bottom"
 *  request, 2026-09-08) can be redirected to the REAL underlying slot by index, entirely bypassing the
 *  need to fake a mouse position back over that slot's own (unmovable - {@code Slot.x}/{@code y} are
 *  {@code final}) real screen location. */
@Mixin(AbstractContainerScreen.class)
public interface SlotClickInvoker {

    @Invoker("slotClicked")
    void killer560smod$slotClicked(Slot slot, int slotId, int mouseButton, ContainerInput clickType);
}
