package com.killer560.hub.slotbinds.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Real vanilla {@code AbstractContainerScreen#hoveredSlot} is {@code protected} with no public getter -
 *  this is a read-only accessor mixin (exposes the existing field, injects no new behavior at all) so
 *  {@link com.killer560.hub.slotbinds.SlotBindsFeature} can read which slot the mouse is over. The
 *  lowest-risk real category of mixin this mod uses. */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {

    @Accessor("hoveredSlot")
    Slot killer560smod$getHoveredSlot();
}
