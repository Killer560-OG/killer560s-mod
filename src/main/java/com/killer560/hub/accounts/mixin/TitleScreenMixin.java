package com.killer560.hub.accounts.mixin;

import com.killer560.hub.accounts.gui.AccountSwitcherScreen;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.mainmenu.MainMenuTheme;
import com.killer560.hub.mainmenu.MainMenuTitleLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
                        btn -> Minecraft.getInstance().setScreen(new AccountSwitcherScreen(self))));
                return;
            } catch (Throwable t) {
                MainMenuTheme.fail("swap accounts button", t);
                // fall through to the vanilla-mode button below
            }
        }

        User user = Minecraft.getInstance().getUser();
        String currentName = user != null ? user.getName() : "unknown";

        // One button, one line of text, one themed box (2026-09-09: converted from vanilla Button to
        // SettingsButtonWidget, per killer560's "do the same for the main menu button for swap
        // accounts") - avoids the multi-line custom rendering that turned out to be unreliable (see
        // memory: a manually-drawn line went invisible from a color missing its alpha byte). Default
        // button label rendering is proven to work.
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Swap Accounts - Playing as " + currentName),
                        btn -> Minecraft.getInstance().setScreen(new AccountSwitcherScreen(self)))
                .bounds(4, 4, 260, 20)
                .build());
    }
}
