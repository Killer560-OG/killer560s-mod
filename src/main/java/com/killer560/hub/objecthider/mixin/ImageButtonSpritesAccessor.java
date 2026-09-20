package com.killer560.hub.objecthider.mixin;

import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.WidgetSprites;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** {@code ImageButton.sprites} is {@code protected} (javap-verified against the 26.1.2 merged jar), so
 *  "Hide Recipe Book" ({@code ObjectHiderFeature}) reaches it through an accessor to tell the recipe-book
 *  toggle button apart from every other {@link ImageButton} on an inventory screen - same {@code @Accessor}
 *  trick {@link AbstractArrowInGroundAccessor} uses. Read-only. */
@Mixin(ImageButton.class)
public interface ImageButtonSpritesAccessor {

    @Accessor("sprites")
    WidgetSprites killer560smod$getSprites();
}
