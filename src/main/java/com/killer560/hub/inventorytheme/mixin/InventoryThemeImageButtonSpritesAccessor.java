package com.killer560.hub.inventorytheme.mixin;

import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.WidgetSprites;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only accessor for {@code ImageButton}'s protected {@code sprites} field - so
 *  {@link com.killer560.hub.inventorytheme.InventoryThemeFeature#register()} can tell the recipe-book
 *  toggle button apart from every other {@link ImageButton} on a screen. Same trick
 *  {@code com.killer560.hub.objecthider.mixin.ImageButtonSpritesAccessor} already uses for its own
 *  "Hide Recipe Book Button" option - a separate local copy rather than importing theirs, matching this
 *  repo's existing per-feature accessor convention (see {@link InventoryThemeGeometryAccessor}). */
@Mixin(ImageButton.class)
public interface InventoryThemeImageButtonSpritesAccessor {

    @Accessor("sprites")
    WidgetSprites killer560smod$getSprites();
}
