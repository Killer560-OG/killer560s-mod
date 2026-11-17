package com.killer560.hub.accounts.mixin;

import com.killer560.hub.accounts.gui.AccountSwitcherScreen;
import com.killer560.hub.mainmenu.MainMenuTheme;
import com.killer560.hub.mainmenu.MainMenuTitleLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.killer560.hub.compat.McCompat;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init()V", at = @At("TAIL"))
    private void killer560smod$addSwapAccountsButton(CallbackInfo ci) {
        TitleScreen self = (TitleScreen) (Object) this;

        // Themed main menu (2026-09-15): a plain vanilla Button (themed by MainMenuButtonMixin like the other
        // column buttons). MainMenuTitleLayout moves it into the main column, shortens the label to "Swap
        // Accounts" and shows "Playing as <name>" under the Options/Quit row, from a ScreenEvents.AFTER_INIT
        // listener that runs after this TAIL and after ModMenu. It starts at the old top-left bounds/label.
        boolean themed = false;
        try {
            themed = MainMenuTheme.active();
        } catch (Throwable t) {
            MainMenuTheme.fail("swap accounts button", t);
        }
        if (themed) {
            try {
                this.addRenderableWidget(MainMenuTitleLayout.createSwapAccountsButton(
                        btn -> McCompat.setScreen(Minecraft.getInstance(), new AccountSwitcherScreen(self))));
                return;
            } catch (Throwable t) {
                MainMenuTheme.fail("swap accounts button", t);
            }
        }
    }
}
