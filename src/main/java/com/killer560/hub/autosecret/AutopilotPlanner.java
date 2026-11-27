package com.killer560.hub.autosecret;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
     * Fastest to 300 (acplan-1121, killer560 2026-11-21: "plan clear vs secrets per room to reach score 300 fastest", not a
     * tour of the map clearing and a second one for secrets). Solo: every clear, puzzle and unexplored room is needed (300
     * needs every room), plus the cheapest secrets that cover what S+ still needs ({@code secretScoreNeeded}): a secret in
     * a room already being visited costs only its route, any other also the detour there and back. Each room is then ONE
     * visit (clear, puzzle, then its route) and the visits go in the order with the least travel, because the work is the
     * same in any order, so least travel is soonest 300. Party (secrets first): the secret rooms covering the need, in
     * least-travel order, then the rest of its secrets the same way; clears stay the team's ({@link #choose}'s tiers).
     * A dropped key that is best by rate is still taken first. Exact up to {@link #EXACT_SITES} rooms, else the best order
     * the least-travel search finds within {@code budgetNanos}.
     */
    public static Choice chooseFastest(boolean party, List<Candidate> all, Travel travel, double secretScoreNeeded,
                                       boolean clearBeforeSecret, long budgetNanos) {
        return chooseFastest(party, all, travel, secretScoreNeeded, clearBeforeSecret, budgetNanos, Shape.NONE);
    }

    /** {@link #chooseFastest} with the visits ordered by Room Order and Allow Insta Clearing ({@link Shape}). */
    public static Choice chooseFastest(boolean party, List<Candidate> all, Travel travel, double secretScoreNeeded,
                                       boolean clearBeforeSecret, long budgetNanos, Shape shape) {
        Choice byRate = choose(party, all);
        if (byRate == null || byRate.pick().kind() == Kind.KEY || travel == null) {
            return byRate;
        }
        List<Candidate> needed = new ArrayList<>();
        List<Candidate> secrets = new ArrayList<>();
        for (Candidate c : byRate.ranked()) {
            if (c.gain() <= 0 || c.kind() == Kind.KEY) {
                continue;
            }
            if (c.kind() == Kind.SECRET) {
                secrets.add(c);
            } else if (!party) {
                needed.add(c);
            }
        }
        if (party && secrets.isEmpty()) {
            return chooseTour(true, all, travel, budgetNanos);
        }
        java.util.Set<String> visited = new java.util.HashSet<>();
        for (Candidate c : needed) {
            visited.add(c.room());
        }
        // Cheapest secrets first by seconds per score: a visited room's route alone, any other also its detour. With a
        // Skip Unnecessary Secrets plan, the planned rooms come first (a skipped room only if they no longer cover it).
        List<Candidate> bySecond = new ArrayList<>(secrets);
        java.util.Set<String> plan = shape.skipPlan();
        bySecond.sort(Comparator.comparingInt((Candidate c) -> plan == null || plan.contains(c.room()) ? 0 : 1)
                .thenComparingDouble(c -> secretCost(c, visited, travel) / c.gain()).thenComparing(Candidate::room));
        double covered = 0;
        List<Candidate> chosen = new ArrayList<>();
        for (Candidate c : bySecond) {
            if (covered >= secretScoreNeeded - 1e-9) {
                break;
            }
            chosen.add(c);
            covered += c.gain();
        }
        if (party && chosen.isEmpty()) {
            chosen.addAll(secrets);   // the need is met: Party keeps going for the spare ones
        }
        needed.addAll(chosen);
        if (party && clearBeforeSecret) {
            needed.addAll(clearsOf(chosen, byRate.ranked()));
        }
        if (needed.isEmpty()) {
            return byRate;
        }
        Visits v = visits(needed, travel, budgetNanos, shape);
        String why = String.format(Locale.US, "%s: fastest to %s - %d room visit(s), %d route(s) for %.1f of %.1f secret"
                        + " score, travel %.1f s, all in %.1f s%s", party ? "Party, secrets first" : "Solo",
                party ? "the secret need" : "300", v.rooms(), chosen.size(), covered, secretScoreNeeded, v.travel(),
                v.travel() + v.work(), v.note());
        return new Choice(v.tour().get(0), why, byRate.ranked(), v.tour());
    }

    /**
     * A mob clear is still worth something and no teammate stands in it (Clear First: mob clears only; puzzles keep their
     * own switches, killer560 2026-11-21).
     */
    public static boolean clearsLeft(List<Candidate> all) {
        for (Candidate c : all) {
            if (c.gain() > 0 && c.kind() == Kind.CLEAR && !c.teammateInside()) {
                return true;
            }
        }
        return false;
    }

    /**
     * A fixed order for the Run Mode switch (killer560 2026-11-21, one Dungeons Autopilot with Clear Then Secret and Secret
     * Then Clear beside Solo 300): {@code clearsFirst} takes every clear, puzzle and unexplored room before any secret,
     * else every secret first; each phase is one visit per room in the least-travel order. A dropped key best by rate is
     * still taken first. Clears in a room a teammate is in stay out, as in {@link #choose}.
     */
    public static Choice chooseOrdered(boolean clearsFirst, List<Candidate> all, Travel travel, boolean clearBeforeSecret,
                                       long budgetNanos) {
        return chooseOrdered(clearsFirst, all, travel, clearBeforeSecret, budgetNanos, Shape.NONE);
    }

    /** {@link #chooseOrdered} with the visits ordered by Room Order and Allow Insta Clearing ({@link Shape}). */
    public static Choice chooseOrdered(boolean clearsFirst, List<Candidate> all, Travel travel, boolean clearBeforeSecret,
                                       long budgetNanos, Shape shape) {
        Choice byRate = choose(false, all);
        if (byRate == null || byRate.pick().kind() == Kind.KEY || travel == null) {
            return byRate;
        }
        List<Candidate> clears = new ArrayList<>();
        List<Candidate> secrets = new ArrayList<>();
        for (Candidate c : byRate.ranked()) {
            if (c.gain() <= 0 || c.kind() == Kind.KEY) {
                continue;
            }
            if (c.kind() == Kind.SECRET) {
                secrets.add(c);
            } else if (!c.teammateInside()) {
                clears.add(c);
            }
        }
        List<Candidate> phase = clearsFirst ? (clears.isEmpty() ? secrets : clears) : (secrets.isEmpty() ? clears : secrets);
        if (phase.isEmpty()) {
            return byRate;
        }
        if (phase == secrets && clearBeforeSecret) {
            phase = new ArrayList<>(secrets);
            phase.addAll(clearsOf(secrets, byRate.ranked()));
        }
        Visits v = visits(phase, travel, budgetNanos, shape);
        String why = String.format(Locale.US, "%s: %s - %d room visit(s), travel %.1f s, all in %.1f s%s",
                clearsFirst ? "Clear First" : "Secret Then Clear", phase == clears ? "clears" : "secrets", v.rooms(),
                v.travel(), v.travel() + v.work(), v.note());
        return new Choice(v.tour().get(0), why, byRate.ranked(), v.tour());
    }

    /**
     * Clear Room Before Secreting (killer560 2026-11-21: "add an option to clear room before secreting it"): the clears of
     * the rooms {@code secrets} visits, so each is cleared in the same visit before its route. A room a teammate is in is
     * left to him.
     */
    static List<Candidate> clearsOf(List<Candidate> secrets, List<Candidate> ranked) {
        java.util.Set<String> rooms = new java.util.HashSet<>();
        for (Candidate c : secrets) {
            rooms.add(c.room());
        }
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : ranked) {
            if (c.kind() == Kind.CLEAR && c.gain() > 0 && !c.teammateInside() && rooms.contains(c.room())) {
                out.add(c);
            }
        }
        return out;
    }

    /** A plan of room visits: candidates in visit order, rooms visited, seconds of travel and of work (insta savings
     *  taken off), and a note for the decide line (Room Order, each insta clear counted). */
    record Visits(List<Candidate> tour, int rooms, double travel, double work, String note) {
    }

    /**
     * Room Order and Allow Insta Clearing for {@link #visits} (autopilot-menu-1126). {@code order}: the Room Order setting;
     * {@code size}: each room's tiles (a room missing counts 1); {@code neighbours}: the rooms each room has a door to;
     * {@code root}: the Entrance's key (Edges In's middle); {@code instaSaving}: the seconds a room's clear saves when its
     * first entry is an etherwarp while the map has not shown it (only rooms where that is allowed; a room is shown once he
     * stands in a room next door, so the saving holds only while no neighbour of it is visited first).
     */
    public record Shape(AutoSecretConfig.SecretOrder order, Map<String, Integer> size, Map<String, List<String>> neighbours,
                        String root, Map<String, Double> instaSaving, java.util.Set<String> skipPlan) {
        public static final Shape NONE = new Shape(AutoSecretConfig.SecretOrder.LEAST_ETHERWARPS, Map.of(), Map.of(), null,
                Map.of(), null);
    }

    /**
     * Skip Unnecessary Secrets' floor plan (killer560, 2026-11-26: "plan that from the second you can see the full map for
     * which secrets you want to skip"): the secret rooms to take, the cheapest set by real seconds over the WHOLE floor
     * whose gain covers {@code need}. A room's price is its route's work, plus - when no clear or puzzle visit goes there
     * anyway - twice the trip from the nearest room that is visited anyway (never from where he stands now, so the plan
     * does not drift as he moves). Cheapest seconds per score first. Pure, for the testkit.
     * @return the room keys to take secrets in (the rest are skipped), in the order chosen
     */
    public static List<String> skipPlan(List<Candidate> all, Travel travel, double need) {
        java.util.Set<String> visited = new java.util.HashSet<>();
        List<Candidate> secrets = new ArrayList<>();
        for (Candidate c : all) {
            if (c.gain() <= 0 || c.kind() == Kind.KEY) {
                continue;
            }
            if (c.kind() == Kind.SECRET) {
                secrets.add(c);
            } else {
                visited.add(c.room());
            }
        }
        java.util.function.ToDoubleFunction<Candidate> price = c -> {
            double in = Math.max(0, travel.fromHere(c.room()));
            double work = Math.max(0, c.seconds() - in);
            if (visited.contains(c.room())) {
                return work;
            }
            double near = visited.isEmpty() ? in : Double.MAX_VALUE;
            for (String v : visited) {
                near = Math.min(near, Math.max(0, travel.between(v, c.room())));
            }
            return work + 2 * near;
        };
        secrets.sort(Comparator.comparingDouble((Candidate c) -> price.applyAsDouble(c) / c.gain()).thenComparing(Candidate::room));
        List<String> out = new ArrayList<>();
        double covered = 0;
        for (Candidate c : secrets) {
            if (covered >= need - 1e-9) {
                break;
            }
            out.add(c.room());
            covered += c.gain();
        }
        return out;
    }

    static Visits visits(List<Candidate> needed, Travel travel, long budgetNanos) {
        return visits(needed, travel, budgetNanos, Shape.NONE);
    }

    /** One visit per room (its clear and puzzle, then its route), the visits in Room Order (Fastest Time: least travel). */
    static Visits visits(List<Candidate> needed, Travel travel, long budgetNanos, Shape shape) {
        java.util.Map<String, List<Candidate>> rooms = new java.util.LinkedHashMap<>();
        for (Candidate c : needed) {
            rooms.computeIfAbsent(c.room(), k -> new ArrayList<>()).add(c);
        }
        List<String> keys = new ArrayList<>(rooms.keySet());
        int n = keys.size();
        double[] fromHere = new double[n];
        double[][] between = new double[n][n];
        double work = 0;
        for (int i = 0; i < n; i++) {
            fromHere[i] = Math.max(0, travel.fromHere(keys.get(i)));
            for (int j = 0; j < n; j++) {
                between[i][j] = i == j ? 0 : Math.max(0, travel.between(keys.get(i), keys.get(j)));
            }
            for (Candidate c : rooms.get(keys.get(i))) {
                work += Math.max(0, c.seconds() - fromHere[i]);
            }
        }
        AutoSecretConfig.SecretOrder mode = shape.order() == null ? AutoSecretConfig.SecretOrder.LEAST_ETHERWARPS
                : shape.order();
        int[] order = switch (mode) {
            case LARGEST_TO_SMALLEST, SMALLEST_TO_LARGEST -> byRank(keys, fromHere, k -> {
                int size = shape.size().getOrDefault(k, 1);
                return mode == AutoSecretConfig.SecretOrder.LARGEST_TO_SMALLEST ? -size : size;
            });
            case EDGES_IN -> {
                List<String> rank = edgesIn(shape.neighbours(), shape.root());
                yield byRank(keys, fromHere, k -> rank.contains(k) ? rank.indexOf(k) : Integer.MAX_VALUE);
            }
            default -> leastTravel(fromHere, between, budgetNanos);
        };
        // Allow Insta Clearing: a clear whose room is still unshown costs its saving less; Fastest Time also moves such a
        // room ahead of its first neighbour when that is sooner overall (the neighbour's visit would show it).
        java.util.Map<String, Double> saving = new java.util.HashMap<>();
        for (String k : keys) {
            double s = shape.instaSaving().getOrDefault(k, 0.0);
            if (s > 0 && rooms.get(k).stream().anyMatch(c -> c.kind() == Kind.CLEAR)) {
                saving.put(k, s);
            }
        }
        if (mode == AutoSecretConfig.SecretOrder.LEAST_ETHERWARPS && !saving.isEmpty()) {
            order = instaOrder(order, keys, fromHere, between, saving, shape.neighbours());
        }
        List<String> held = heldInsta(order, keys, saving, shape.neighbours());
        double saved = 0;
        for (String k : held) {
            saved += saving.get(k);
        }
        List<Candidate> tour = new ArrayList<>();
        for (int i : order) {
            List<Candidate> here = rooms.get(keys.get(i));
            here.sort(Comparator.comparingInt((Candidate c) -> c.kind() == Kind.SECRET ? 1 : 0));
            tour.addAll(here);
        }
        StringBuilder note = new StringBuilder();
        if (mode != AutoSecretConfig.SecretOrder.LEAST_ETHERWARPS) {
            note.append(", room order ").append(mode.label());
        }
        for (String k : held) {
            note.append(String.format(Locale.US, ", insta clear %s (-%.1f s)", k, saving.get(k)));
        }
        for (String k : saving.keySet()) {
            if (!held.contains(k)) {
                note.append(", no insta clear for ").append(k).append(" (a room next door comes first)");
            }
        }
        return new Visits(tour, n, TourPlanner.travel(order, fromHere, between), Math.max(0, work - saved), note.toString());
    }

    /** {@code keys} by {@code rank} (lower first), then nearer first, then by name: the fixed Room Orders. */
    private static int[] byRank(List<String> keys, double[] fromHere, java.util.function.ToIntFunction<String> rank) {
        Integer[] idx = new Integer[keys.size()];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, Comparator.comparingInt((Integer i) -> rank.applyAsInt(keys.get(i)))
                .thenComparingDouble(i -> fromHere[i]).thenComparing(keys::get));
        int[] out = new int[idx.length];
        for (int i = 0; i < idx.length; i++) {
            out[i] = idx[i];
        }
        return out;
    }

    /**
     * Edges In (killer560, 2026-11-26: "it just goes to the end of splits and clears back towards the middle"): the rooms
     * in post-order of the breadth-first tree from {@code root} over the doors, the deepest branch first, so each branch
     * is taken from its far end back toward the Entrance before the next one starts (never far corner to far corner).
     * Rooms the tree does not reach follow by name. Pure, for the testkit.
     */
    public static List<String> edgesIn(Map<String, List<String>> neighbours, String root) {
        List<String> out = new ArrayList<>();
        if (root == null || !neighbours.containsKey(root)) {
            List<String> all = new ArrayList<>(neighbours.keySet());
            all.sort(null);
            return all;
        }
        Map<String, List<String>> children = new java.util.HashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>(List.of(root));
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(List.of(root));
        List<String> bfs = new ArrayList<>();
        while (!queue.isEmpty()) {
            String at = queue.poll();
            bfs.add(at);
            List<String> next = new ArrayList<>(neighbours.getOrDefault(at, List.of()));
            next.sort(null);
            for (String nb : next) {
                if (seen.add(nb)) {
                    children.computeIfAbsent(at, k -> new ArrayList<>()).add(nb);
                    queue.add(nb);
                }
            }
        }
        Map<String, Integer> height = new java.util.HashMap<>();
        for (int i = bfs.size() - 1; i >= 0; i--) {
            int h = 0;
            for (String c : children.getOrDefault(bfs.get(i), List.of())) {
                h = Math.max(h, height.get(c) + 1);
            }
            height.put(bfs.get(i), h);
        }
        postOrder(root, children, height, out);
        List<String> rest = new ArrayList<>();
        for (String k : neighbours.keySet()) {
            if (!seen.contains(k)) {
                rest.add(k);
            }
        }
        rest.sort(null);
        out.addAll(rest);
        return out;
    }

    private static void postOrder(String at, Map<String, List<String>> children, Map<String, Integer> height,
                                  List<String> out) {
        List<String> kids = new ArrayList<>(children.getOrDefault(at, List.of()));
        kids.sort(Comparator.comparingInt((String k) -> -height.get(k)).thenComparing(k -> k));
        for (String k : kids) {
            postOrder(k, children, height, out);
        }
        out.add(at);
    }

    /** The insta rooms whose saving holds in {@code order}: none of their neighbours is visited before them. */
    static List<String> heldInsta(int[] order, List<String> keys, Map<String, Double> saving,
                                  Map<String, List<String>> neighbours) {
        List<String> held = new ArrayList<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        for (int i : order) {
            String k = keys.get(i);
            if (saving.containsKey(k) && neighbours.getOrDefault(k, List.of()).stream().noneMatch(visited::contains)) {
                held.add(k);
            }
            visited.add(k);
        }
        return held;
    }

    /**
     * Fastest Time with insta clears: each insta room whose saving the least-travel order loses (a neighbour comes first)
     * is tried just before that neighbour, biggest saving first, and kept there when travel less savings is lower.
     */
    static int[] instaOrder(int[] order, List<String> keys, double[] fromHere, double[][] between, Map<String, Double> saving,
                            Map<String, List<String>> neighbours) {
        int[] best = order;
        double bestCost = instaCost(best, keys, fromHere, between, saving, neighbours);
        List<String> byGain = new ArrayList<>(saving.keySet());
        byGain.sort(Comparator.comparingDouble((String k) -> -saving.get(k)).thenComparing(k -> k));
        // autopilot-instaclear-roomcost-1126: every slot before its first neighbour is tried (not only the one right
        // before it), and the passes repeat while one helps, since moving one room can free another.
        for (int pass = 0; pass < 4; pass++) {
            boolean moved = false;
            for (String k : byGain) {
                if (heldInsta(best, keys, saving, neighbours).contains(k)) {
                    continue;
                }
                int me = keys.indexOf(k);
                List<Integer> rest = new ArrayList<>();
                for (int i : best) {
                    if (i != me) {
                        rest.add(i);
                    }
                }
                int first = 0;
                while (first < rest.size() && !neighbours.getOrDefault(k, List.of()).contains(keys.get(rest.get(first)))) {
                    first++;
                }
                for (int at = 0; at <= first; at++) {
                    List<Integer> trialList = new ArrayList<>(rest);
                    trialList.add(at, me);
                    int[] trial = trialList.stream().mapToInt(Integer::intValue).toArray();
                    double cost = instaCost(trial, keys, fromHere, between, saving, neighbours);
                    if (cost < bestCost - 1e-9) {
                        best = trial;
                        bestCost = cost;
                        moved = true;
                    }
                }
            }
            if (!moved) {
                break;
            }
        }
        return best;
    }

    private static double instaCost(int[] order, List<String> keys, double[] fromHere, double[][] between,
                                    Map<String, Double> saving, Map<String, List<String>> neighbours) {
        double cost = TourPlanner.travel(order, fromHere, between);
        for (String k : heldInsta(order, keys, saving, neighbours)) {
            cost -= saving.get(k);
        }
        return cost;
    }

    /** Rooms the least-travel order is exact for (Held-Karp, about a millisecond at 12). */
    static final int EXACT_SITES = 12;

    /** The order through every site with the least travel, open-ended (exact up to {@link #EXACT_SITES}). */
    static int[] leastTravel(double[] fromHere, double[][] between, long budgetNanos) {
        int n = fromHere.length;
        if (n <= 1) {
            return n == 0 ? new int[0] : new int[]{0};
        }
        if (n <= EXACT_SITES) {
            return TourPlanner.heldKarp(fromHere, between).order();
        }
        long deadline = System.nanoTime() + Math.max(0, budgetNanos);
        return LeastWarpSearch.branchAndBound(fromHere, between, () -> System.nanoTime() > deadline).order();
    }

    /** What taking {@code c} adds: its route, and when its room is not visited anyway, the trip there and back. */
    private static double secretCost(Candidate c, java.util.Set<String> visited, Travel travel) {
        double in = Math.max(0, travel.fromHere(c.room()));
        double work = Math.max(0, c.seconds() - in);
        if (visited.contains(c.room())) {
            return work;
        }
        double near = in;
        for (String v : visited) {
            near = Math.min(near, Math.max(0, travel.between(v, c.room())));
        }
        return work + 2 * near;
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

    /**
     * Clear before secrets in the same room (killer560, 2026-10-20: "if something goes to a room for the first time during
     * auto pilot it tends to secret it then clear it, try to clear it first as secreting it first leads to mobs going
     * everywhere"). A SECRET pick in a room whose CLEAR is also a candidate becomes that CLEAR; the room it cleared first
     * this way ({@code clearedFirst}, a candidate key) has its SECRET taken next, while it is still worth score, ahead of
     * whatever else was picked. A dropped-key pick is left alone. A room with no CLEAR candidate (no mobs, already cleared,
     * counted cleared by the chest rule) or with a teammate in it is not touched. {@link Autopilot} applies it in Solo only.
     * @return the choice to take (the same one when nothing applies)
     */
    /**
     * Allow Insta Clearing at the step (autopilot-menu-1126, his 11-26 F7: Melon and Atlas were counted as insta clears, then
     * a visit next door showed them on the map first and both were killed by hand): when the pick's room is next door to
     * a room still credited an insta clear ({@code sh.instaSaving}), that room's clear goes first, while the map has not
     * shown it, if the detour costs less than the clear it saves. Otherwise {@code c} unchanged.
     */
    public static Choice instaNextDoor(Choice c, List<Candidate> all, Travel t, Shape sh) {
        return instaNextDoor(c, all, t, sh, null);
    }

    /**
     * {@link #instaNextDoor} on the whole way there (autopilot-instaclear-roomcost-1126, killer560 2026-11-26: "I don't want
     * it to jump through the room before quiz if that room is something that it should insta-clear"): the rooms on the
     * door path from {@code from} (the room he stands in; null: only the pick's own neighbours) to the pick are entered on
     * the way, each showing its neighbours, so a credited room among them or next to them goes first when its saving beats
     * the detour; of several, the biggest net saving.
     */
    public static Choice instaNextDoor(Choice c, List<Candidate> all, Travel t, Shape sh, String from) {
        if (c == null || c.pick().kind() == Kind.KEY) {
            return c;
        }
        Candidate best = instaBefore(c.pick().room(), all, t, sh, from);
        if (best == null) {
            return c;
        }
        double net = sh.instaSaving().get(best.room()) - detour(t, best.room(), c.pick().room());
        return new Choice(best, String.format(Locale.US, "insta clear %s first: the way to %s would show it on the map"
                + " (saves %.1f s net)", best.room(), c.pick().room(), net), c.ranked(), fronted(c.tour(), best));
    }

    /** The credited insta room the trip from {@code from} to {@code at} would show (on the door path or next to it) whose
     *  saving beats the detour most, or null. Pure, for the testkit. */
    public static Candidate instaBefore(String at, List<Candidate> all, Travel t, Shape sh, String from) {
        if (sh.instaSaving().isEmpty() || at == null) {
            return null;
        }
        java.util.Set<String> shown = new java.util.HashSet<>(sh.neighbours().getOrDefault(at, List.of()));
        for (String p : doorPath(sh.neighbours(), from, at)) {
            shown.add(p);
            shown.addAll(sh.neighbours().getOrDefault(p, List.of()));
        }
        Candidate best = null;
        double bestNet = 0;
        for (Candidate k : all) {
            Double save = sh.instaSaving().get(k.room());
            if (k.kind() != Kind.CLEAR || save == null || k.room().equals(at) || k.teammateInside() || !shown.contains(k.room())) {
                continue;
            }
            double net = save - detour(t, k.room(), at);
            if (net > bestNet) {
                best = k;
                bestNet = net;
            }
        }
        return best;
    }

    /** What going through {@code via} first adds: the leg from it on to {@code at} beyond the straight trip. The trip into
     *  {@code via} itself is not counted, since its clear needs a visit anyway (now as an insta clear instead of later as a
     *  kill clear): killer560 2026-11-26, "insta-clearing is going to 100% of the time be faster than clearing a room". */
    private static double detour(Travel t, String via, String at) {
        return Math.max(0, Math.max(0, t.between(via, at)) - Math.max(0, t.fromHere(at)));
    }

    /** The rooms strictly between {@code from} and {@code at} on the fewest-doors path (empty when next door, unknown or
     *  {@code from} is null). */
    static List<String> doorPath(Map<String, List<String>> neighbours, String from, String at) {
        if (from == null || from.equals(at) || !neighbours.containsKey(from)) {
            return List.of();
        }
        Map<String, String> back = new java.util.HashMap<>();
        java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>();
        q.add(from);
        back.put(from, from);
        while (!q.isEmpty() && !back.containsKey(at)) {
            String x = q.poll();
            for (String n : neighbours.getOrDefault(x, List.of())) {
                if (back.putIfAbsent(n, x) == null) {
                    q.add(n);
                }
            }
        }
        if (!back.containsKey(at)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String x = back.get(at); !x.equals(from); x = back.get(x)) {
            out.add(x);
        }
        return out;
    }

    /** Rooms that can be insta cleared at all (killer560 2026-11-26: "it is impossible to insta-clear trap or any puzzles.
     *  You can insta-clear normal rooms and yellow room"): the database's NORMAL, RARE and CHAMPION (the yellow miniboss
     *  room) types; never PUZZLE, TRAP, FAIRY, BLOOD or ENTRANCE. */
    public static boolean instaType(String type) {
        return type != null && switch (type.toUpperCase(Locale.ROOT)) {
            case "NORMAL", "RARE", "CHAMPION" -> true;
            default -> false;
        };
    }

    public static Choice clearFirst(Choice c, List<Candidate> all, String clearedFirst) {
        if (c == null || c.pick().kind() == Kind.KEY) {
            return c;
        }
        boolean stillToClear = false;
        for (Candidate k : all) {
            stillToClear |= k.kind() == Kind.CLEAR && k.room().equals(clearedFirst);
        }
        // Only once its clear is over (no CLEAR left for it): a clear that never ran must not let its secrets jump ahead.
        if (clearedFirst != null && !stillToClear
                && !(c.pick().kind() == Kind.SECRET && c.pick().room().equals(clearedFirst))) {
            for (Candidate k : all) {
                if (k.kind() == Kind.SECRET && k.room().equals(clearedFirst) && k.gain() > 0) {
                    return new Choice(k, "cleared first - its secrets now (was: " + c.pick().describe() + ")", c.ranked(),
                            fronted(c.tour(), k));
                }
            }
        }
        if (c.pick().kind() != Kind.SECRET) {
            return c;
        }
        for (Candidate k : all) {
            if (k.kind() == Kind.CLEAR && k.room().equals(c.pick().room()) && !k.teammateInside()) {
                return new Choice(k, "clear first: mobs dead before its secrets (was: " + c.pick().describe() + ")",
                        c.ranked(), fronted(c.tour(), k));
            }
        }
        return c;
    }

    /** The tour it now takes: {@code first}, then the rest in the order planned (empty stays empty: picked by rate). */
    private static List<Candidate> fronted(List<Candidate> tour, Candidate first) {
        if (tour.isEmpty()) {
            return tour;
        }
        List<Candidate> out = new ArrayList<>();
        out.add(first);
        for (Candidate t : tour) {
            if (t != first) {
                out.add(t);
            }
        }
        return out;
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
