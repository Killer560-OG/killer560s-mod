package com.killer560.hub.inventorytheme.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Real vanilla {@code AbstractContainerScreen#leftPos}/{@code #topPos}/{@code #imageWidth}/
 *  {@code #imageHeight}/{@code #titleLabelX}/{@code #titleLabelY}/{@code #inventoryLabelX}/
 *  {@code #inventoryLabelY}/{@code #playerInventoryTitle} are all {@code protected} with no public
 *  getters - a read-only accessor mixin (exposes the existing fields, injects no new behavior) so
 *  {@link com.killer560.hub.inventorytheme.InventoryThemeFeature} can place the themed panel/slot
 *  backdrops/highlight/labels at the exact same screen positions vanilla's own layout already computed.
 *  Same lowest-risk accessor shape already used by
 *  {@code com.killer560.hub.slotbinds.mixin.AbstractContainerScreenAccessor} and
 *  {@code com.killer560.hub.inventorysearch.mixin.ContainerScreenPositionAccessor} - a separate local
 *  copy rather than importing theirs, matching this repo's existing per-feature accessor convention.
 *  <p>
 *  Deliberately mixed into {@code AbstractContainerScreen} only, even though
 *  {@code InventoryThemeInventoryBackgroundMixin}/{@code InventoryThemeInventoryLabelsMixin} target the
 *  concrete {@code InventoryScreen} subclass: an interface mixin applied to an ancestor class is
 *  inherited by every subclass instance at the normal JVM level, so casting a real
 *  {@code InventoryScreen} to this interface works exactly the same as casting a {@code ContainerScreen}
 *  - and avoids ever needing {@code @Shadow} to reach a superclass-declared field from a mixin whose
 *  {@code @Mixin} target is the subclass, which is the less-certain of the two approaches. */
@Mixin(AbstractContainerScreen.class)
public interface InventoryThemeGeometryAccessor {

    @Accessor("leftPos")
    int killer560smod$getLeftPos();

    @Accessor("topPos")
    int killer560smod$getTopPos();

    @Accessor("imageWidth")
    int killer560smod$getImageWidth();

    @Accessor("imageHeight")
    int killer560smod$getImageHeight();

    @Accessor("titleLabelX")
    int killer560smod$getTitleLabelX();

    @Accessor("titleLabelY")
    int killer560smod$getTitleLabelY();

    @Accessor("inventoryLabelX")
    int killer560smod$getInventoryLabelX();

    @Accessor("inventoryLabelY")
    int killer560smod$getInventoryLabelY();

    @Accessor("playerInventoryTitle")
    Component killer560smod$getPlayerInventoryTitle();
}
