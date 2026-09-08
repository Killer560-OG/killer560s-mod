package com.killer560.hub.accounts.mixin;

import com.killer560.hub.accounts.PendingConnection;
import com.killer560.hub.accounts.core.HypixelBanStatus;
import com.killer560.hub.accounts.core.SharedBanStatusStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads the disconnect reason the game itself already received when the player gets kicked, and -
 * only if we were watching a Hypixel connection attempt (see ConnectScreenMixin) - checks whether
 * it looks like a ban and records it. Never sends anything, never triggers a connection itself.
 */
@Mixin(DisconnectedScreen.class)
public class DisconnectedScreenMixin {

    @Inject(method = "<init>(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;)V", at = @At("RETURN"))
    private void killer560smod$simple(Screen parent, Component title, Component reason, CallbackInfo ci) {
        handle(reason);
    }

    @Inject(method = "<init>(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;)V", at = @At("RETURN"))
    private void killer560smod$simpleWithButton(Screen parent, Component title, Component reason, Component buttonText, CallbackInfo ci) {
        handle(reason);
    }

    @Inject(method = "<init>(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("RETURN"))
    private void killer560smod$details(Screen parent, Component title, DisconnectionDetails details, CallbackInfo ci) {
        handle(details == null ? null : details.reason());
    }

    @Inject(method = "<init>(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/DisconnectionDetails;Lnet/minecraft/network/chat/Component;)V", at = @At("RETURN"))
    private void killer560smod$detailsWithButton(Screen parent, Component title, DisconnectionDetails details, Component buttonText, CallbackInfo ci) {
        handle(details == null ? null : details.reason());
    }

    private void handle(Component reason) {
        if (!PendingConnection.isPending()) {
            return;
        }
        PendingConnection.clear();
        if (reason == null) {
            return;
        }
        HypixelBanStatus status = HypixelBanStatus.fromKickMessage(reason.getString());
        if (status.status() == HypixelBanStatus.Status.BANNED) {
            User user = Minecraft.getInstance().getUser();
            if (user != null) {
                SharedBanStatusStore.recordStatus(user.getProfileId().toString(), status);
            }
        }
    }
}
