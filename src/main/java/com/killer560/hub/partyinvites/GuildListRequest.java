package com.killer560.hub.partyinvites;

import com.killer560.hub.relay.RelayCalls;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ServerCommands;
import com.killer560.hub.util.SkyblockGate;
import net.minecraft.client.Minecraft;

import java.util.UUID;

/**
 * Gets the guild list for Auto Accept Invites' "Only From Guild Members" (guildtag-1118): first from the relay's Hypixel read
 * ({@code GET /hypixel/guild}, service key {@code guild-relay}, once per session, retried at most every 10 minutes while it
 * fails; guildapi-1119), and only when that fails by sending {@code /guild list} once per game session, hiding the reply it
 * asked for. Only while that switch is ON (and the feature), only on Skyblock outside a dungeon, at least
 * 5 s after joining, and once more after 8 s with no reply; then never again until the next launch. A list the player types
 * is never hidden.
 */
public final class GuildListRequest {

    static final long JOIN_DELAY_MS = 5_000L;
    static final long RETRY_MS = 8_000L;
    static final long HIDE_MS = 6_000L;

    private static volatile long joinedAt;
    private static long lastSentAt;
    private static int sends;
    static final long API_RETRY_MS = 10 * 60_000L;
    private static volatile boolean apiInFlight;
    private static volatile boolean apiFailed;
    private static long apiAt;
    private static int apiCalls;
    private static volatile long hideUntil;
    private static volatile boolean closingDash;
    /** Test hook: how many /guild list commands went out. */
    private static int sentForTest;

    private GuildListRequest() {
    }

    public static void onJoin() {
        joinedAt = System.currentTimeMillis();
    }

    /** Client tick (cheap early-outs first). */
    static void tick(Minecraft client) {
        if (sends >= 2 || GuildMembers.haveList() || client.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - joinedAt < JOIN_DELAY_MS || sends > 0 && now - lastSentAt < RETRY_MS) {
            return;
        }
        AutoAcceptInvitesConfig cfg = AutoAcceptInvitesConfig.getInstance();
        if (!cfg.isFromGuild() || !cfg.isEnabled() || apiInFlight) {
            return;
        }
        if (apiAt == 0L || apiFailed && sends == 0 && now - apiAt >= API_RETRY_MS) {
            askApi(client, now);
            return;
        }
        if (!apiFailed || DungeonState.isInDungeon() || !SkyblockGate.isOnSkyblock()) {
            return;
        }
        if (ServerCommands.toServer("guild list")) {
            sends++;
            sentForTest++;
            lastSentAt = now;
            hideUntil = now + HIDE_MS;
            closingDash = false;
        }
    }

    private static void askApi(Minecraft client, long now) {
        UUID me = client.getUser() == null ? null : client.getUser().getProfileId();
        if (me == null) {
            return;
        }
        apiAt = now;
        apiInFlight = true;
        apiCalls++;
        RelayCalls.call("guild-relay", "GET", "/hypixel/guild?id=" + me.toString().replace("-", ""), null).whenComplete((reply, e) -> {
            apiFailed = e != null || reply == null || !reply.ok() || !GuildMembers.applyApi(reply.body());
            apiInFlight = false;
        });
    }

    /** Account switch: ask again for the new account. */
    public static void resetSession() {
        apiAt = 0L;
        apiFailed = false;
        sends = 0;
    }

    /** Display hook (ChatTidy): true drops a reply line while the mod's own request is waiting for it. */
    public static boolean hides(String plain) {
        if (plain == null || System.currentTimeMillis() > hideUntil) {
            return false;
        }
        String s = plain.trim();
        if (closingDash && GuildMembers.DASHES.matcher(s).matches()) {
            closingDash = false;
            hideUntil = 0L;
            return true;
        }
        if (!GuildMembers.isReplyLine(s)) {
            return false;
        }
        if (s.startsWith("Online Members:") || s.equals("You are not in a guild!")) {
            closingDash = true;
        }
        return true;
    }

    public static int sentForTest() {
        return sentForTest;
    }

    /** Test hook: how many relay guild reads went out. */
    public static int apiCallsForTest() {
        return apiCalls;
    }

    public static void resetForTest() {
        resetSession();
        apiCalls = 0;
        apiInFlight = false;
        sentForTest = 0;
        hideUntil = 0L;
        closingDash = false;
        joinedAt = 0L;
    }
}
