package com.killer560.hub.relay;

import com.killer560.hub.scoreboard.ScoreboardData;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hypixel's own per-instance id (the {@code mini312M} / {@code mega12A} style name) read off the scoreboard
 * sidebar's gray date line ("11/15/26 m312M"), which Hypixel sends every client, so every Mod Chat client on the
 * same instance derives the identical {@code lobby:<hash>} room name without coordinating - the Lobby half of
 * {@link RelayRoom.Mode}, hashed the same way {@link RelayRoom} hashes a party. The mod sends nothing to learn it
 * (a server must never be able to tell the mod is installed). The sidebar writes the id short (m = mini, M = mega,
 * L = lobby); {@link #expand} turns it into the long form the old command reply used, so a player on an older jar
 * lands in the same room.
 * <p>
 * <b>Not on p3sim.</b> It isn't Hypixel, so there is no real per-instance id - {@link #lobbyRoom()} stays
 * {@code null} there rather than guess.
 */
public final class HypixelLocation {

    private static final Pattern DATE_ID = Pattern.compile("^\\d{1,2}/\\d{1,2}/\\d{2,4} ([A-Za-z]+[0-9][0-9A-Za-z]*)$");
    /** Any section-sign code (Hypixel also uses ones vanilla has no name for). */
    private static final Pattern CODES = Pattern.compile("§.");
    /** Sidebar read at most once a second. */
    private static final long READ_INTERVAL_MS = 1_000L;

    private static volatile String serverId;
    private static long lastReadAtMs;
    /** The level we last read for; a new ClientLevel means somewhere new, so the old id is dropped (by reference). */
    private static Object lastLevel;

    private HypixelLocation() {
    }

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    private static void reset() {
        serverId = null;
        lastLevel = null;
        lastReadAtMs = 0L;
    }

    /** Called from {@code ModChatFeature}'s own tick; reads the sidebar when a lobby room could use it (never sends). */
    public static void tick(Minecraft client, boolean lobbyModeNeeded) {
        if (!lobbyModeNeeded || client.player == null || client.getConnection() == null || isP3Sim(client)
                || com.killer560.hub.roomsim.SimState.isGuest(client) || client.getSingleplayerServer() != null) return;
        if (client.level != lastLevel) {
            lastLevel = client.level;
            serverId = null;
            lastReadAtMs = 0L;
        }
        long now = System.currentTimeMillis();
        if (now - lastReadAtMs < READ_INTERVAL_MS) return;
        lastReadAtMs = now;
        for (String line : ScoreboardData.sidebarNow(client)) {
            Matcher m = DATE_ID.matcher(CODES.matcher(line).replaceAll("").trim());
            if (m.matches()) {
                serverId = expand(m.group(1));
                return;
            }
        }
    }

    /** m312M -> mini312m, M12A -> mega12a, L12 -> lobby12 (lower case, as the old command reply was stored). */
    static String expand(String id) {
        String rest = id.substring(1);
        String head = switch (id.charAt(0)) {
            case 'm' -> "mini";
            case 'M' -> "mega";
            case 'L' -> "lobby";
            default -> id.substring(0, 1);
        };
        return (head + rest).toLowerCase(Locale.ROOT);
    }

    /** @return {@code lobby:<hash>} once Hypixel has answered, else {@code null} - see the class doc. */
    static String lobbyRoom() {
        String id = serverId;
        if (id == null) return null;
        String hash = RelayRoom.sha256Hex(id);
        return hash == null ? null : "lobby:" + hash.substring(0, RelayRoom.HEX_LENGTH);
    }

    /**
     * An opaque key for the lobby we are on, or null while Hypixel has not answered yet.
     *
     * <p>Public because the Crystal Hollows map (mining/chmap) threw its findings away
     * when the lobby changed - Hypixel lays that world out differently on every server. Same value
     * {@link #lobbyRoom} keys the relay room on.
     */
    public static String lobbyKey() {
        return lobbyRoom();
    }

    /** For the settings tab: is the id known, or should it explain why not yet. */
    public static boolean isKnown() { return serverId != null; }

    /** For the settings tab: p3sim has no real Hypixel instance id, so Lobby mode can never work there. */
    public static boolean isP3Sim(Minecraft client) {
        ServerData server = client.getCurrentServer();
        return server != null && server.ip != null && server.ip.toLowerCase(Locale.ROOT).contains("p3sim");
    }
}
