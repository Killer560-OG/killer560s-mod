package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import com.killer560.hub.mainmenu.MenuWidgets;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.LockIconButton;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The difficulty lock button next to the Difficulty cycle button (in-world Options screen). Its sprites
 * ({@code widget/locked_button*} / {@code widget/unlocked_button*}) bake the gray button frame into the icon, so it
 * cannot be themed by drawing under it; instead the themed box and a small padlock glyph are drawn.
 * <p>
 * 26.1.2 (javap): {@code LockIconButton.extractContents(GuiGraphicsExtractor,int,int,float)} (public, overrides
 * Button's) makes exactly one {@code GuiGraphicsExtractor.blitSprite(RenderPipeline,Identifier,int,int,int,int)} call
 * and never calls extractDefaultSprite.
 */
@Mixin(LockIconButton.class)
public abstract class MenuLockIconButtonMixin {

    @Redirect(method = "extractContents(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"),
            require = 0)
    private void killer560smod$themedLockButton(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite,
                                                int x, int y, int width, int height) {
        if (MenuWidgets.active()) {
            try {
                LockIconButton self = (LockIconButton) (Object) this;
                MenuWidgets.drawLockButton(graphics, x, y, width, height, self.isActive(), self.isHoveredOrFocused(),
                        self.isLocked(), self.getAlpha());
                return;
            } catch (Throwable t) {
                MainMenuTheme.fail("lock button", t);
            }
        }
        graphics.blitSprite(pipeline, sprite, x, y, width, height);
    }

    /** 26.3: the same call with RenderPipeline at its 26.3 path (Redirect takes one @At). */
    @Redirect(method = "extractContents(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"),
            require = 0)
    private void killer560smod$themedLockButton263(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite,
                                                int x, int y, int width, int height) {
        killer560smod$themedLockButton(graphics, pipeline, sprite, x, y, width, height);
    }
}
