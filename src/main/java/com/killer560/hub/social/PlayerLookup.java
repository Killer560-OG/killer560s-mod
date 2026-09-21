package com.killer560.hub.social;

import net.minecraft.client.Minecraft;

import java.util.UUID;

/**
 * Tiny live-presence check local to the {@code social} package: "is this UUID on my own tab list right now".
 * <p>
 * UUID&lt;-&gt;name resolution used to live here as a self-contained stub (tab-list-only, no network) because
 * the shared mod-wide resolver the task brief promised - {@code com.killer560.hub.players.PlayerNames} - did
 * not exist yet when this package was started. It landed partway through (persisted cache + tab-list scan +
 * Mojang API, with the documented "immediate cached answer, async refresh" contract), so every UUID/name
 * lookup in this package now goes through it directly instead (see {@link BestFriendsTracker},
 * {@link FriendsListConfig}, {@link FriendsListCommands}, {@link FriendsListScreen}).
 * <p>
 * What's left here - "online now" - isn't something {@code PlayerNames} does or should do: it has no notion
 * of presence at all, and this mod has no access to Hypixel's own friends/presence API. "On my own tab list
 * right now" (the same server instance as me) is the only honest signal either {@link BestFriendsScreen} or
 * {@link FriendsListScreen} can show for "online" - see both screens' doc comments for why they call it
 * "Nearby now" rather than "Online".
 */
final class PlayerLookup {

    private PlayerLookup() {
    }

    static boolean isOnlineNow(UUID id) {
        if (id == null) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        return client.getConnection() != null && client.getConnection().getPlayerInfo(id) != null;
    }
}
