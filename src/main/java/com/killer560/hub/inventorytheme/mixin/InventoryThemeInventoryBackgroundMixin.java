package com.killer560.hub.inventorytheme.mixin;

import com.killer560.hub.inventorytheme.InventoryThemeFeature;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Same idea as {@link InventoryThemeContainerBackgroundMixin}, but for the player's own survival
 *  inventory screen ('e'). {@code InventoryScreen.extractBackground} does three things (javap, 26.1.2 and
 *  26.2, identical): the super call, ONE {@code blit} of the inventory texture, then
 *  {@code extractEntityInInventoryFollowsMouse} for the player model. Only the texture is replaced, by
 *  redirecting that blit; the old HEAD-cancel of the whole method also threw away the player model, which
 *  left an empty hole in the themed panel. {@code require = 0} like every other injection here. */
@Mixin(InventoryScreen.class)
public abstract class InventoryThemeInventoryBackgroundMixin {

    @Redirect(
            method = "extractBackground",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V"),
            require = 0
    )
    private void killer560smod$themeInventoryBackground(GuiGraphicsExtractor graphics, RenderPipeline pipeline,
                                                          Identifier texture, int x, int y, float u, float v,
                                                          int width, int height, int texWidth, int texHeight) {
        InventoryScreen self = (InventoryScreen) (Object) this;
        if (InventoryThemeFeature.shouldTheme(self)) {
            InventoryThemeFeature.drawBackground(graphics, self);
        } else {
            graphics.blit(pipeline, texture, x, y, u, v, width, height, texWidth, texHeight);
        }
    }
}
