package com.killer560.hub.bloodrush;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * How a room's boxes are shared between the roles, as seen from the door the rush came in by. The reference mod adm
 * keeps hand-made plans per room x entry door x team size on its site; this is the rule we fitted to those plans
 * (killer560s-mod-logs\adm-study\measure_final.py, measured on all 90 rooms of both their normal and M7 sets; with
 * equal box weights: 64% / 53% / 56% of boxes the same role as adm's at 2 / 3 / 4 killers on normal floors, 55% / 50% on
 * M7, against 37% / 28% / 13% for our first nearest-first cut):
 * <ol>
 *   <li><b>Regions.</b> The boxes form one region a role: k-means on the boxes' x/z, seeded with the box farthest from
 *       the door, then each next seed the box farthest from those already picked. On normal floors the regions are then
 *       evened out to at most ceil(boxes / roles) each, the box nearest another region's middle moving first (adm's normal
 *       plans are near equal box counts; its M7 ones are not).</li>
 *   <li><b>Numbering.</b> Normal floors: role 1 is the region farthest from the door (on average), then the next farthest.
 *       Master mode: role 1 is the region with the most boxes (adm's M7 roles are classes, the Archer taking the most), ties
 *       to the nearer one.</li>
 *   <li><b>Kill order</b> inside a role: from the door, the nearest box first, then each next the nearest to the last.</li>
 *   <li>A room with one box (a miniboss room) is every role's.</li>
 * </ol>
 * Deterministic: the same boxes and door give the same split on every client, so two players with this mod never differ.
 */
final class BloodRushSplit {

    private BloodRushSplit() {
    }

    /** {@code n} lists (role 1 first), each that role's boxes in kill order. {@code boxes} in a stable order. */
    static List<List<BloodRushHelperFeature.Box>> split(List<BloodRushHelperFeature.Box> boxes, double dx, double dz, int n,
                                                       boolean master) {
        List<List<BloodRushHelperFeature.Box>> out = new ArrayList<>();
        if (boxes.size() == 1 || n <= 1) {
            for (int i = 0; i < Math.max(1, n); i++) {
                out.add(tour(boxes, dx, dz));
            }
            return out;
        }
        List<List<BloodRushHelperFeature.Box>> groups = regions(boxes, dx, dz, n, master);
        if (master) {
            groups.sort(Comparator.<List<BloodRushHelperFeature.Box>>comparingInt(g -> -g.size())
                    .thenComparingDouble(g -> meanDist(g, dx, dz)));
        } else {
            groups.sort(Comparator.comparingDouble(g -> -meanDist(g, dx, dz)));
        }
        for (List<BloodRushHelperFeature.Box> g : groups) {
            out.add(tour(g, dx, dz));
        }
        while (out.size() < n) {
            out.add(new ArrayList<>());
        }
        return out;
    }

    private static double dist2(BloodRushHelperFeature.Box b, double x, double z) {
        return (b.cx - x) * (b.cx - x) + (b.cz - z) * (b.cz - z);
    }

    private static double meanDist(List<BloodRushHelperFeature.Box> g, double dx, double dz) {
        if (g.isEmpty()) {
            return Double.MAX_VALUE;
        }
        double s = 0;
        for (BloodRushHelperFeature.Box b : g) {
            s += Math.sqrt(dist2(b, dx, dz));
        }
        return s / g.size();
    }

    private static List<List<BloodRushHelperFeature.Box>> regions(List<BloodRushHelperFeature.Box> boxes, double dx,
                                                                   double dz, int n, boolean master) {
        int len = boxes.size();
        List<List<BloodRushHelperFeature.Box>> groups = new ArrayList<>();
        if (n >= len) {
            for (BloodRushHelperFeature.Box b : boxes) {
                List<BloodRushHelperFeature.Box> g = new ArrayList<>();
                g.add(b);
                groups.add(g);
            }
            return groups;
        }
        // Seeds: the box farthest from the door, then each next the farthest from the seeds already picked.
        List<BloodRushHelperFeature.Box> seeds = new ArrayList<>();
        BloodRushHelperFeature.Box first = null;
        for (BloodRushHelperFeature.Box b : boxes) {
            if (first == null || dist2(b, dx, dz) > dist2(first, dx, dz)) {
                first = b;
            }
        }
        seeds.add(first);
        while (seeds.size() < n) {
            BloodRushHelperFeature.Box best = null;
            double bestD = -1;
            for (BloodRushHelperFeature.Box b : boxes) {
                if (seeds.contains(b)) {
                    continue;
                }
                double m = Double.MAX_VALUE;
                for (BloodRushHelperFeature.Box s : seeds) {
                    m = Math.min(m, dist2(b, s.cx, s.cz));
                }
                if (m > bestD) {
                    bestD = m;
                    best = b;
                }
            }
            seeds.add(best);
        }
        double[][] c = new double[n][2];
        for (int i = 0; i < n; i++) {
            c[i][0] = seeds.get(i).cx;
            c[i][1] = seeds.get(i).cz;
        }
        int[] g = new int[len];
        java.util.Arrays.fill(g, -1);
        for (int iter = 0; iter < 50; iter++) {
            boolean changed = false;
            for (int k = 0; k < len; k++) {
                BloodRushHelperFeature.Box b = boxes.get(k);
                int bi = 0;
                for (int i = 1; i < n; i++) {
                    if (dist2(b, c[i][0], c[i][1]) < dist2(b, c[bi][0], c[bi][1])) {
                        bi = i;
                    }
                }
                if (g[k] != bi) {
                    g[k] = bi;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
            centres(boxes, g, c, n);
        }
        if (!master) {
            balance(boxes, g, c, n);
        }
        for (int i = 0; i < n; i++) {
            groups.add(new ArrayList<>());
        }
        for (int k = 0; k < len; k++) {
            groups.get(g[k]).add(boxes.get(k));
        }
        return groups;
    }

    private static void centres(List<BloodRushHelperFeature.Box> boxes, int[] g, double[][] c, int n) {
        for (int i = 0; i < n; i++) {
            double sx = 0;
            double sz = 0;
            int m = 0;
            for (int k = 0; k < boxes.size(); k++) {
                if (g[k] == i) {
                    sx += boxes.get(k).cx;
                    sz += boxes.get(k).cz;
                    m++;
                }
            }
            if (m > 0) {
                c[i][0] = sx / m;
                c[i][1] = sz / m;
            }
        }
    }

    /** At most ceil(len / n) boxes a region: the box nearest another, smaller, region's middle moves there first. */
    private static void balance(List<BloodRushHelperFeature.Box> boxes, int[] g, double[][] c, int n) {
        int len = boxes.size();
        int cap = (len + n - 1) / n;
        for (int guard = 0; guard < len * n; guard++) {
            int[] size = new int[n];
            for (int k = 0; k < len; k++) {
                size[g[k]]++;
            }
            int over = -1;
            for (int i = 0; i < n; i++) {
                if (size[i] > cap) {
                    over = i;
                    break;
                }
            }
            if (over < 0) {
                return;
            }
            int bestK = -1;
            int bestTo = -1;
            double bestD = Double.MAX_VALUE;
            for (int k = 0; k < len; k++) {
                if (g[k] != over) {
                    continue;
                }
                for (int j = 0; j < n; j++) {
                    if (j == over || size[j] >= cap || size[j] == 0) {
                        continue;
                    }
                    double d = dist2(boxes.get(k), c[j][0], c[j][1]);
                    if (d < bestD) {
                        bestD = d;
                        bestK = k;
                        bestTo = j;
                    }
                }
            }
            if (bestK < 0) {
                return;
            }
            g[bestK] = bestTo;
            centres(boxes, g, c, n);
        }
    }

    /** From the door: nearest first, then each next the nearest to the last. */
    static List<BloodRushHelperFeature.Box> tour(List<BloodRushHelperFeature.Box> boxes, double dx, double dz) {
        List<BloodRushHelperFeature.Box> left = new ArrayList<>(boxes);
        List<BloodRushHelperFeature.Box> out = new ArrayList<>();
        double x = dx;
        double z = dz;
        while (!left.isEmpty()) {
            BloodRushHelperFeature.Box best = left.get(0);
            for (BloodRushHelperFeature.Box b : left) {
                if (dist2(b, x, z) < dist2(best, x, z) - 1e-9) {
                    best = b;
                }
            }
            left.remove(best);
            out.add(best);
            x = best.cx;
            z = best.cz;
        }
        return out;
    }
}
