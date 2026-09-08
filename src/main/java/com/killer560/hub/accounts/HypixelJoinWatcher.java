package com.killer560.hub.accounts;

import com.killer560.hub.accounts.core.HypixelBanStatus;
import com.killer560.hub.accounts.core.SharedBanStatusStore;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ServerData;

import java.util.Locale;

/**
 * Confirms "not banned" the other way around from DisconnectedScreenMixin: if a Hypixel connection
 * attempt actually succeeds (the client reaches Play state on that server) without ever hitting a
 * ban-looking disconnect, that's positive evidence the account isn't banned right now.
 */
public final class HypixelJoinWatcher {

    private HypixelJoinWatcher() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(HypixelJoinWatcher::onTick);
    }

    private static void onTick(Minecraft minecraft) {
        if (!PendingConnection.isPending()) {
            return;
        }
        ServerData server = minecraft.getCurrentServer();
        if (server == null || server.ip == null) {
            return;
        }
        if (!server.ip.toLowerCase(Locale.US).contains("hypixel.net")) {
            return;
        }
        PendingConnection.clear();
        User user = minecraft.getUser();
        if (user != null) {
            SharedBanStatusStore.recordStatus(user.getProfileId().toString(), HypixelBanStatus.notBanned());
        }
    }
}
