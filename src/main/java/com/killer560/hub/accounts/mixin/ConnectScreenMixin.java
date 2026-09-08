package com.killer560.hub.accounts.mixin;

import com.killer560.hub.accounts.PendingConnection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;

/**
 * Records whether the player is manually connecting to Hypixel, so DisconnectedScreenMixin and
 * HypixelJoinWatcher know to pay attention to what happens next. Never initiates a connection
 * itself - only observes ones the player started.
 */
@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {

    @Inject(method = "startConnecting", at = @At("HEAD"))
    private static void killer560smod$onStartConnecting(Screen parent, Minecraft minecraft, ServerAddress serverAddress,
            ServerData serverData, boolean quickPlay, TransferState transferState, CallbackInfo ci) {
        String host = serverAddress == null ? null : serverAddress.getHost();
        if (host != null && host.toLowerCase(Locale.US).contains("hypixel.net")) {
            PendingConnection.markPending();
        } else {
            PendingConnection.clear();
        }
    }
}
