package com.killer560.hub.partyinvites;

import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ServerCommands;
import com.killer560.hub.util.SkyblockGate;
import net.minecraft.client.Minecraft;

/**
 * Sends {@code /guild list} once per game session for Auto Accept Invites' "Only From Guild Members" (guildtag-1118), and
 * hides the reply it asked for. Only while that switch is ON (and the feature), only on Skyblock outside a dungeon, at least
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
        if (!cfg.isFromGuild() || !cfg.isEnabled() || DungeonState.isInDungeon() || !SkyblockGate.isOnSkyblock()) {
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

    public static void resetForTest() {
        sends = 0;
        sentForTest = 0;
        hideUntil = 0L;
        closingDash = false;
        joinedAt = 0L;
    }
}
