package com.killer560.hub.inventorysearch.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Real vanilla {@code AbstractContainerScreen#leftPos}/{@code #topPos} are {@code protected} with no
 *  public getters - this is a read-only accessor mixin (exposes the existing fields, injects no new
 *  behavior at all) so {@link com.killer560.hub.inventorysearch.InventorySearchFeature} can convert a
 *  real {@code Slot}'s container-relative {@code x}/{@code y} (both already public) into real screen
 *  coordinates for drawing a highlight box. Same lowest-risk accessor shape as
 *  {@code com.killer560.hub.slotbinds.mixin.AbstractContainerScreenAccessor}. */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenPositionAccessor {

    @Accessor("leftPos")
    int killer560smod$getLeftPos();

    @Accessor("topPos")
    int killer560smod$getTopPos();
}
