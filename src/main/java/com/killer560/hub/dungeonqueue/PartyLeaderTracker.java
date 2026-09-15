package com.killer560.hub.dungeonqueue;

import com.killer560.hub.util.ChatObserver;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who leads your party - ported from the leader half of NoammAddons' {@code PartyUtils} (itself from Odin's
 * {@code PartyUtils}), which {@code AutoRequeue.kt} checks before sending {@code /joininstance}. Member lists
 * stay in {@link com.killer560.hub.leapmenu.PartyTracker}; this only tracks the leader name.
 * <p>
 * The dungeon enter line ("[MVP+] Name entered MM The Catacombs, Floor VII!") names the leader - the same
 * shortcut NoammAddons takes - so the leader is always known inside a run even if the party was formed before
 * the mod loaded.
 */
public final class PartyLeaderTracker {

    private static final String NAME = "(?:\\[[^]]*?] ?)?(\\w{1,16})";

    private static final Pattern YOU_JOINED = Pattern.compile("^You have joined " + NAME + "'s? party!$");
    private static final Pattern TRANSFER_LEAVE = Pattern.compile("^The party was transferred to " + NAME + " because " + NAME + " left$");
    private static final Pattern TRANSFER_BY = Pattern.compile("^The party was transferred to " + NAME + " by " + NAME + "$");
    private static final Pattern LEADER_DISCONNECTED = Pattern.compile("^The party leader, " + NAME
            + " has disconnected, they have 5 minutes to rejoin before the party is disbanded\\.$");
    private static final Pattern LEADER_REJOINED = Pattern.compile("^The party leader " + NAME + " has rejoined\\.$");
    private static final Pattern INVITE = Pattern.compile("^" + NAME + " invited " + NAME + " to the party! They have 60 seconds to accept\\.$");
    private static final Pattern QUEUED_IN_FINDER = Pattern.compile("^Party Finder > Your party has been queued in the dungeon finder!$");
    private static final Pattern LEADER_LIST = Pattern.compile("^Party Leader: " + NAME + " ?●");
    /** Hypixel sends this framed by dashed lines in one message, so it is matched per line. */
    static final Pattern DUNGEON_ENTER = Pattern.compile(
            "(?m)^\\s*" + NAME + " entered (?:MM )?(?:The )?Catacombs, (?:Floor [IVX]+|Entrance)!\\s*$");
    private static final List<Pattern> DISBAND = List.of(
            Pattern.compile("^" + NAME + " has disbanded the party!$"),
            Pattern.compile("^You have been kicked from the party by " + NAME + "$"),
            Pattern.compile("^The party was disbanded because .+$"),
            Pattern.compile("^You left the party\\.$"),
            Pattern.compile("^You are not currently in a party\\.$"),
            Pattern.compile("^You are not in a party.*$"));

    private static String leader;

    private PartyLeaderTracker() {
    }

    public static void register() {
        ChatObserver.subscribe(PartyLeaderTracker::onChat);
    }

    private static void onChat(Component message) {
        String plain = ChatObserver.strip(message);
        Matcher m = DUNGEON_ENTER.matcher(plain);
        if (m.find()) {
            leader = m.group(1);
            return;
        }
        plain = plain.trim();
        if ((m = YOU_JOINED.matcher(plain)).matches()
                || (m = TRANSFER_LEAVE.matcher(plain)).matches()
                || (m = TRANSFER_BY.matcher(plain)).matches()
                || (m = LEADER_DISCONNECTED.matcher(plain)).matches()
                || (m = LEADER_REJOINED.matcher(plain)).matches()
                || (m = LEADER_LIST.matcher(plain)).find()) {
            leader = m.group(1);
            return;
        }
        if ((m = INVITE.matcher(plain)).matches()) {
            if (leader == null) {
                leader = m.group(1);
            }
            return;
        }
        if (QUEUED_IN_FINDER.matcher(plain).matches()) {
            if (leader == null) {
                leader = selfName();
            }
            return;
        }
        for (Pattern p : DISBAND) {
            if (p.matcher(plain).matches()) {
                leader = null;
                return;
            }
        }
    }

    /** @return the known party leader's name, or null when unknown / not in a party. */
    public static String getLeader() {
        return leader;
    }

    public static boolean isSelfLeader() {
        String self = selfName();
        return self != null && self.equalsIgnoreCase(leader);
    }

    private static String selfName() {
        // Same as NoammAddons' PartyUtils.isLeader (mc.user.name) - the logged-in account's name.
        return Minecraft.getInstance().getUser().getName();
    }
}
