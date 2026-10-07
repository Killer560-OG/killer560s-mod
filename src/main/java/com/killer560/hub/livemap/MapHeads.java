package com.killer560.hub.livemap;

import com.killer560.hub.util.ChatObserver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a map marker's skin comes from when Player Heads is on (killer560, 2026-10-07). Only ever the server's own
 * word - no session-server fetch, so nothing leaves the client for this:
 * <ol>
 *   <li>yourself: your own {@link PlayerInfo} (by UUID), which the server always sends;</li>
 *   <li>a teammate: the {@link PlayerInfo} the server sent for that exact player name (a real player, v4 UUID). On
 *       Hypixel that entry exists whether or not the teammate is in render distance, because the server lists every
 *       player in the instance;</li>
 *   <li>else Hypixel's dungeon tab list: the fake entry whose text is {@code [lvl] (rank )Name ...} carries the
 *       teammate's face for the tab list. Taken only when that entry has a {@code textures} property - a fake
 *       entry without one would give a random default Steve/Alex for the fake UUID, which is not their skin;</li>
 *   <li>else null, and the painter draws the ordinary arrow for that player.</li>
 * </ol>
 * Lookups by name are cached for a second (the map draws every frame; the tab list is ~80 entries).
 */
final class MapHeads {

    /** {@code [42] [MVP+] Name ...} - name group bounded, as everything parsing other players' text must be. */
    private static final Pattern TAB_NAME = Pattern.compile("^\\[\\d{1,4}] (?:\\[[^]]{1,20}] )*([A-Za-z0-9_]{1,16})(?: .*)?$");
    private static final int CACHE_TICKS = 20;

    private record Cached(PlayerInfo info, int tick) {
    }

    private static final Map<String, Cached> CACHE = new HashMap<>();
    private static ClientPacketListener cachedFor;

    private MapHeads() {
    }

    /** The skin to draw for this marker, or null when none is known (draw the arrow). Render thread. */
    static PlayerSkin skinFor(InteractiveMapFeature.MapPlayer mp) {
        Minecraft client = Minecraft.getInstance();
        ClientPacketListener connection = client.getConnection();
        if (connection == null) {
            return null;
        }
        PlayerInfo info;
        if (mp.self()) {
            info = client.player == null ? null : connection.getPlayerInfo(client.player.getUUID());
        } else {
            info = teammateInfo(connection, mp.name());
        }
        return info == null ? null : info.getSkin();
    }

    private static PlayerInfo teammateInfo(ClientPacketListener connection, String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        if (connection != cachedFor) {
            cachedFor = connection;
            CACHE.clear();
        }
        int now = LiveMapFeature.tickCount();
        String key = name.toLowerCase(Locale.US);
        Cached hit = CACHE.get(key);
        if (hit != null && now - hit.tick() >= 0 && now - hit.tick() < CACHE_TICKS) {
            return hit.info();
        }
        PlayerInfo found = resolve(connection, name);
        CACHE.put(key, new Cached(found, now));
        return found;
    }

    private static PlayerInfo resolve(ClientPacketListener connection, String name) {
        PlayerInfo direct = connection.getPlayerInfo(name);
        if (direct != null && direct.getProfile() != null && direct.getProfile().id() != null
                && direct.getProfile().id().version() == 4) {
            return direct;
        }
        for (PlayerInfo info : connection.getListedOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null || info.getProfile() == null || !hasTextures(info)) {
                continue;
            }
            String plain = ChatObserver.stripCodes(display.getString());
            if (plain == null) {
                continue;
            }
            Matcher m = TAB_NAME.matcher(plain.trim());
            if (m.matches() && m.group(1).equalsIgnoreCase(name)) {
                return info;
            }
        }
        return null;
    }

    private static boolean hasTextures(PlayerInfo info) {
        try {
            return !info.getProfile().properties().get("textures").isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
