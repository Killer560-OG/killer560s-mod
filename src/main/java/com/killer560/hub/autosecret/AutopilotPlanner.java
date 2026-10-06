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

    /** The pick, why, and every candidate in the order it was weighed (best first). */
    public record Choice(Candidate pick, String why, List<Candidate> ranked) {
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
