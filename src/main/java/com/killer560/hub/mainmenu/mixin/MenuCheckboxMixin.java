package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import com.killer560.hub.mainmenu.MenuWidgets;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.resources.Identifier;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Checkboxes (chat/accessibility/experiments toggles, ModMenu and other mods' config screens): themed square with an
 * orange check mark when selected (light orange when hovered/focused). The label (a MultiLineTextWidget) stays neutral.
 * <p>
 * 26.1.2 (javap): Checkbox extends AbstractButton but does NOT use extractDefaultSprite; its
 * {@code extractContents(GuiGraphicsExtractor,int,int,float)} draws the box with exactly one
 * {@code GuiGraphicsExtractor.blitSprite(RenderPipeline,Identifier,int,int,int,int,int)} call at
 * (getX(), getY(), boxSize, boxSize, ARGB.white(alpha)).
 */
@Mixin(Checkbox.class)
public abstract class MenuCheckboxMixin {

    @WrapOperation(method = "extractContents(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V"),
            require = 0)
    private void killer560smod$themedCheckbox(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite,
                                              int x, int y, int width, int height, int color,
            Operation<Void> original) {
        if (MenuWidgets.active()) {
            try {
                Checkbox self = (Checkbox) (Object) this;
                MenuWidgets.drawCheckbox(graphics, x, y, Math.min(width, height), self.isActive(),
                        self.isHoveredOrFocused(), self.selected(), self.getAlpha());
                return;
            } catch (Throwable t) {
                MainMenuTheme.fail("checkbox", t);
            }
        }
        original.call(graphics, pipeline, sprite, x, y, width, height, color);
    }

    /** 26.3: the same call with RenderPipeline at its 26.3 path (Redirect takes one @At). */
    @WrapOperation(method = "extractContents(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V"),
            require = 0)
    private void killer560smod$themedCheckbox263(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite,
                                              int x, int y, int width, int height, int color,
            Operation<Void> original) {
        killer560smod$themedCheckbox(graphics, pipeline, sprite, x, y, width, height, color, original);
    }
}
