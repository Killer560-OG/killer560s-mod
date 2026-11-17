package com.killer560.hub.partyinvites;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The whole of Auto Accept Invites' decision, with no Minecraft in it so it can be checked on its own (killer560,
 * 2026-10-20): "One for all, one for just reinvites. All invites will accept, one if you are not currently in a party.
 * Reinvites need to look at what party you were just in. If that party contained any of the people that just invited
 * you then it rejoins that. If not it ignores it. It should base that off of a party you were in within the last 2
 * minutes."
 * <p>
 * One instance remembers the party you are in now (everyone seen in it since it started, so someone who left before the
 * end still counts) and, once you are out of it, that party and when you left. The record is dropped
 * {@link #WINDOW_MS} after you left.
 */
public final class AutoAcceptRule {

    /** A reinvite counts only from a party you left less than this long ago. */
    public static final long WINDOW_MS = 120_000L;

    public enum Mode {
        ALL_INVITES("All Invites"),
        REINVITES_ONLY("Reinvites Only");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    public enum Decision {
        /** Send {@code /party accept}. */
        ACCEPT,
        /** The setting is off. */
        OFF,
        /** You are in a party now: never accepted, in either mode. */
        IN_PARTY,
        /** Reinvites Only, and you have not left a party in the last two minutes. */
        NO_RECENT_PARTY,
        /** Reinvites Only, and nobody who invited you was in the party you left. */
        NOT_IN_LAST_PARTY
    }

    private static final String NAME = "([A-Za-z0-9_]{1,16})";
    /** A rank tag has no space in it, so a player's chat line ("[VIP] Bob: ...") can never fill the name group. */
    private static final String RANK = "(?:\\[[A-Z+]{1,12}\\] )?";
    /**
     * The middle line of Hypixel's framed invite, in its two shapes (seen in killer560's own logs):
     * "[MVP+] Xolty has invited you to join their party!" and, when a member invites for the leader,
     * "[VIP] nightmare_night has invited you to join [MVP+] Xolty's party!" (a name ending in s takes a bare
     * apostrophe: "[MVP] bebahs' party!").
     */
    static final Pattern INVITE = Pattern.compile(
            "^" + RANK + NAME + " has invited you to join (?:their|" + RANK + NAME + "'s?) party!$");
    static final Pattern ACCEPT_LINE = Pattern.compile("^You have \\d{1,3} seconds to accept\\. Click here to join!$");
    static final Pattern FRAME = Pattern.compile("^-{10,80}$");
    /** The command on Hypixel's "Click here to join!" text, when it carries one. */
    static final Pattern CLICK_COMMAND = Pattern.compile("^/(?:party|p) accept " + NAME + "$");

    /** One invite: who sent it, and the leader whose party it is (the same person for "their party"). */
    public record Invite(String inviter, String leader) {

        /** Everyone the invite came from: the inviter, and the leader when someone else invited for him. */
        public List<String> senders() {
            return inviter.equalsIgnoreCase(leader) ? List.of(inviter) : List.of(inviter, leader);
        }
    }

    /**
     * Hypixel's whole framed invite message (colour codes stripped): a line of dashes, the invite line, "You have 60
     * seconds to accept. Click here to join!", and the dashes again. Anything else, including this mod's own translated
     * copy of it (which starts "[Killer560's Mod -> ...]"), is null.
     */
    public static Invite parse(String plain) {
        if (plain == null || plain.length() > 400) {
            return null;
        }
        String[] lines = plain.split("\n");
        if (lines.length != 4 || !FRAME.matcher(lines[0].trim()).matches() || !FRAME.matcher(lines[3].trim()).matches()
                || !ACCEPT_LINE.matcher(lines[2].trim()).matches()) {
            return null;
        }
        Matcher m = INVITE.matcher(lines[1].trim());
        if (!m.matches()) {
            return null;
        }
        String inviter = m.group(1);
        String leader = m.group(2) == null ? inviter : m.group(2);
        return new Invite(inviter, leader);
    }

    /**
     * The name {@code /party accept} is given: the one on Hypixel's own click command when it names someone in the
     * invite, else the leader (the party being joined).
     */
    public static String acceptName(Invite invite, String clickCommand) {
        if (clickCommand != null) {
            Matcher m = CLICK_COMMAND.matcher(clickCommand.trim());
            if (m.matches()) {
                String n = m.group(1);
                for (String s : invite.senders()) {
                    if (s.equalsIgnoreCase(n)) {
                        return n;
                    }
                }
            }
        }
        return invite.leader();
    }

    /** Lower-case name -> spelling, for the party you are in now. */
    private final Map<String, String> current = new LinkedHashMap<>();
    private boolean inParty = false;
    private boolean freshParty = false;
    /** The party you were last in, and when you left it; empty when there is no record (or it timed out). */
    private final Map<String, String> last = new LinkedHashMap<>();
    private long leftAt = 0L;

    /**
     * The party as it stands now (your teammates, without you). Called every client tick, and before every decision.
     * Going from some teammates to none is leaving: what was recorded becomes the last party, stamped {@code now}.
     */
    public void observe(Collection<String> teammates, long now) {
        if (!last.isEmpty() && now - leftAt >= WINDOW_MS) {
            last.clear();
        }
        if (teammates != null && !teammates.isEmpty()) {
            if (!inParty || freshParty) {
                current.clear();
                freshParty = false;
            }
            inParty = true;
            for (String n : teammates) {
                if (n != null && !n.isEmpty()) {
                    current.putIfAbsent(n.toLowerCase(Locale.ROOT), n);
                }
            }
        } else if (inParty) {
            inParty = false;
            last.clear();
            last.putAll(current);
            leftAt = now;
            current.clear();
        }
    }

    /** Hypixel said "You have joined X's party!": the next {@link #observe} starts a new record. */
    public void joinedNewParty() {
        freshParty = true;
    }

    public Decision decide(boolean enabled, Mode mode, Invite invite, long now) {
        if (!enabled || invite == null) {
            return Decision.OFF;
        }
        if (inParty) {
            return Decision.IN_PARTY;
        }
        if (mode == Mode.ALL_INVITES) {
            return Decision.ACCEPT;
        }
        if (last.isEmpty() || now - leftAt >= WINDOW_MS) {
            return Decision.NO_RECENT_PARTY;
        }
        for (String s : invite.senders()) {
            if (last.containsKey(s.toLowerCase(Locale.ROOT))) {
                return Decision.ACCEPT;
            }
        }
        return Decision.NOT_IN_LAST_PARTY;
    }

    public boolean inParty() {
        return inParty;
    }

    /** The last party's members (empty when there is no record). */
    public List<String> lastParty() {
        return new ArrayList<>(last.values());
    }

    public long leftAt() {
        return leftAt;
    }

    public void reset() {
        current.clear();
        last.clear();
        inParty = false;
        freshParty = false;
        leftAt = 0L;
    }
}
