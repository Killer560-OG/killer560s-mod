package com.killer560.hub.runstats;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The secret counts of ONE dungeon run (playersecrets-1118). Pure logic, no Minecraft or network: the tracker feeds it
 * what Hypixel's player data said at run start and again after the run, and what the game itself says.
 *
 * <p>Ground truth in the game: the end-of-run Stats block's "Secrets Found: N" is the viewer's OWN count (it matched
 * the old API delta for the player in 18 of 36 logged runs and was never the team's), and the tab list's
 * "Secrets Found: N" is the team's. So the player's own row is exact, and every other row is a lifetime-total delta that
 * is only shown once {@code own + sum of the deltas == team total}. The API lags the run (an end read returns the old
 * total) and lags the run before it (a start read misses its secrets, inflating the delta), and a delta known to be
 * wrong is never shown: it reads "?". Lifetime totals only grow, so a start read is lifted to the newest total already
 * seen for that player this session ({@code known}).
 */
public final class SecretRun {

    /** Start-read attempts per player, spaced {@link #RETRY_MS} apart (the backend answers 429 for 30 s). */
    static final int MAX_TRIES = 6;
    static final long RETRY_MS = 30_000L;

    private final Map<String, Long> known;
    private final Map<String, Long> start = new LinkedHashMap<>();
    private final Map<String, Long> end = new LinkedHashMap<>();
    private final Map<String, String> display = new LinkedHashMap<>();
    private final Map<String, long[]> tries = new HashMap<>();
    private String self;
    private int own = -1;
    private int team = -1;
    private boolean ended;

    public SecretRun(Map<String, Long> known) {
        this.known = known;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.US);
    }

    public synchronized void setSelf(String name) {
        self = key(name);
        display.putIfAbsent(self, name);
    }

    /** True when {@code name} should get a start read now (not yet read, under the try cap, past its retry time). */
    public synchronized boolean wantStart(String name, long nowMs) {
        String k = key(name);
        if (ended || k.equals(self) || start.containsKey(k)) return false;
        long[] t = tries.computeIfAbsent(k, x -> new long[2]);
        if (t[0] >= MAX_TRIES || nowMs < t[1]) return false;
        t[0]++;
        t[1] = nowMs + RETRY_MS;
        display.putIfAbsent(k, name);
        return true;
    }

    /** A start read. Ignored once the run ended (it would already hold the run's secrets); lifted to the newest known total. */
    public synchronized void gotStart(String name, long total) {
        String k = key(name);
        if (ended || start.containsKey(k)) return;
        display.putIfAbsent(k, name);
        start.put(k, Math.max(total, known.getOrDefault(k, 0L)));
        known.merge(k, total, Math::max);
    }

    /** An end read; the highest one wins (a lagging read can come back late and lower). */
    public synchronized void gotEnd(String name, long total) {
        String k = key(name);
        end.merge(k, total, Math::max);
        known.merge(k, total, Math::max);
    }

    /** The run ended: from here start reads are refused. {@code teamTotal} is the tab list's team count, or -1. */
    public synchronized void markEnded(int teamTotal) {
        ended = true;
        if (teamTotal >= 0) team = teamTotal;
    }

    public synchronized boolean ended() { return ended; }

    public synchronized void setOwn(int n) { own = n; }

    public synchronized int own() { return own; }

    public synchronized void setTeam(int n) { team = n; }

    /** Players to read at the end: those whose total has not moved yet, or all of them when every one moved but the sum falls short. */
    public synchronized List<String> endTargets() {
        List<String> unchanged = new ArrayList<>();
        for (Map.Entry<String, Long> e : start.entrySet()) {
            Long after = end.get(e.getKey());
            if (after == null || after <= e.getValue()) unchanged.add(display.get(e.getKey()));
        }
        return unchanged.isEmpty() && !done() ? new ArrayList<>(start.keySet().stream().map(display::get).toList()) : unchanged;
    }

    private long sum() {
        long sum = own < 0 ? 0 : own;
        for (Map.Entry<String, Long> e : start.entrySet()) {
            Long after = end.get(e.getKey());
            if (after != null) sum += Math.max(0, after - e.getValue());
        }
        return sum;
    }

    private boolean complete() {
        for (String k : start.keySet()) {
            if (!end.containsKey(k)) return false;
        }
        return true;
    }

    /** Own + the others' deltas equal the team total: every shown number is then the real one. */
    public synchronized boolean reconciled() {
        return own >= 0 && team >= 0 && complete() && sum() == team && everyoneMeasured();
    }

    /** A player who never got a start read was not measured, and the sum could match by luck without them. */
    private boolean everyoneMeasured() {
        for (String k : display.keySet()) {
            if (!k.equals(self) && !start.containsKey(k)) return false;
        }
        return true;
    }

    /** Waiting is pointless: the deltas already exceed the team total (a start read was stale, an end read cannot fix it). */
    public synchronized boolean hopeless() {
        return own >= 0 && team >= 0 && sum() > team;
    }

    private boolean done() { return reconciled() || hopeless(); }

    /** Worth reading again: something is still short of the team total and it can still change. */
    public synchronized boolean pending() {
        return ended && !start.isEmpty() && !done();
    }

    /** Secrets for {@code name}: the player's own count is exact; others only once reconciled; -1 = unknown. */
    public synchronized int secrets(String name) {
        String k = key(name);
        if (k.equals(self)) return own;
        Long before = start.get(k);
        Long after = end.get(k);
        if (before == null || after == null || !reconciled()) return -1;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, after - before));
    }

    /** "Name 12 | Other ?" for the late chat line, empty when nothing is known. */
    public synchronized String line() {
        StringBuilder sb = new StringBuilder();
        for (String name : display.values()) {
            int n = secrets(name);
            if (!sb.isEmpty()) sb.append(" | ");
            sb.append(name).append(' ').append(n < 0 ? "?" : String.valueOf(n));
        }
        return own < 0 && !reconciled() ? "" : sb.toString();
    }
}
