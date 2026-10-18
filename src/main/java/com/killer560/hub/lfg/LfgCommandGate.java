package com.killer560.hub.lfg;

import com.killer560.hub.util.ServerCommands;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The ONLY way the Custom Party Finder sends anything to Hypixel, and the hard limits on it. Two commands exist:
 * <ul>
 * <li>{@code /party invite <name>} - only from the leader's own click on Accept, for a join request the relay delivered
 * (the name is the joiner's, stamped by the relay from his verified token). At most one every 3 s, six a minute, and the
 * same name once per 30 s.</li>
 * <li>{@code /party accept <name>} - only when Hypixel's own anchored invite line names the leader who accepted a request
 * this player made by clicking Join. At most one every 5 s, four a minute.</li>
 * </ul>
 * Together never more than eight a minute. Every name must be {@code [A-Za-z0-9_]{1,16}}, so no space and so no second
 * argument can reach the command. So a mod user spamming requests can at most fill a leader's inbox (the relay caps it at
 * eight); nothing reaches Hypixel without that leader clicking, and nothing faster than these limits even then.
 */
public final class LfgCommandGate {

    static final long INVITE_GAP_MS = 3_000L;
    static final int INVITES_PER_MINUTE = 6;
    static final long SAME_NAME_GAP_MS = 30_000L;
    static final long ACCEPT_GAP_MS = 5_000L;
    static final int ACCEPTS_PER_MINUTE = 4;
    static final int TOTAL_PER_MINUTE = 8;

    private static final ArrayDeque<Long> INVITES = new ArrayDeque<>();
    private static final ArrayDeque<Long> ACCEPTS = new ArrayDeque<>();
    private static final ArrayDeque<Long> ALL = new ArrayDeque<>();
    private static final Map<String, Long> LAST_BY_NAME = new HashMap<>();
    /** Test hook: every command this gate let through, "/party invite Name". */
    private static final List<String> SENT = new ArrayList<>();
    /** Test hook: every refusal, "invite Name: reason". */
    private static final List<String> REFUSED = new ArrayList<>();

    private LfgCommandGate() {
    }

    /** @return null when sent, else why not. */
    public static synchronized String invite(String name) {
        return send("invite", name, INVITES, INVITE_GAP_MS, INVITES_PER_MINUTE, true);
    }

    /** @return null when sent, else why not. */
    public static synchronized String accept(String name) {
        return send("accept", name, ACCEPTS, ACCEPT_GAP_MS, ACCEPTS_PER_MINUTE, false);
    }

    private static String send(String verb, String name, ArrayDeque<Long> times, long gap, int perMinute, boolean perName) {
        String why = refusal(name, times, gap, perMinute, perName);
        if (why != null) {
            REFUSED.add(verb + " " + name + ": " + why);
            return why;
        }
        long now = System.currentTimeMillis();
        if (!ServerCommands.toServer("party " + verb + " " + name)) {
            REFUSED.add(verb + " " + name + ": not connected");
            return "Not connected.";
        }
        times.addLast(now);
        ALL.addLast(now);
        if (perName) {
            LAST_BY_NAME.put(name.toLowerCase(Locale.ROOT), now);
        }
        SENT.add("/party " + verb + " " + name);
        return null;
    }

    private static String refusal(String name, ArrayDeque<Long> times, long gap, int perMinute, boolean perName) {
        if (name == null || !LfgListing.NAME.matcher(name).matches()) {
            return "Not a Minecraft name.";
        }
        long now = System.currentTimeMillis();
        trim(times, now);
        trim(ALL, now);
        if (!times.isEmpty() && now - times.peekLast() < gap) {
            return "Wait a moment.";
        }
        if (times.size() >= perMinute || ALL.size() >= TOTAL_PER_MINUTE) {
            return "Too many party commands this minute.";
        }
        if (perName) {
            Long last = LAST_BY_NAME.get(name.toLowerCase(Locale.ROOT));
            if (last != null && now - last < SAME_NAME_GAP_MS) {
                return "Already invited " + name + ".";
            }
        }
        return null;
    }

    private static void trim(ArrayDeque<Long> times, long now) {
        while (!times.isEmpty() && now - times.peekFirst() >= 60_000L) {
            times.removeFirst();
        }
    }

    public static synchronized List<String> sent() {
        return List.copyOf(SENT);
    }

    public static synchronized List<String> refused() {
        return List.copyOf(REFUSED);
    }

    /** Test hook: forget every limit and record. */
    public static synchronized void resetForTest() {
        INVITES.clear();
        ACCEPTS.clear();
        ALL.clear();
        LAST_BY_NAME.clear();
        SENT.clear();
        REFUSED.clear();
    }
}
