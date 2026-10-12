package com.killer560.hub.hud.mixin;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The recipe book's search box (private {@code searchBox}, javap 26.1.2 and 26.2), for util/TypingDetector: the
 *  component's own {@code isFocused()} always answers false. */
@Mixin(RecipeBookComponent.class)
public interface RecipeBookSearchAccessor {
    @Accessor("searchBox")
    EditBox killer560smod$getSearchBox();
}
