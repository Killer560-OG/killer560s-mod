package com.killer560.hub.relay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ChatObserver;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Hypixel's own per-instance id (the {@code mini312M} / {@code dungeon123} style name), read from
 * {@code /locraw}'s JSON chat reply so every Mod Chat client on the same Hypixel instance derives the
 * identical {@code lobby:<hash>} room name without coordinating - the Lobby half of {@link RelayRoom.Mode},
 * hashed the same way {@link RelayRoom} hashes a party.
 * <p>
 * killer560 (2026-09-20): <i>"Do not have a global option only have a lobby option or a party option."</i>
 * Lobby needs an id every client on the instance computes identically. Per the implementation brief, checked
 * first, before writing this: no HypixelModAPI dependency anywhere in {@code build.gradle}, and nothing
 * already in this repo reads {@code locraw} / a server id / the scoreboard for this purpose (his separately
 * installed HypixelModAPI mod is not a dependency of this jar and exposes nothing to it - see the mod list on
 * his machine, not this repo). So this asks Hypixel itself the plain way: send the command, read the JSON
 * line it answers with back in chat, the same as any player typing {@code /locraw} would get.
 * <p>
 * <b>Not on p3sim.</b> It isn't Hypixel, so there is no real per-instance id to ask for - {@link #lobbyRoom()}
 * stays {@code null} there rather than hash whatever p3sim happens to answer with. Guessing would risk either
 * putting two different p3sim sessions in one room or splitting one session across several - the exact class
 * of mistake {@link RelayRoom}'s class doc warns about.
 */
public final class HypixelLocation {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-relay");
    /** Only used to RETRY when we have no answer yet - never as a polling interval. An earlier version of
     *  this class re-sent {@code /locraw} every 15s for as long as Lobby mode was on: 240 commands an hour,
     *  which is automation, not "a command a player might type". We now ask once per join and then stay
     *  quiet until the answer is invalidated by an actual world/server change. */
    private static final long RETRY_INTERVAL_MS = 10_000L;
    /** Give up asking after this many tries on one join, rather than retrying forever on a server that never
     *  answers (p3sim is already excluded, but any non-Hypixel server would otherwise be asked indefinitely). */
    private static final int MAX_ATTEMPTS_PER_JOIN = 3;

    private static volatile String serverId;
    private static long lastSentAtMs;
    private static int attemptsThisJoin;
    /** Identity of the level we last asked about. Hypixel moves you between instances (hub -> dungeon) with
     *  a server transfer that does not always re-fire JOIN, and a stale id would quietly put you in the wrong
     *  room, so the level object changing is treated as "somewhere new" in its own right. Compared by
     *  reference on purpose - a new ClientLevel means a new place. */
    private static Object lastLevel;
    private static boolean rewriterRegistered;

    private HypixelLocation() {
    }

    public static void register() {
        // A previous instance's id must never survive into a new join - see the class doc on p3sim for the
        // same "don't guess wide" reasoning. This also covers a Hypixel backend switch (hub <-> instance),
        // which re-fires JOIN on the same connection the same way the repo's other per-instance state
        // already relies on (see e.g. LagDisplayFeature/SplitLagClock's own JOIN/DISCONNECT resets).
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    private static void reset() {
        serverId = null;
        lastLevel = null;
        // Next tick() asks immediately instead of waiting out the rest of the old interval.
        lastSentAtMs = 0L;
        attemptsThisJoin = 0;
    }

    /** Called from {@code ModChatFeature}'s own tick. Sends a fresh {@code /locraw} when Lobby mode is
     *  selected and enabled and the last answer is stale enough to be worth refreshing; never sends
     *  otherwise (Party mode never touches this at all). */
    public static void tick(Minecraft client, boolean lobbyModeNeeded) {
        if (!lobbyModeNeeded || client.player == null || client.getConnection() == null || isP3Sim(client)) {
            return;
        }
        if (client.level != lastLevel) {
            lastLevel = client.level;
            serverId = null;
            lastSentAtMs = 0L;
            attemptsThisJoin = 0;
        }
        // Already know where we are: ask nothing. A world or server change calls reset() and only then does
        // this start asking again - that is the whole throttle, and it is why this cannot turn into a
        // command every N seconds no matter how long he leaves Lobby mode on.
        if (serverId != null || attemptsThisJoin >= MAX_ATTEMPTS_PER_JOIN) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSentAtMs < RETRY_INTERVAL_MS) {
            return;
        }
        lastSentAtMs = now;
        attemptsThisJoin++;
        ensureRewriter();
        client.player.connection.sendCommand("locraw");
    }

    /** @return {@code lobby:<hash>} once Hypixel has answered, else {@code null} - see the class doc. */
    static String lobbyRoom() {
        String id = serverId;
        if (id == null) {
            return null;
        }
        String hash = RelayRoom.sha256Hex(id);
        return hash == null ? null : "lobby:" + hash.substring(0, RelayRoom.HEX_LENGTH);
    }

    /** For the settings tab: is the id known, or should it explain why not yet. */
    public static boolean isKnown() {
        return serverId != null;
    }

    /** For the settings tab: p3sim has no real Hypixel instance id, so Lobby mode can never work there. */
    public static boolean isP3Sim(Minecraft client) {
        ServerData server = client.getCurrentServer();
        return server != null && server.ip != null && server.ip.toLowerCase(Locale.ROOT).contains("p3sim");
    }

    private static synchronized void ensureRewriter() {
        if (rewriterRegistered) {
            return;
        }
        rewriterRegistered = true;
        // /locraw's raw JSON is not for humans - hide it once recognised, the same "clean up the noise" habit
        // this mod already applies to other server lines. Only lines carrying BOTH keys /locraw always answers
        // with are touched, so an unrelated JSON-shaped chat line is left alone.
        ChatObserver.addRewriter((message, plain) -> {
            String id = parseServerId(plain);
            if (id == null) {
                return null;
            }
            serverId = id;
            LOGGER.debug("[Relay] Hypixel instance id: {}", id);
            return Component.empty();
        });
    }

    private static String parseServerId(String plain) {
        String trimmed = plain == null ? "" : plain.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return null; // cheap rejection before trying to parse every chat line as JSON
        }
        try {
            JsonElement parsed = JsonParser.parseString(trimmed);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject obj = parsed.getAsJsonObject();
            if (!obj.has("server") || !obj.has("gametype")) {
                return null; // /locraw's exact shape, not just any JSON-looking chat line
            }
            String server = obj.get("server").getAsString();
            return server == null || server.isBlank() ? null : server.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }
}
