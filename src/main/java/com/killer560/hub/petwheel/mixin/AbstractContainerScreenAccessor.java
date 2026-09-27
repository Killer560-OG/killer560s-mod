package com.killer560.hub.petwheel.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Real vanilla {@code AbstractContainerScreen#hoveredSlot} is {@code protected} with no public getter -
 *  this is a read-only accessor mixin (exposes the existing field, injects no new behavior at all) so
 *  {@link com.killer560.hub.petwheel.PetWheelEditor} can read which real {@code /pets} slot the mouse is
 *  over when it hijacks the player's own click for the wheel's edit mode. Same lowest-risk accessor shape
 *  as {@code com.killer560.hub.slotbinds.mixin.AbstractContainerScreenAccessor} /
 *  {@code com.killer560.hub.experiments.mixin.AbstractContainerScreenAccessor} - its own copy rather than
 *  importing either of those (each feature package stays independent of another feature's mixin config). */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {

    @Accessor("hoveredSlot")
    Slot killer560smod$getHoveredSlot();
}
