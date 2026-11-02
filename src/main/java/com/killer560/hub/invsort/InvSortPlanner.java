package com.killer560.hub.invsort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Plans the fewest container clicks that put a layout in place (killer560, 2026-11-01: "double check that any move you
 * make is the most optimized method that there is no way to do it in less clicks"). Pure Java, no Minecraft classes, so
 * {@code tools/invsort-optimality} can run it against an exhaustive search of the real click rules.
 * <p>
 * <b>The model.</b> Positions 0..n-1 hold whole stacks (an identity, or null for empty); positions below
 * {@code hotbarCount} are hotbar slots. A plan is a list of two kinds of click, the only two that move a whole stack
 * without merging or splitting it:
 * <ul>
 *   <li>{@code PICKUP p} (left click): exchanges the cursor with position p.</li>
 *   <li>{@code SWAP p h} (number key h over p, cursor empty): exchanges position p with hotbar slot h.</li>
 * </ul>
 * Every click is therefore one transposition, and every transposition touches a HUB: the cursor or a hotbar slot.
 * <p>
 * <b>Why this is the minimum</b> (the full argument is in docs/INVSORT-OPTIMALITY.md). Fix which stack ends where: that is
 * a permutation, and it splits into cycles. A cycle of length L needs at least L-1 transpositions (each transposition
 * changes the number of cycles by exactly one). A cycle with no hub in it needs L+1: none of its positions can be
 * touched without first joining a hub's cycle (one extra join), which must be split off again (one extra split). A cycle
 * holding an empty slot but no hotbar slot needs L, because the cursor's own emptiness can stand in for that slot's. So
 * for a fixed permutation the minimum is the sum of (L-1) + 0 / 1 / 2 per cycle, and this class reaches it exactly:
 * hotbar cycles by number-key swaps around one hotbar slot, the rest by carrying the cursor round the cycle.
 * <p>
 * What is left is choosing the permutation: which copy of an item fills which slot, where a displaced item goes, and
 * which cycles to join. Displaced items with no slot of their own go back to where the moved item came from (a slot
 * the layout does not use); every path with no hub in it is spliced into a cycle that has one, or into one shared cycle.
 * Copies of the same item are tried exhaustively (with a budget; past it the plan is still valid but
 * {@link Plan#provenMinimal} is false). Moves that split, merge, shift-click or drag stacks are never better: the
 * exhaustive check runs them too and none beats this plan on any instance.
 */
public final class InvSortPlanner {

    /** One click. {@code hotbar} is the number-key slot for SWAP, -1 for PICKUP. */
    public record Click(boolean swap, int slot, int hotbar) {
        static Click pickup(int slot) {
            return new Click(false, slot, -1);
        }

        static Click swapWith(int slot, int hotbar) {
            return new Click(true, slot, hotbar);
        }

        @Override
        public String toString() {
            return swap ? "SWAP " + slot + "<->" + hotbar : "PICKUP " + slot;
        }
    }

    /**
     * @param clicks        the clicks, in order
     * @param unresolved    layout slots left as they are (their item is not in the inventory, or is locked)
     * @param lowerBound    the cycle bound for the permutation chosen: equal to {@code clicks.size()}
     * @param provenMinimal true when every choice of copies was compared (false only past the search budget)
     */
    public record Plan(List<Click> clicks, Set<Integer> unresolved, int lowerBound, boolean provenMinimal) {
    }

    /** Assignments compared before the search gives up on proving the plan minimal and keeps the best so far. */
    static final int SEARCH_BUDGET = 200_000;

    private final int n;
    private final int hotbarCount;
    private final String[] cur;
    private final boolean[] locked;
    private final Map<Integer, String> want;

    // Search state.
    private final List<Integer> demands = new ArrayList<>();
    private final Set<Integer> wrongSet = new java.util.HashSet<>();
    private final Map<Integer, List<Integer>> options = new HashMap<>();
    private final int[] choice;
    private int[] bestChoice;
    private int bestSatisfied = -1;
    private int bestCost = Integer.MAX_VALUE;
    private int evaluations;
    private boolean budgetHit;

    private InvSortPlanner(String[] cur, boolean[] locked, Map<Integer, String> want, int hotbarCount) {
        this.n = cur.length;
        this.hotbarCount = hotbarCount;
        this.cur = cur;
        this.locked = locked == null ? new boolean[n] : locked;
        this.want = want;
        this.choice = new int[n];
    }

    /**
     * @param cur         identity per position (null = empty), compared case-insensitively
     * @param locked      positions never clicked (Item Protect's Lock In Place), or null
     * @param want        layout: position -> identity
     * @param hotbarCount positions below this are hotbar slots (9 in the real inventory)
     */
    public static Plan plan(String[] cur, boolean[] locked, Map<Integer, String> want, int hotbarCount) {
        return plan(cur, locked, want, hotbarCount, SEARCH_BUDGET);
    }

    /** {@link #plan} with its own search budget (the executor plans on the render thread and uses a smaller one). */
    public static Plan plan(String[] cur, boolean[] locked, Map<Integer, String> want, int hotbarCount, int budget) {
        InvSortPlanner p = new InvSortPlanner(cur, locked, want, hotbarCount);
        p.budget = budget;
        return p.run();
    }

    private int budget = SEARCH_BUDGET;

    private boolean hot(int p) {
        return p < hotbarCount;
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private Plan run() {
        Set<Integer> unresolved = new TreeSet<>();
        // Wrong layout slots, in slot order, and the identities they need.
        Set<String> needed = new java.util.HashSet<>();
        List<Integer> wrong = new ArrayList<>();
        for (Map.Entry<Integer, String> e : new java.util.TreeMap<>(want).entrySet()) {
            int t = e.getKey();
            if (t < 0 || t >= n || same(cur[t], e.getValue())) {
                continue;
            }
            if (locked[t]) {
                unresolved.add(t);
                continue;
            }
            wrong.add(t);
            needed.add(e.getValue().toUpperCase(java.util.Locale.ROOT));
            if (cur[t] != null) {
                needed.add(cur[t].toUpperCase(java.util.Locale.ROOT)); // displaced: a slot of its own kind may take it
            }
        }
        // A layout slot that is already right still takes part when a wrong slot needs the same item: its copy may go to
        // the wrong slot while another copy comes back to it. That is how a hotbar slot holding a matching copy brings
        // a hub into a cycle that had none (the exhaustive check found it: [A,-,B,A,-] wanting 0=A 2=A 3=B is two swaps
        // through slot 0, not three cursor clicks).
        for (Map.Entry<Integer, String> e : new java.util.TreeMap<>(want).entrySet()) {
            int t = e.getKey();
            if (t >= 0 && t < n && !locked[t] && same(cur[t], e.getValue())
                    && needed.contains(e.getValue().toUpperCase(java.util.Locale.ROOT))) {
                demands.add(t);
            }
        }
        demands.addAll(wrong);
        demands.sort(null);
        wrongSet.addAll(wrong);
        // Sources for each demand: any unlocked position holding that identity. Free positions (not in the layout) of
        // one kind (hotbar / main) are interchangeable for the cost, so only as many of each kind as there are demands
        // for that identity are offered (fewer branches, same optimum).
        Map<String, Integer> demandCount = new HashMap<>();
        for (int t : demands) {
            demandCount.merge(want.get(t).toUpperCase(java.util.Locale.ROOT), 1, Integer::sum);
        }
        for (int t : demands) {
            String x = want.get(t);
            String key = x.toUpperCase(java.util.Locale.ROOT);
            List<Integer> opts = new ArrayList<>();
            boolean right = same(cur[t], x);
            if (right) {
                opts.add(t); // stays where it is: tried first
            }
            for (int p = 0; p < n; p++) {
                if (p == t || locked[p] || !same(cur[p], x)) {
                    continue;
                }
                // A slot already right takes a copy only from a slot that is moving anyway, or a free one.
                if (right && want.containsKey(p) && !wrongSet.contains(p)) {
                    continue;
                }
                opts.add(p);
            }
            options.put(t, trimFree(opts, demandCount.get(key)));
        }
        search(0);
        if (bestChoice == null) {
            // No demand at all, or every one unresolvable.
            unresolved.addAll(wrong);
            return new Plan(List.of(), unresolved, 0, true);
        }
        Structure s = build(bestChoice);
        for (int t : demands) {
            if (bestChoice[t] < 0) {
                unresolved.add(t);
            }
        }
        List<Click> clicks = emit(s);
        return new Plan(clicks, unresolved, s.cost, !budgetHit);
    }

    /** Keeps every wrong-slot source, but at most {@code limit} free sources per kind (hotbar, main). */
    private List<Integer> trimFree(List<Integer> opts, int limit) {
        List<Integer> out = new ArrayList<>();
        int freeHot = 0;
        int freeMain = 0;
        for (int p : opts) {
            if (want.containsKey(p)) {
                out.add(p);
            } else if (hot(p) ? freeHot++ < limit : freeMain++ < limit) {
                out.add(p);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------- search

    private void search(int i) {
        if (budgetHit) {
            return;
        }
        if (i == demands.size()) {
            evaluations++;
            if (evaluations > budget) {
                budgetHit = true;
            }
            int satisfied = 0;
            for (int t : demands) {
                if (choice[t] >= 0) {
                    satisfied++;
                }
            }
            if (satisfied < bestSatisfied) {
                return;
            }
            int cost = build(choice).cost;
            if (satisfied > bestSatisfied || cost < bestCost) {
                bestSatisfied = satisfied;
                bestCost = cost;
                bestChoice = choice.clone();
            }
            return;
        }
        int t = demands.get(i);
        String x = want.get(t);
        boolean any = false;
        for (int p : options.get(t)) {
            if (used(p, i)) {
                continue;
            }
            any = true;
            choice[t] = p;
            search(i + 1);
        }
        // Leaving it unresolved is only allowed when there are not enough copies to go round.
        if (!any || copiesShort(x, i)) {
            choice[t] = -1;
            search(i + 1);
        }
    }

    private boolean used(int p, int upTo) {
        for (int j = 0; j < upTo; j++) {
            if (choice[demands.get(j)] == p) {
                return true;
            }
        }
        return false;
    }

    /** Whether the demands for {@code x} outnumber its sources (so one may be left unresolved). */
    private boolean copiesShort(String x, int i) {
        Set<Integer> sources = new TreeSet<>();
        int need = 0;
        for (int t : demands) {
            if (same(want.get(t), x)) {
                need++;
                sources.addAll(options.get(t));
            }
        }
        return need > sources.size();
    }

    // ------------------------------------------------------------------------------------------- structure

    /** The cycles a choice of sources makes, and their cost. */
    private static final class Structure {
        final List<List<Integer>> cycles = new ArrayList<>();
        int cost;
    }

    private Structure build(int[] src) {
        // next[p] = where p's stack goes.
        int[] next = new int[n];
        int[] prev = new int[n];
        java.util.Arrays.fill(next, -1);
        java.util.Arrays.fill(prev, -1);
        boolean[] satisfied = new boolean[n];
        for (int t : demands) {
            int s = src[t];
            if (s >= 0) {
                next[s] = t;
                prev[t] = s;
                satisfied[t] = true;
            }
        }
        Structure out = new Structure();
        boolean[] seen = new boolean[n];
        List<List<Integer>> hosts = new ArrayList<>();
        List<List<Integer>> loose = new ArrayList<>();
        // Paths: they start at a source that receives nothing (a free position, or an unresolved layout slot).
        for (int p = 0; p < n; p++) {
            if (next[p] < 0 || prev[p] >= 0) {
                continue;
            }
            List<Integer> path = new ArrayList<>();
            int q = p;
            while (q >= 0 && !seen[q]) {
                seen[q] = true;
                path.add(q);
                q = next[q];
            }
            // Closed at its head: the head is free, so the last displaced stack may go there.
            int pen = penalty(path);
            if (pen == 2) {
                loose.add(path);
            } else {
                hosts.add(path);
            }
        }
        // Pure cycles of layout slots.
        for (int p = 0; p < n; p++) {
            if (next[p] < 0 || seen[p] || next[p] == p) {
                continue;
            }
            List<Integer> cyc = new ArrayList<>();
            int q = p;
            while (!seen[q]) {
                seen[q] = true;
                cyc.add(q);
                q = next[q];
            }
            out.cycles.add(cyc);
        }
        // Splice every hub-less path into one host: a hotbar or empty-ending path, else a free hotbar slot no source
        // used, else one shared cycle. Each splice saves a click (see the class doc).
        if (!loose.isEmpty()) {
            List<Integer> host = null;
            for (List<Integer> h : hosts) {
                if (penalty(h) == 0) {
                    host = h;
                    break;
                }
            }
            if (host == null) {
                int extra = freeHotbarSlot(next, prev, satisfied);
                if (extra >= 0) {
                    host = new ArrayList<>(List.of(extra));
                    hosts.add(host);
                } else if (!hosts.isEmpty()) {
                    host = hosts.get(0);
                }
            }
            if (host == null) {
                host = loose.remove(0);
                hosts.add(host);
            }
            for (List<Integer> l : loose) {
                host.addAll(l);
            }
        }
        out.cycles.addAll(hosts);
        for (List<Integer> c : out.cycles) {
            if (c.size() > 1) {
                out.cost += c.size() - 1 + penalty(c);
            }
        }
        return out;
    }

    /** 0 when a hotbar slot is in it, else 1 when one of its stacks is empty, else 2. */
    private int penalty(List<Integer> cycle) {
        boolean empty = false;
        for (int p : cycle) {
            if (hot(p)) {
                return 0;
            }
            if (cur[p] == null) {
                empty = true;
            }
        }
        return empty ? 1 : 2;
    }

    /** A hotbar slot the layout does not need right where it is, that no source uses, and not locked; -1 if none. */
    private int freeHotbarSlot(int[] next, int[] prev, boolean[] satisfied) {
        for (int h = 0; h < hotbarCount && h < n; h++) {
            if (locked[h] || next[h] >= 0 || prev[h] >= 0 || satisfied[h]) {
                continue;
            }
            String w = want.get(h);
            if (w == null || !same(cur[h], w)) {
                return h; // free, or an unresolved layout slot (it gets nothing anyway)
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------------------------------- clicks

    private List<Click> emit(Structure s) {
        List<Click> out = new ArrayList<>();
        for (List<Integer> c : s.cycles) {
            if (c.size() < 2) {
                continue;
            }
            int hub = -1;
            int emptyAt = -1;
            for (int i = 0; i < c.size(); i++) {
                if (hot(c.get(i)) && hub < 0) {
                    hub = i;
                }
                if (cur[c.get(i)] == null && emptyAt < 0) {
                    emptyAt = i;
                }
            }
            int len = c.size();
            if (hub >= 0) {
                // Number-key swaps round the hotbar slot: c[hub]'s stack goes to c[hub+1], and so on.
                int h = c.get(hub);
                for (int k = 1; k < len; k++) {
                    out.add(Click.swapWith(c.get((hub + k) % len), h));
                }
            } else if (emptyAt >= 0) {
                // Start just after the empty slot; the last click drops the cursor's stack into it.
                for (int k = 1; k <= len; k++) {
                    out.add(Click.pickup(c.get((emptyAt + k) % len)));
                }
            } else {
                for (int k = 0; k < len; k++) {
                    out.add(Click.pickup(c.get(k)));
                }
                out.add(Click.pickup(c.get(0)));
            }
        }
        return out;
    }

    /**
     * Applies {@code clicks} to {@code cur} by the model's rules (for the checker and the executor's own prediction).
     * @return the final contents, or null when a click is impossible (a SWAP with a stack on the cursor, a merge) or
     * the cursor is not empty at the end
     */
    public static String[] simulate(String[] cur, List<Click> clicks) {
        String[] s = cur.clone();
        String cursor = null;
        for (Click c : clicks) {
            if (c.swap()) {
                if (cursor != null || c.slot() == c.hotbar()) {
                    return null;
                }
                String t = s[c.slot()];
                s[c.slot()] = s[c.hotbar()];
                s[c.hotbar()] = t;
            } else {
                if (cursor != null && s[c.slot()] != null && same(cursor, s[c.slot()])) {
                    return null; // would merge
                }
                String t = s[c.slot()];
                s[c.slot()] = cursor;
                cursor = t;
            }
        }
        return cursor == null ? s : null;
    }

    /** One click applied to {@code state} with {@code cursor}: the new state with the cursor appended as the last
     *  element (the executor's prediction of what the inventory holds next). */
    public static String[] simulateWithCursor(String[] state, String cursor, Click c) {
        String[] s = java.util.Arrays.copyOf(state, state.length + 1);
        if (c.swap()) {
            String t = s[c.slot()];
            s[c.slot()] = s[c.hotbar()];
            s[c.hotbar()] = t;
            s[state.length] = cursor;
        } else {
            s[state.length] = s[c.slot()];
            s[c.slot()] = cursor;
        }
        return s;
    }

    /** Layout slots of {@code want} that {@code state} has right. */
    public static int satisfied(String[] state, Map<Integer, String> want) {
        int k = 0;
        for (Map.Entry<Integer, String> e : want.entrySet()) {
            if (e.getKey() < state.length && same(state[e.getKey()], e.getValue())) {
                k++;
            }
        }
        return k;
    }

    /** For logs: "cycles of length 3 (hotbar), 2 (cursor)". */
    static Map<String, Integer> describe(List<Click> clicks) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Click c : clicks) {
            m.merge(c.swap() ? "swap" : "pickup", 1, Integer::sum);
        }
        return m;
    }
}
