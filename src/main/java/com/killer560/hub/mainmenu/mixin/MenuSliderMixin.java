package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import com.killer560.hub.mainmenu.MenuWidgets;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Sliders (FOV, volumes, render distance, GUI scale, mouse sensitivity, other mods' option sliders): themed track
 * box with a warm filled strip up to the handle, an orange knob (light orange while hovered/focused), and the
 * label recolored like buttons.
 * <p>
 * 26.1.2 (javap): {@code AbstractSliderButton.extractWidgetRenderState(GuiGraphicsExtractor,int,int,float)} makes
 * exactly two {@code GuiGraphicsExtractor.blitSprite(RenderPipeline,Identifier,int,int,int,int,int)} calls - ordinal 0
 * the track (x, y, width, height, ARGB.white(alpha)), ordinal 1 the handle (x + value*(width-8), y, 8, height, ...) -
 * then {@code this.extractScrollingStringOverContents(ActiveTextCollector,Component,int)} (owner AbstractSliderButton
 * in the constant pool) for the label. OptionInstance sliders (via AbstractOptionSliderButton) inherit it unchanged.
 * The mod's own {@code gui.ThemedSliderButton} overrides extractWidgetRenderState without calling super, so none of
 * these injections run for it (no double theming).
 */
@Mixin(AbstractSliderButton.class)
public abstract class MenuSliderMixin {

    @Shadow
    protected double value;

    @WrapOperation(method = "extractWidgetRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V", ordinal = 0),
            require = 0)
    private void killer560smod$themedSliderTrack(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite,
                                                 int x, int y, int width, int height, int color,
            Operation<Void> original) {
        if (MenuWidgets.active()) {
            try {
                AbstractSliderButton self = (AbstractSliderButton) (Object) this;
                MenuWidgets.drawSliderTrack(graphics, x, y, width, height, self.isActive(), self.isHoveredOrFocused(),
                        self.getAlpha(), this.value, 8);
                return;
            } catch (Throwable t) {
                MainMenuTheme.fail("slider track", t);
            }
        }
        original.call(graphics, pipeline, sprite, x, y, width, height, color);
    }

    /** 26.3: the same call with RenderPipeline at its 26.3 path (Redirect takes one @At). */
    @WrapOperation(method = "extractWidgetRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V", ordinal = 0),
            require = 0)
    private void killer560smod$themedSliderTrack263(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite,
                                                 int x, int y, int width, int height, int color,
            Operation<Void> original) {
        killer560smod$themedSliderTrack(graphics, pipeline, sprite, x, y, width, height, color, original);
    }

    @WrapOperation(method = "extractWidgetRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V", ordinal = 1),
            require = 0)
    private void killer560smod$themedSliderHandle(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite,
                                                  int x, int y, int width, int height, int color,
            Operation<Void> original) {
        if (MenuWidgets.active()) {
            try {
                AbstractSliderButton self = (AbstractSliderButton) (Object) this;
                MenuWidgets.drawSliderHandle(graphics, x, y, width, height, self.isActive(), self.isHoveredOrFocused(),
                        self.getAlpha());
                return;
            } catch (Throwable t) {
                MainMenuTheme.fail("slider handle", t);
            }
        }
        original.call(graphics, pipeline, sprite, x, y, width, height, color);
    }

    /** 26.3: the same call with RenderPipeline at its 26.3 path (Redirect takes one @At). */
    @WrapOperation(method = "extractWidgetRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V", ordinal = 1),
            require = 0)
    private void killer560smod$themedSliderHandle263(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite,
                                                  int x, int y, int width, int height, int color,
            Operation<Void> original) {
        killer560smod$themedSliderHandle(graphics, pipeline, sprite, x, y, width, height, color, original);
    }

    @ModifyArg(method = "extractWidgetRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/AbstractSliderButton;extractScrollingStringOverContents(Lnet/minecraft/client/gui/ActiveTextCollector;Lnet/minecraft/network/chat/Component;I)V"),
            index = 1, require = 0)
    private Component killer560smod$themedSliderLabel(Component message) {
        if (!MenuWidgets.active()) {
            return message;
        }
        try {
            AbstractSliderButton self = (AbstractSliderButton) (Object) this;
            Component recolored = MainMenuTheme.recolorLabel(message, self.isActive(), self.isHoveredOrFocused());
            return recolored != null ? recolored : message;
        } catch (Throwable t) {
            MainMenuTheme.fail("slider label", t);
            return message;
        }
    }
}
