package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Themes every button on the title screen without replacing any widget (so click handlers, tooltips,
 * narration, positions and other mods' references to those Button objects are all untouched).
 * <p>
 * 26.1.2 (javap): every vanilla button draws its grey 9-slice through the one final
 * {@code AbstractButton.extractDefaultSprite} - Button$Plain (Singleplayer/Multiplayer/Realms/Options/
 * Quit and most mod-added buttons such as ModMenu's), SpriteIconButton$CenteredIcon (language /
 * accessibility) and $TextAndIcon all call it, then draw their label/icon on top. Cancelling it and
 * drawing the dark box keeps icons visible. Labels drawn through {@code extractDefaultLabel} are
 * additionally recoloured (near-white, light orange on hover). Scoped to Minecraft.screen being a
 * TitleScreen.
 */
@Mixin(AbstractButton.class)
public abstract class MainMenuButtonMixin {

    @Inject(method = "extractDefaultSprite(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$themedButtonBox(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (!MainMenuTheme.activeOnTitleScreen()) {
            return;
        }
        try {
            AbstractButton self = (AbstractButton) (Object) this;
            MainMenuTheme.drawButtonBox(graphics, self.getX(), self.getY(), self.getWidth(), self.getHeight(),
                    self.isActive(), self.isHoveredOrFocused(), self.getAlpha());
            ci.cancel();
        } catch (Throwable t) {
            MainMenuTheme.fail("button box", t);
        }
    }

    @ModifyArg(method = "extractDefaultLabel(Lnet/minecraft/client/gui/ActiveTextCollector;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/AbstractButton;extractScrollingStringOverContents(Lnet/minecraft/client/gui/ActiveTextCollector;Lnet/minecraft/network/chat/Component;I)V"),
            index = 1, require = 0)
    private Component killer560smod$themedButtonLabel(Component message) {
        if (!MainMenuTheme.activeOnTitleScreen()) {
            return message;
        }
        try {
            AbstractButton self = (AbstractButton) (Object) this;
            Component recolored = MainMenuTheme.recolorLabel(message, self.isActive(), self.isHoveredOrFocused());
            return recolored != null ? recolored : message;
        } catch (Throwable t) {
            MainMenuTheme.fail("button label", t);
            return message;
        }
    }
}
