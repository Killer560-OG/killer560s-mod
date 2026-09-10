package com.killer560.hub.accounts.mixin;

import com.killer560.hub.accounts.gui.AccountSwitcherScreen;
import com.killer560.hub.gui.SettingsButtonWidget;
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
        User user = Minecraft.getInstance().getUser();
        String currentName = user != null ? user.getName() : "unknown";

        // One button, one line of text, one themed box (2026-09-09: converted from vanilla Button to
        // SettingsButtonWidget, per killer560's "do the same for the main menu button for swap
        // accounts") - avoids the multi-line custom rendering that turned out to be unreliable (see
        // memory: a manually-drawn line went invisible from a color missing its alpha byte). Default
        // button label rendering is proven to work.
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Swap Accounts - Playing as " + currentName),
                        btn -> Minecraft.getInstance().setScreen(new AccountSwitcherScreen((TitleScreen) (Object) this)))
                .bounds(4, 4, 260, 20)
                .build());
    }
}
