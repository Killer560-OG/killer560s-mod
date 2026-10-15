package com.killer560.hub.autoclear;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Where Auto Clear stands in a room - pure, no Minecraft state (testkit 364-logic-room-tour drives it).
 *
 * <p>killer560, 2026-10-15: "For rooms that need repositioning make sure that it always chooses the repositioning path
 * that will require the least amount of total repositions in the room." Auto Clear used to take the nearest mob and the
 * standing block nearest to THAT mob, one mob at a time, so three mobs nine blocks apart took three etherwarps where one
 * block between the first two reaches both. This is a minimum set cover: every standing block is the set of mobs its
 * weapon reaches from there ({@link WeaponReach#hyperionHits} / {@link WeaponReach#sceptreHits}), and the plan is the
 * fewest blocks that reach every mob between them - exact (iterative deepening) for any room's handful of mobs, the
 * greedy cover past the budget. Of the plans with that fewest count, the one with the shortest walk between its blocks
 * from where he stands, visited nearest first.
 */
public final class RepositionPlanner {

    private RepositionPlanner() {
    }

    /**
     * @param spots  indices of the chosen standing blocks, in the order to visit them
     * @param exact  no plan with fewer blocks exists
     */
    public record Plan(int[] spots, boolean exact) {
        public int count() {
            return spots.length;
        }
    }

    /**
     * @param covers per candidate block, the mobs it reaches (bit i = mob i; at most 63 mobs)
     * @param mobs   how many mobs
     * @param spots  per candidate block, its x, y, z
     * @param from   where he stands: x, y, z
     * @return null when some mob is reached from no block at all
     */
    public static Plan plan(long[] covers, int mobs, double[][] spots, double[] from, long budgetNanos) {
        double[] fromHim = new double[spots.length];
        for (int i = 0; i < spots.length; i++) {
            fromHim[i] = dist(spots[i], from);
        }
        Dist between = (a, b) -> dist(spots[a], spots[b]);
        return plan(covers, mobs, fromHim, between, budgetNanos);
    }

    /** Distance between two candidate blocks. */
    interface Dist {
        double between(int a, int b);
    }

    static double dist(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * How many blocks taking the mobs one at a time needs - the old rule: from where he stands the nearest mob not reached
     * yet, and the block reaching it that is nearest to IT; for the log beside the plan.
     * @param mobAt per mob, its x, y, z
     */
    public static int nearestMobFirst(long[] covers, int mobs, double[][] spots, double[][] mobAt, double[] from) {
        long all = mobs >= 63 ? -1L >>> 1 : (1L << mobs) - 1;
        long got = 0;
        double[] at = from;
        int moves = 0;
        while ((got & all) != all && moves <= mobs) {
            int mob = -1;
            double best = Double.MAX_VALUE;
            for (int m = 0; m < mobs; m++) {
                if ((got & (1L << m)) == 0 && dist(mobAt[m], at) < best) {
                    best = dist(mobAt[m], at);
                    mob = m;
                }
            }
            int spot = -1;
            double near = Double.MAX_VALUE;
            for (int i = 0; i < covers.length; i++) {
                if ((covers[i] & (1L << mob)) != 0 && dist(spots[i], mobAt[mob]) < near) {
                    near = dist(spots[i], mobAt[mob]);
                    spot = i;
                }
            }
            if (spot < 0) {
                got |= 1L << mob;
                continue;
            }
            got |= covers[spot];
            at = spots[spot];
            moves++;
        }
        return moves;
    }

    private static Plan plan(long[] covers, int mobs, double[] fromHim, Dist between, long budgetNanos) {
        long all = mobs >= 63 ? -1L >>> 1 : (1L << mobs) - 1;
        long reach = 0;
        for (long c : covers) {
            reach |= c;
        }
        if ((reach & all) != all) {
            return null;
        }
        if (mobs == 0) {
            return new Plan(new int[0], true);
        }
        // One block per distinct set of mobs: the nearest to him (a farther block reaching the same mobs is never better).
        List<Integer> kept = new ArrayList<>();
        for (int i = 0; i < covers.length; i++) {
            long c = covers[i] & all;
            if (c == 0) {
                continue;
            }
            boolean dominated = false;
            for (int k = 0; k < kept.size(); k++) {
                int j = kept.get(k);
                long cj = covers[j] & all;
                if (cj == c) {
                    if (fromHim[i] < fromHim[j]) {
                        kept.set(k, i);
                    }
                    dominated = true;
                    break;
                }
            }
            if (!dominated) {
                kept.add(i);
            }
        }
        // Drop a block whose mobs are a strict subset of another's (it can never be needed in a smallest cover).
        List<Integer> cand = new ArrayList<>();
        for (int i : kept) {
            long ci = covers[i] & all;
            boolean sub = false;
            for (int j : kept) {
                long cj = covers[j] & all;
                if (j != i && (ci & cj) == ci && cj != ci) {
                    sub = true;
                    break;
                }
            }
            if (!sub) {
                cand.add(i);
            }
        }
        int[] greedy = greedyCover(covers, all, cand);
        long deadline = System.nanoTime() + Math.max(0, budgetNanos);
        int[] best = greedy;
        boolean exact = true;
        for (int k = 1; k < greedy.length; k++) {
            Cover c = new Cover(covers, all, cand, k, fromHim, between, deadline);
            c.search(0L, new int[k], 0);
            if (c.timedOut) {
                exact = false;
                break;
            }
            if (c.best != null) {
                best = c.best;
                break;
            }
        }
        if (exact && best == greedy) {
            // The greedy count is the fewest: still prefer the shortest walk among the covers of that size.
            Cover c = new Cover(covers, all, cand, greedy.length, fromHim, between, deadline);
            c.search(0L, new int[greedy.length], 0);
            if (c.timedOut) {
                exact = false;
            }
            if (c.best != null) {
                best = c.best;
            }
        }
        return new Plan(nearestFirst(best, fromHim, between), exact);
    }

    private static int[] greedyCover(long[] covers, long all, List<Integer> cand) {
        long left = all;
        List<Integer> out = new ArrayList<>();
        while (left != 0) {
            int pick = -1;
            int bestGain = 0;
            for (int i : cand) {
                int g = Long.bitCount(covers[i] & left);
                if (g > bestGain) {
                    bestGain = g;
                    pick = i;
                }
            }
            if (pick < 0) {
                break;
            }
            out.add(pick);
            left &= ~covers[pick];
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The blocks in nearest-first order from him (then from the block before). */
    static int[] nearestFirst(int[] spots, double[] fromHim, Dist between) {
        int n = spots.length;
        boolean[] used = new boolean[n];
        int[] out = new int[n];
        int prev = -1;
        for (int k = 0; k < n; k++) {
            int pick = -1;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (used[i]) {
                    continue;
                }
                double d = prev < 0 || between == null ? fromHim[spots[i]] : between.between(prev, spots[i]);
                if (d < best) {
                    best = d;
                    pick = i;
                }
            }
            used[pick] = true;
            out[k] = spots[pick];
            prev = spots[pick];
        }
        return out;
    }

    static double walk(int[] ordered, double[] fromHim, Dist between) {
        double d = 0;
        int prev = -1;
        for (int s : ordered) {
            d += prev < 0 || between == null ? fromHim[s] : between.between(prev, s);
            prev = s;
        }
        return d;
    }

    /** Every cover of exactly {@code k} blocks, keeping the shortest walk. */
    private static final class Cover {
        final long[] covers;
        final long all;
        final List<Integer> cand;
        final int k;
        final double[] fromHim;
        final Dist between;
        final long deadline;
        int[] best;
        double bestWalk = Double.MAX_VALUE;
        boolean timedOut;
        long steps;

        Cover(long[] covers, long all, List<Integer> cand, int k, double[] fromHim, Dist between, long deadline) {
            this.covers = covers;
            this.all = all;
            this.cand = cand;
            this.k = k;
            this.fromHim = fromHim;
            this.between = between;
            this.deadline = deadline;
        }

        void search(long got, int[] chosen, int depth) {
            if (timedOut) {
                return;
            }
            if ((++steps & 255) == 0 && System.nanoTime() > deadline) {
                timedOut = true;
                return;
            }
            if ((got & all) == all) {
                int[] pick = Arrays.copyOf(chosen, depth);
                double w = walk(nearestFirst(pick, fromHim, between), fromHim, between);
                if (best == null || pick.length < best.length || pick.length == best.length && w < bestWalk) {
                    best = pick;
                    bestWalk = w;
                }
                return;
            }
            if (depth == k) {
                return;
            }
            // Branch on the mob reached by the fewest blocks: one of them must be in the cover.
            long left = all & ~got;
            int mob = -1;
            int fewest = Integer.MAX_VALUE;
            for (int m = 0; m < 63 && (1L << m) <= left; m++) {
                if ((left & (1L << m)) == 0) {
                    continue;
                }
                int n = 0;
                for (int i : cand) {
                    if ((covers[i] & (1L << m)) != 0) {
                        n++;
                    }
                }
                if (n < fewest) {
                    fewest = n;
                    mob = m;
                }
            }
            if (mob < 0) {
                return;
            }
            // Not enough blocks left to cover what is left, even taking the biggest each time.
            int maxGain = 0;
            for (int i : cand) {
                maxGain = Math.max(maxGain, Long.bitCount(covers[i] & left));
            }
            if (maxGain == 0 || (long) maxGain * (k - depth) < Long.bitCount(left)) {
                return;
            }
            for (int i : cand) {
                if ((covers[i] & (1L << mob)) == 0) {
                    continue;
                }
                chosen[depth] = i;
                search(got | covers[i], chosen, depth + 1);
                if (timedOut) {
                    return;
                }
            }
        }
    }
}
