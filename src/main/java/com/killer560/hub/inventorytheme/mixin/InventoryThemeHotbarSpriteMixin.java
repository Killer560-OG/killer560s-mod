package com.killer560.hub.inventorytheme.mixin;

import com.killer560.hub.inventorytheme.HotbarTheme;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops vanilla's four hotbar background sprites ({@code hud/hotbar}, {@code hud/hotbar_selection},
 * {@code hud/hotbar_offhand_left/right}) while - and only while - {@link HotbarTheme} is running vanilla's own hotbar
 * layer under its themed background. Everything else that layer draws (the items with their pickup pop, counts,
 * durability bars, cooldowns, and the hotbar attack indicator, whose sprites have other names) still comes from vanilla.
 * <p>
 * Target verified with javap on both versions: {@code GuiGraphicsExtractor.blitSprite(RenderPipeline, Identifier, int,
 * int, int, int)} is what {@code Gui.extractItemHotbar} (26.1.2) and {@code Hud.extractItemHotbar} (26.2) call for all
 * four, with identical arguments. The class itself is the same on both, so one mixin covers both versions.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class InventoryThemeHotbarSpriteMixin {

    @Inject(method = "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V",
            at = @At("HEAD"), cancellable = true)
    private void killer560smod$dropHotbarSprite(RenderPipeline pipeline, Identifier sprite, int x, int y, int width,
                                               int height, CallbackInfo ci) {
        // One static boolean read on every sprite blit of every frame when the theme is not running.
        if (HotbarTheme.suppressingSprites && HotbarTheme.isHotbarSprite(sprite)) {
            ci.cancel();
        }
    }
}
