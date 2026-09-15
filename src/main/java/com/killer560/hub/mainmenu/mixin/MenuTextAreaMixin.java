package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import com.killer560.hub.mainmenu.MenuWidgets;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractTextAreaWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Multi-line text areas (MultiLineEditBox - report comments, dialog text inputs; FittingMultiLineTextWidget with a
 * background; telemetry event list) share the {@code widget/text_field} sprites with EditBox. 26.1.2 (javap):
 * {@code AbstractTextAreaWidget.extractBorder(GuiGraphicsExtractor,int,int,int,int)} is protected, non-final, and
 * does nothing but blit that sprite (called from extractBackground); no vanilla subclass overrides it.
 */
@Mixin(AbstractTextAreaWidget.class)
public abstract class MenuTextAreaMixin {

    @Inject(method = "extractBorder(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIII)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$themedTextAreaBorder(GuiGraphicsExtractor graphics, int x, int y, int width, int height, CallbackInfo ci) {
        if (!MenuWidgets.active()) {
            return;
        }
        try {
            AbstractTextAreaWidget self = (AbstractTextAreaWidget) (Object) this;
            MenuWidgets.drawTextField(graphics, x, y, width, height, self.isActive(), self.isFocused());
            ci.cancel();
        } catch (Throwable t) {
            MainMenuTheme.fail("text area", t);
        }
    }
}
