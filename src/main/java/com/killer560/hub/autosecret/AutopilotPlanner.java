package com.killer560.hub.autosecret;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Dungeon Autopilot's choice - pure, no Minecraft state (the testkit's logic test drives it with made-up candidates).
 * {@link Autopilot} turns the floor into {@link Candidate}s, each with the score it should buy
 * ({@link AutopilotScore}) and the seconds it should take (travel plus the work); this picks one.
 *
 * <ul>
 *   <li><b>Solo</b>: the best score per second, over everything. Rooms must all be cleared for 300 anyway, so in effect
 *       this orders the floor by travel - the room next door wins - while secrets drop out the moment the S+ need is
 *       met (their gain goes to zero).</li>
 *   <li><b>Party</b>: secrets (and picking up a dropped key - it opens the way for the whole party) first, best per
 *       second among them. Only when no
 *       secret is left anywhere it can reach: puzzles, clears and unexplored rooms, never one a teammate stands in
 *       ("nobody else will").</li>
 *   <li><b>Blood First</b> (either mode, until the blood door is open): only clears on the Blood Rush Split path, in
 *       path order from the Entrance - pushing toward blood, never sideways.</li>
 * </ul>
 * Ties go to the shorter action, then the room name, so a choice is reproducible.
 */
public final class AutopilotPlanner {

    /** Below this an action is treated as this long, so a zero-travel estimate cannot divide to infinity. */
    public static final double MIN_SECONDS = 0.5;

    public enum Kind { SECRET, CLEAR, PUZZLE, EXPLORE, KEY }

    /**
     * One thing it could do next.
     * @param rushIndex the room's place on the Blood Rush Split path (0 = Entrance), -1 when off it
     */
    public record Candidate(Kind kind, String room, double gain, double seconds, boolean teammateInside, int rushIndex) {
        public double rate() {
            return gain / Math.max(MIN_SECONDS, seconds);
        }

        public String describe() {
            return String.format(Locale.US, "%s %s %.3f/%.2fs=%.4f%s", kind, room, gain, seconds, rate(),
                    teammateInside ? " (teammate)" : "");
        }
    }

    /** The pick, why, and every candidate in the order it was weighed (best first); {@code tour}: the order it plans to
     *  take them in (by {@link #chooseTour}; empty from {@link #choose}). */
    public record Choice(Candidate pick, String why, List<Candidate> ranked, List<Candidate> tour) {
        public Choice(Candidate pick, String why, List<Candidate> ranked) {
            this(pick, why, ranked, List.of());
        }
    }

    /** Trip times between candidates, in seconds: from where he is to one, and from where one's work ends to another. */
    public interface Travel {
        double fromHere(String key);

        double between(String fromKey, String toKey);
    }

    private static final Comparator<Candidate> BY_RATE = Comparator.comparingDouble(Candidate::rate).reversed()
            .thenComparingDouble(Candidate::seconds).thenComparing(Candidate::room);

    private AutopilotPlanner() {
    }

    /** @return the choice, or null when nothing is worth doing */
    public static Choice choose(boolean party, List<Candidate> all) {
        List<Candidate> pool = new ArrayList<>();
        for (Candidate c : all) {
            if (c.gain() > 0) {
                pool.add(c);
            }
        }
        if (!party) {
            pool.sort(BY_RATE);
            return pool.isEmpty() ? null : new Choice(pool.get(0), "Solo: best score per second", pool);
        }
        List<Candidate> secrets = new ArrayList<>();
        List<Candidate> rest = new ArrayList<>();
        for (Candidate c : pool) {
            if (c.kind() == Kind.SECRET || c.kind() == Kind.KEY) {
                secrets.add(c);
            } else if (!c.teammateInside()) {
                rest.add(c);
            }
        }
        secrets.sort(BY_RATE);
        rest.sort(BY_RATE);
        List<Candidate> ranked = new ArrayList<>(secrets);
        ranked.addAll(rest);
        if (!secrets.isEmpty()) {
            return new Choice(secrets.get(0), "Party: secrets first, best per second", ranked);
        }
        return rest.isEmpty() ? null : new Choice(rest.get(0), "Party: no secrets left it can reach - nobody else is on this one",
                ranked);
    }

    /**
     * {@link #choose}'s pools and tiers, but within the tier the whole order counts, not the next action alone
     * ({@link TourPlanner}: every candidate's score, by the second it is in, over the trips between them) - killer560,
     * 2026-10-15: "spending 20 ticks etherwarping across a map for 2 secrets is less optimal than doing 2 1 secret rooms
     * taking 5 ticks each". A key on the ground that is the best by rate is still taken first (it opens doors for the
     * whole party). Each candidate's work is its seconds less its trip from here.
     */
    public static Choice chooseTour(boolean party, List<Candidate> all, Travel travel, long budgetNanos) {
        Choice byRate = choose(party, all);
        if (byRate == null || byRate.pick().kind() == Kind.KEY || travel == null) {
            return byRate;
        }
        Kind tier = byRate.pick().kind();
        boolean secretTier = party && (tier == Kind.SECRET || tier == Kind.KEY);
        List<Candidate> pool = new ArrayList<>();
        for (Candidate c : byRate.ranked()) {
            if (c.kind() == Kind.KEY || c.gain() <= 0) {
                continue;
            }
            boolean secret = c.kind() == Kind.SECRET;
            if (!party || secretTier == secret) {
                pool.add(c);
            }
        }
        if (pool.size() <= 1) {
            return byRate;
        }
        int n = pool.size();
        double[] fromHere = new double[n];
        double[][] between = new double[n][n];
        double[] weight = new double[n];
        double[] work = new double[n];
        for (int i = 0; i < n; i++) {
            Candidate c = pool.get(i);
            fromHere[i] = Math.max(0, travel.fromHere(c.room()));
            weight[i] = c.gain();
            work[i] = Math.max(0, c.seconds() - fromHere[i]);
            for (int j = 0; j < n; j++) {
                between[i][j] = i == j ? 0 : Math.max(0, travel.between(c.room(), pool.get(j).room()));
            }
        }
        TourPlanner.Result res = TourPlanner.plan(fromHere, between, weight, work, budgetNanos);
        List<Candidate> tour = new ArrayList<>();
        for (int i : res.order()) {
            tour.add(pool.get(i));
        }
        int[] rateOrder = new int[n];
        for (int i = 0; i < n; i++) {
            rateOrder[i] = i;   // the pool is in rate order already
        }
        double[] mine = TourPlanner.evaluate(res.order(), fromHere, between, weight, work);
        double[] rate = TourPlanner.evaluate(rateOrder, fromHere, between, weight, work);
        String why = String.format(java.util.Locale.US, "%s: best order for score by the second (score-weighted wait %.1f,"
                        + " all in %.1f s; best-per-second-next %.1f, %.1f s)", party ? "Party, " + (secretTier
                        ? "secrets first" : "nothing left but this tier") : "Solo", mine[0], mine[1], rate[0], rate[1]);
        return new Choice(tour.get(0), why, byRate.ranked(), tour);
    }

    /**
     * Seconds everything this choice still wants takes (killer560, 2026-10-18: an estimated time till completion "based
     * off the auto routes it has left and the pathfinding and puzzles"): the tour in its order - the trip from here to
     * the first, each candidate's own work, the trips between them - and then every other ranked candidate worth score
     * at its own estimate (its trip from here plus its work; a dropped key is not counted, it is not work left). Each
     * candidate's seconds already price its route by nodes, a clear and a puzzle by their learned or guessed time.
     */
    public static double estimateSeconds(Choice c, Travel travel) {
        if (c == null) {
            return Double.NaN;
        }
        double total = 0.0;
        java.util.Set<Candidate> counted = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        String prev = null;
        for (Candidate k : c.tour()) {
            double from = travel == null ? 0.0 : Math.max(0.0, travel.fromHere(k.room()));
            double work = Math.max(0.0, k.seconds() - from);
            double trip = prev == null || travel == null ? from : Math.max(0.0, travel.between(prev, k.room()));
            total += trip + work;
            prev = k.room();
            counted.add(k);
        }
        for (Candidate k : c.ranked()) {
            if (!counted.contains(k) && k.kind() != Kind.KEY && k.gain() > 0) {
                total += Math.max(0.0, k.seconds());
            }
        }
        return total;
    }

    /** Blood First: the uncleared room furthest back on the rush path (a teammate's room left to them), or null. */
    public static Choice chooseBloodFirst(List<Candidate> all) {
        List<Candidate> rush = new ArrayList<>();
        for (Candidate c : all) {
            if (c.kind() == Kind.CLEAR && c.rushIndex() >= 0 && c.gain() > 0 && !c.teammateInside()) {
                rush.add(c);
            }
        }
        rush.sort(Comparator.comparingInt(Candidate::rushIndex).thenComparing(Candidate::room));
        return rush.isEmpty() ? null : new Choice(rush.get(0), "Blood First: clearing the blood rush path in order", rush);
    }
}
