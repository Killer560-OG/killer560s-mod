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
 * Themes every button on the title screen and - while "Themed Menus" is on - every other non-container menu
 * (multiplayer, options and all sub-option screens, world select / create world, pause menu, ModMenu and
 * other mods' config screens), without replacing any widget (so click handlers, tooltips, narration,
 * positions and other mods' references to those Button objects are all untouched).
 * <p>
 * 26.1.2 (javap): every vanilla button draws its grey 9-slice through the one final
 * {@code AbstractButton.extractDefaultSprite} - Button$Plain (almost every menu button, incl. ModMenu's and
 * most mod-added ones), CycleButton (option toggles, when it has no custom SpriteSupplier) and
 * SpriteIconButton$CenteredIcon / $TextAndIcon (language / accessibility icons) all call it, then draw their
 * label/icon on top. Cancelling it and drawing the dark box keeps icons visible. Labels drawn through
 * {@code extractDefaultLabel} (Button$Plain, CycleButton) are additionally recoloured (near-white, light
 * orange on hover, dim when inactive); explicit colours inside a label (red warnings, vanilla's grey
 * inactive message from WithInactiveMessage.defaultInactiveMessage) still win - see
 * {@link MainMenuTheme#recolorLabel}.
 * <p>
 * Widgets that bypass extractDefaultSprite are covered elsewhere: Checkbox (MenuCheckboxMixin),
 * LockIconButton (MenuLockIconButtonMixin), SpriteIconButton$TextAndIcon's label (MenuTextAndIconButtonMixin).
 * ImageButton subclasses (recipe book, social interactions, stat sort) are custom art and stay vanilla.
 * The mod's own GUI widgets (SettingsButtonWidget etc.) extend AbstractWidget, not AbstractButton, so they
 * are never touched here. Scope: {@link MainMenuTheme#activeOnMenus()}.
 */
@Mixin(AbstractButton.class)
public abstract class MainMenuButtonMixin {

    @Inject(method = "extractDefaultSprite(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$themedButtonBox(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (!MainMenuTheme.activeOnMenus()) {
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
        if (!MainMenuTheme.activeOnMenus()) {
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
