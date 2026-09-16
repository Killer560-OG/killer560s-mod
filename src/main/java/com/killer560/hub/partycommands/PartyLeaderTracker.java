package com.killer560.hub.partycommands;

import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who the party leader is, for OdinLegacy's {@code PartyUtils.isLeader()} check - Odin only honours a
 * party-mutating command ({@code !warp}, {@code !kick}, {@code !pt}, ...) while YOU are the leader, otherwise
 * Hypixel would just answer "You are not the leader" for every one of them.
 * <p>
 * Same patterns as {@code scoreboard.ScoreboardPartyLeader} (which is package-private to the scoreboard, and
 * whose own doc sets the precedent of keeping a copy next to the feature that needs it rather than depending on
 * another feature's internals). Unlike that one this is fed only from {@code ChatCommandsFeature}'s Fabric
 * {@code ClientReceiveMessageEvents} listener, i.e. only from lines that really came from the server - never
 * from {@code ChatObserver}'s ChatComponent path, which another mod (or this one) can inject into.
 * <p>
 * Note this is a convenience gate, not the security boundary: {@link PartyCommandsFeature}'s teammate check is.
 * "Leader unknown" deliberately means "let it through" so a fresh login doesn't break every command.
 */
final class PartyLeaderTracker {

    private static final String NAME = "(?:\\[[^]]*?] ?)?(\\w{1,16})";

    private static final Pattern YOU_JOINED = Pattern.compile("^You have joined " + NAME + "'s? party!$");
    private static final Pattern TRANSFER_LEAVE = Pattern.compile("^The party was transferred to " + NAME + " because " + NAME + " left$");
    private static final Pattern TRANSFER_BY = Pattern.compile("^The party was transferred to " + NAME + " by " + NAME + "$");
    private static final Pattern LEADER_DISCONNECTED = Pattern.compile("^The party leader, " + NAME
            + " has disconnected, they have 5 minutes to rejoin before the party is disbanded\\.$");
    private static final Pattern LEADER_REJOINED = Pattern.compile("^The party leader " + NAME + " has rejoined\\.$");
    private static final Pattern INVITE = Pattern.compile("^" + NAME + " invited " + NAME + " to the party! They have 60 seconds to accept\\.$");
    private static final Pattern LEADER_LIST = Pattern.compile("^Party Leader: " + NAME + " ?●");
    private static final Pattern PROMOTED = Pattern.compile("^" + NAME + " has promoted " + NAME + " to Party Leader$");
    private static final List<Pattern> CLEARED = List.of(
            Pattern.compile("^" + NAME + " has disbanded the party!$"),
            Pattern.compile("^You have been kicked from the party by " + NAME + "$"),
            Pattern.compile("^The party was disbanded because .+$"),
            Pattern.compile("^You left the party\\.$"),
            Pattern.compile("^You are not currently in a party\\.$"),
            Pattern.compile("^You are not in a party.*$"));

    private static volatile String leader;

    private PartyLeaderTracker() {
    }

    /** {@code plain} is a stripped, trimmed line that really arrived from the server. */
    static void onServerLine(String plain) {
        Matcher m;
        if ((m = YOU_JOINED.matcher(plain)).matches()
                || (m = TRANSFER_LEAVE.matcher(plain)).matches()
                || (m = TRANSFER_BY.matcher(plain)).matches()
                || (m = LEADER_DISCONNECTED.matcher(plain)).matches()
                || (m = LEADER_REJOINED.matcher(plain)).matches()
                || (m = LEADER_LIST.matcher(plain)).find()) {
            leader = m.group(1);
            return;
        }
        if ((m = PROMOTED.matcher(plain)).matches()) {
            leader = m.group(2);
            return;
        }
        if ((m = INVITE.matcher(plain)).matches()) {
            if (leader == null) {
                leader = m.group(1);
            }
            return;
        }
        for (Pattern p : CLEARED) {
            if (p.matcher(plain).matches()) {
                leader = null;
                return;
            }
        }
    }

    /** @return the known leader, or null when unknown / not in a party. */
    static String get() {
        return leader;
    }

    /** True when we know we are NOT the leader. Unknown leader returns false (let the command through). */
    static boolean knownNotLeader() {
        String current = leader;
        if (current == null) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        String self = client.player == null ? null : client.player.getGameProfile().name();
        return self != null && !current.equalsIgnoreCase(self);
    }
}
