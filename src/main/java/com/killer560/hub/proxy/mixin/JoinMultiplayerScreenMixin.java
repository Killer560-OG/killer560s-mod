package com.killer560.hub.proxy.mixin;

import com.killer560.hub.proxy.gui.ProxyConfigScreen;
import com.killer560.hub.proxy.gui.ProxyToggleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a "Proxy: Enabled/Disabled" toggle button to the top-right of the
 * multiplayer server-list screen. Clicking it opens {@link ProxyConfigScreen}.
 *
 * <p>Declared {@code abstract} and extending {@link Screen} purely so the mixin
 * can see inherited members ({@code width}, {@code minecraft},
 * {@code addRenderableWidget}). The class is never actually instantiated; the
 * constructor exists only to satisfy the compiler, since {@link Screen} has no
 * no-arg constructor.
 */
@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {

    protected JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void killer560smod$addProxyButton(CallbackInfo ci) {
        int buttonWidth = 110;
        int x = this.width - buttonWidth - 5;
        int y = 5;
        this.addRenderableWidget(ProxyToggleButton.create(x, y, buttonWidth, 20,
                b -> this.minecraft.setScreen(new ProxyConfigScreen(this))));
    }
}
