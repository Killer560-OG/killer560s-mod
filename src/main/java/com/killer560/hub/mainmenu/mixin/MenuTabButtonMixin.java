package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import com.killer560.hub.mainmenu.MenuWidgets;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.TabButton;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tab buttons (Create World Game/World/More, other TabNavigationBar screens): themed tab boxes, selected tab full
 * height and warm with an orange frame, orange underline under the selected tab's title, labels recoloured.
 * <p>
 * 26.1.2 (javap), {@code TabButton.extractWidgetRenderState(GuiGraphicsExtractor,int,int,float)}:
 * <ul>
 *   <li>one {@code GuiGraphicsExtractor.blitSprite(RenderPipeline,Identifier,int,int,int,int)} - the tab sprite;</li>
 *   <li>if isSelected(): {@code this.extractMenuBackground(GuiGraphicsExtractor,int,int,int,int)} (protected, draws
 *       Screen.MENU_BACKGROUND inside the tab - cancelled while themed, the tab box already fills it) and
 *       {@code this.extractFocusUnderline(GuiGraphicsExtractor,Font,int)} (private; one
 *       {@code GuiGraphicsExtractor.fill(int,int,int,int,int)}, arg 4 = white/grey colour);</li>
 *   <li>{@code this.extractLabel(ActiveTextCollector)} (private; one
 *       {@code ActiveTextCollector.acceptScrollingWithDefaultCenter(Component,int,int,int,int)} interface call,
 *       arg 0 = getMessage()).</li>
 * </ul>
 */
@Mixin(TabButton.class)
public abstract class MenuTabButtonMixin {

    @Redirect(method = "extractWidgetRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"),
            require = 0)
    private void killer560smod$themedTab(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite,
                                         int x, int y, int width, int height) {
        if (MenuWidgets.active()) {
            try {
                TabButton self = (TabButton) (Object) this;
                MenuWidgets.drawTab(graphics, x, y, width, height, self.isActive(), self.isSelected(), self.isHoveredOrFocused());
                return;
            } catch (Throwable t) {
                MainMenuTheme.fail("tab", t);
            }
        }
        graphics.blitSprite(pipeline, sprite, x, y, width, height);
    }

    @Inject(method = "extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIII)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$themedTabBackground(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, CallbackInfo ci) {
        if (MenuWidgets.active()) {
            ci.cancel();
        }
    }

    @ModifyArg(method = "extractFocusUnderline(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"),
            index = 4, require = 0)
    private int killer560smod$themedTabUnderline(int color) {
        if (!MenuWidgets.active()) {
            return color;
        }
        try {
            TabButton self = (TabButton) (Object) this;
            if (!self.isActive()) {
                return MenuWidgets.BORDER_DISABLED;
            }
            return self.isHoveredOrFocused() ? MenuWidgets.ACCENT_LIGHT : MenuWidgets.ACCENT;
        } catch (Throwable t) {
            MainMenuTheme.fail("tab underline", t);
            return color;
        }
    }

    @ModifyArg(method = "extractLabel(Lnet/minecraft/client/gui/ActiveTextCollector;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/ActiveTextCollector;acceptScrollingWithDefaultCenter(Lnet/minecraft/network/chat/Component;IIII)V"),
            index = 0, require = 0)
    private Component killer560smod$themedTabLabel(Component message) {
        if (!MenuWidgets.active()) {
            return message;
        }
        try {
            TabButton self = (TabButton) (Object) this;
            Component recolored = MainMenuTheme.recolorLabel(message, self.isActive(),
                    self.isSelected() || self.isHoveredOrFocused());
            return recolored != null ? recolored : message;
        } catch (Throwable t) {
            MainMenuTheme.fail("tab label", t);
            return message;
        }
    }
}
