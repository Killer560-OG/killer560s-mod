package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import com.killer560.hub.mainmenu.MenuWidgets;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * SpriteIconButton$TextAndIcon gets its box from extractDefaultSprite (MainMenuButtonMixin) but draws its label itself
 * rather than through extractDefaultLabel. 26.1.2 (javap): {@code SpriteIconButton$TextAndIcon.extractContents(
 * GuiGraphicsExtractor,int,int,float)} has exactly one {@code ActiveTextCollector.acceptScrolling(Component,int,int,
 * int,int,int)} interface call, whose arg 0 is getMessage(). Recoloured the same way as ordinary button labels.
 */
@Mixin(SpriteIconButton.TextAndIcon.class)
public abstract class MenuTextAndIconButtonMixin {

    @ModifyArg(method = "extractContents(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/ActiveTextCollector;acceptScrolling(Lnet/minecraft/network/chat/Component;IIIII)V"),
            index = 0, require = 0)
    private Component killer560smod$themedTextAndIconLabel(Component message) {
        if (!MenuWidgets.active()) {
            return message;
        }
        try {
            SpriteIconButton self = (SpriteIconButton) (Object) this;
            Component recolored = MainMenuTheme.recolorLabel(message, self.isActive(), self.isHoveredOrFocused());
            return recolored != null ? recolored : message;
        } catch (Throwable t) {
            MainMenuTheme.fail("icon button label", t);
            return message;
        }
    }
}
