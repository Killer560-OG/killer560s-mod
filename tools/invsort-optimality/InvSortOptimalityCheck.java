import com.killer560.hub.invsort.InvSortPlanner;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Exhaustive check of {@link InvSortPlanner} against the real click rules (killer560, 2026-11-01: "double check that any
 * move you make is the most optimized method that there is no way to do it in less clicks").
 * <p>
 * For every start inventory and every layout on a small inventory, a breadth-first search over EVERY click a player can
 * send finds the true minimum, and the planner's plan must (1) be legal, (2) reach as many layout slots as any sequence
 * can, and (3) be exactly that minimum long. The clicks searched are a superset of what the sorter uses:
 * <ul>
 *   <li>left click (pick up / place / exchange / MERGE onto a matching stack),</li>
 *   <li>number-key swap with a hotbar slot (cursor empty, as the vanilla client requires),</li>
 *   <li>shift-click (to the other section: merges into matching stacks first, then the first empty slot),</li>
 *   <li>double click gather (PICKUP_ALL: pulls every matching stack onto the cursor).</li>
 * </ul>
 * Identity A is stackable (so merges and gathers really happen), B and C are not, 0 is empty. Right-click splits and
 * drags are left out: they split a stack, and a layout moves whole stacks (see docs/INVSORT-OPTIMALITY.md).
 * <p>
 * Run: {@code javac -d out src/main/java/com/killer560/hub/invsort/InvSortPlanner.java tools/invsort-optimality/*.java &&
 * java -cp out InvSortOptimalityCheck}.
 */
public final class InvSortOptimalityCheck {

    private static final String[] NAMES = {null, "A", "B", "C"};
    private static int IDS = 4;

    private static int n;
    private static int hot;

    public static void main(String[] args) {
        long t0 = System.currentTimeMillis();
        long total = 0;
        total += runConfig(5, 2);
        total += runConfig(5, 1);
        total += runConfig(4, 0);
        total += runConfig(4, 4);
        IDS = 3; // empty, A (stackable), B: one slot more
        total += runConfig(6, 2);
        total += runConfig(6, 0);
        IDS = 4;
        total += randomLarge(2_000);
        System.out.println("ALL PASS: " + total + " instances in " + (System.currentTimeMillis() - t0) / 1000 + " s");
    }

    // ------------------------------------------------------------------------------------------- exhaustive

    private static long runConfig(int slots, int hotbar) {
        n = slots;
        hot = hotbar;
        int starts = pow(IDS, n);
        int layouts = pow(IDS, n);
        long checked = 0;
        long withClicks = 0;
        long maxClicks = 0;
        for (int s = 0; s < starts; s++) {
            int[] ids = digits(s);
            // BFS from this start: best distance to each identity vector with an empty cursor.
            Map<Integer, Integer> best = bfs(ids);
            int[] vecs = new int[best.size()];
            int[] dist = new int[best.size()];
            int k = 0;
            for (Map.Entry<Integer, Integer> e : best.entrySet()) {
                vecs[k] = e.getKey();
                dist[k++] = e.getValue();
            }
            String[] cur = new String[n];
            for (int i = 0; i < n; i++) {
                cur[i] = NAMES[ids[i]];
            }
            for (int l = 0; l < layouts; l++) {
                int[] lay = digits(l);
                Map<Integer, String> want = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) {
                    if (lay[i] != 0) {
                        want.put(i, NAMES[lay[i]]);
                    }
                }
                int bestSat = -1;
                int bestDist = Integer.MAX_VALUE;
                for (int v = 0; v < vecs.length; v++) {
                    int sat = 0;
                    int vec = vecs[v];
                    for (int i = 0; i < n; i++) {
                        int id = vec % IDS;
                        vec /= IDS;
                        if (lay[i] != 0 && lay[i] == id) {
                            sat++;
                        }
                    }
                    if (sat > bestSat || (sat == bestSat && dist[v] < bestDist)) {
                        bestSat = sat;
                        bestDist = dist[v];
                    }
                }
                InvSortPlanner.Plan plan = InvSortPlanner.plan(cur, null, want, hot);
                String[] end = InvSortPlanner.simulate(cur, plan.clicks());
                String where = "n=" + n + " hot=" + hot + " start=" + java.util.Arrays.toString(cur) + " layout=" + want
                        + " plan=" + plan.clicks();
                if (end == null) {
                    fail("illegal plan: " + where);
                }
                int sat = InvSortPlanner.satisfied(end, want);
                if (sat != bestSat) {
                    fail("plan reaches " + sat + " layout slots, " + bestSat + " are reachable: " + where);
                }
                if (plan.clicks().size() != bestDist) {
                    fail("plan takes " + plan.clicks().size() + " clicks, the minimum is " + bestDist + ": " + where);
                }
                if (plan.lowerBound() != plan.clicks().size() || !plan.provenMinimal()) {
                    fail("plan's bound disagrees: " + where);
                }
                checked++;
                if (bestDist > 0) {
                    withClicks++;
                }
                maxClicks = Math.max(maxClicks, bestDist);
            }
        }
        System.out.println(String.format("n=%d hotbar=%d: %d instances (%d needing clicks, up to %d), every plan legal and"
                + " exactly minimal", n, hot, checked, withClicks, maxClicks));
        return checked;
    }

    /** State: per position (and the cursor, last) id*8+count, packed 6 bits each. */
    private static Map<Integer, Integer> bfs(int[] ids) {
        int[] st = new int[n + 1];
        for (int i = 0; i < n; i++) {
            st[i] = ids[i] == 0 ? 0 : ids[i] * 8 + 1;
        }
        Map<Long, Integer> seen = new HashMap<>();
        ArrayDeque<long[]> queue = new ArrayDeque<>();
        long start = pack(st);
        seen.put(start, 0);
        queue.add(new long[]{start, 0});
        Map<Integer, Integer> best = new HashMap<>();
        while (!queue.isEmpty()) {
            long[] cell = queue.poll();
            int[] s = unpack(cell[0]);
            int d = (int) cell[1];
            if (s[n] == 0) {
                best.merge(identityVector(s), d, Math::min);
            }
            for (int[] nx : moves(s)) {
                long key = pack(nx);
                if (!seen.containsKey(key)) {
                    seen.put(key, d + 1);
                    queue.add(new long[]{key, d + 1});
                }
            }
        }
        return best;
    }

    private static List<int[]> moves(int[] s) {
        List<int[]> out = new java.util.ArrayList<>();
        int c = n;
        for (int p = 0; p < n; p++) {
            // Left click.
            int[] a = s.clone();
            if (a[c] == 0 && a[p] == 0) {
                // nothing happens
            } else if (a[c] != 0 && a[p] != 0 && id(a[c]) == id(a[p]) && id(a[c]) == 1) {
                a[p] = 8 + cnt(a[p]) + cnt(a[c]);
                a[c] = 0;
                out.add(a);
            } else {
                int t = a[p];
                a[p] = a[c];
                a[c] = t;
                out.add(a);
            }
            // Number-key swap with each hotbar slot, cursor empty.
            if (s[c] == 0) {
                for (int h = 0; h < hot; h++) {
                    if (h != p && (s[p] != 0 || s[h] != 0)) {
                        int[] b = s.clone();
                        b[p] = s[h];
                        b[h] = s[p];
                        out.add(b);
                    }
                }
            }
            // Shift-click to the other section.
            if (s[p] != 0) {
                int[] q = quickMove(s, p);
                if (q != null) {
                    out.add(q);
                }
            }
            // Double-click gather onto the cursor (stackable A only).
            if (s[c] != 0 && id(s[c]) == 1) {
                int[] g = s.clone();
                boolean any = false;
                for (int i = 0; i < n; i++) {
                    if (id(g[i]) == 1) {
                        g[c] = 8 + cnt(g[c]) + cnt(g[i]);
                        g[i] = 0;
                        any = true;
                    }
                }
                if (any) {
                    out.add(g);
                }
            }
        }
        return out;
    }

    private static int[] quickMove(int[] s, int p) {
        boolean fromHot = p < hot;
        int lo = fromHot ? hot : 0;
        int hi = fromHot ? n : hot;
        if (lo >= hi) {
            return null;
        }
        int[] a = s.clone();
        if (id(a[p]) == 1) {
            for (int i = lo; i < hi; i++) {
                if (id(a[i]) == 1) {
                    a[i] = 8 + cnt(a[i]) + cnt(a[p]);
                    a[p] = 0;
                    return a;
                }
            }
        }
        for (int i = lo; i < hi; i++) {
            if (a[i] == 0) {
                a[i] = a[p];
                a[p] = 0;
                return a;
            }
        }
        return null;
    }

    private static int id(int v) {
        return v / 8;
    }

    private static int cnt(int v) {
        return v % 8;
    }

    private static int identityVector(int[] s) {
        int v = 0;
        for (int i = n - 1; i >= 0; i--) {
            v = v * IDS + id(s[i]);
        }
        return v;
    }

    private static long pack(int[] s) {
        long k = 0;
        for (int v : s) {
            k = (k << 6) | v;
        }
        return k;
    }

    private static int[] unpack(long k) {
        int[] s = new int[n + 1];
        for (int i = n; i >= 0; i--) {
            s[i] = (int) (k & 63);
            k >>>= 6;
        }
        return s;
    }

    private static int[] digits(int v) {
        int[] d = new int[n];
        for (int i = 0; i < n; i++) {
            d[i] = v % IDS;
            v /= IDS;
        }
        return d;
    }

    private static int pow(int b, int e) {
        int r = 1;
        for (int i = 0; i < e; i++) {
            r *= b;
        }
        return r;
    }

    // ------------------------------------------------------------------------------------------- full size

    /** Real-size inventories (36 slots, 9 hotbar): every plan must be legal, reach every reachable layout slot, and be
     *  as long as its own cycle bound. */
    private static long randomLarge(int count) {
        Random rnd = new Random(1101);
        String[] pool = {"AOTE", "HYPERION", "PEARL", "TNT", "DECOY", "BONZO", "SPRING", "ROD", "BOW", "LEAP", "JERRY",
                "ICE_SPRAY", "GYRO", "CLAYMORE", "DIRT"};
        long proven = 0;
        long worstMs = 0;
        long sumMs = 0;
        for (int k = 0; k < count; k++) {
            String[] cur = new String[36];
            for (int i = 0; i < 36; i++) {
                cur[i] = rnd.nextInt(3) == 0 ? null : pool[rnd.nextInt(pool.length)];
            }
            String[] target = cur.clone();
            for (int i = 35; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                String t = target[i];
                target[i] = target[j];
                target[j] = t;
            }
            Map<Integer, String> want = new LinkedHashMap<>();
            for (int i = 0; i < 36; i++) {
                if (target[i] != null && rnd.nextInt(4) != 0) {
                    want.put(i, rnd.nextInt(20) == 0 ? "MISSING" : target[i]);
                }
            }
            long p0 = System.nanoTime();
            InvSortPlanner.Plan plan = InvSortPlanner.plan(cur, null, want, 9);
            long ms = (System.nanoTime() - p0) / 1_000_000;
            worstMs = Math.max(worstMs, ms);
            sumMs += ms;
            String[] end = InvSortPlanner.simulate(cur, plan.clicks());
            if (end == null) {
                fail("illegal 36-slot plan " + k);
            }
            Set<Integer> wrong = new HashSet<>();
            for (Map.Entry<Integer, String> e : want.entrySet()) {
                if (!e.getValue().equalsIgnoreCase(String.valueOf(end[e.getKey()]))) {
                    wrong.add(e.getKey());
                }
            }
            if (!wrong.equals(plan.unresolved())) {
                fail("36-slot plan " + k + " left " + wrong + " wrong but reported " + plan.unresolved());
            }
            if (plan.lowerBound() != plan.clicks().size()) {
                fail("36-slot plan " + k + " is longer than its bound");
            }
            if (plan.provenMinimal()) {
                proven++;
            }
        }
        System.out.println(String.format("36 slots / 9 hotbar: %d random inventories, every plan legal and at its bound;"
                + " %d searched exhaustively (the rest hit the search budget); planning took %.1f ms on average, %d ms at worst",
                count, proven, sumMs / (double) count, worstMs));
        return count;
    }

    private static void fail(String msg) {
        System.out.println("FAIL " + msg);
        System.exit(1);
    }
}
