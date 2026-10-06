import com.killer560.hub.autopuzzles.MazeRoute;
import com.killer560.hub.roomsim.puzzles.TeleportMazeLinks;
import java.util.*;

/**
 * Plays Auto Teleport Maze's pad choice against N sim mazes (TeleportMazeLinks.draw), offline. "old" is the pick
 * before 2026-10-06 (unvisited diagonal, farthest unvisited, then the diagonal again); "new" is MazeRoute. "-blind" plays
 * without the solver; the plain rows model it as the sim drives it: every landing faces the exit pad, the exit
 * candidates are the unvisited pads that look ray crosses (narrowed landing by landing), and best is a candidate in
 * this chamber, else the unvisited pad here nearest the facing - TeleportMazeSolverFeature's rules. Counts how many reach the centre,
 * end on the start pad / with nothing to pick, or pass 60 teleports (the auto's own limit), and the hops taken.
 *   tools/mazecheck/route.sh [N]
 */
public class RouteCheck {
    static int[][] xz = new int[30][];
    static int[] cellOf = new int[30];

    public static void main(String[] a) {
        int n = Integer.parseInt(a[0]);
        for (int i = 0; i < 30; i++) {
            xz[i] = new int[]{MazeCheck.REL[i][0], MazeCheck.REL[i][2]};
            cellOf[i] = TeleportMazeLinks.chamberOf(i);
        }
        for (String mode : new String[]{"old-blind", "new-blind", "old", "new"}) {
            Random rng = new Random(560);
            int solved = 0, stuck = 0, capped = 0, over30 = 0;
            int[] hist = new int[62];
            long hops = 0;
            for (int g = 0; g < n; g++) {
                TeleportMazeLinks.Maze m = TeleportMazeLinks.draw(rng, MazeCheck.REL);
                int r = play(m, mode);
                if (r > 0) { solved++; hops += r; hist[Math.min(61, r)]++; if (r > 10) over30++; }
                else if (r == -1) stuck++;
                else capped++;
            }
            int max = 0;
            for (int i = 0; i < hist.length; i++) if (hist[i] > 0) max = i;
            System.out.printf("%-9s reached the centre %d/%d (%.2f%%), stuck %d, over 60 teleports %d; mean %.1f teleports, max %d, over 10: %d%n",
                    mode, solved, n, 100.0 * solved / n, stuck, capped, solved == 0 ? 0 : (double) hops / solved, max, over30);
        }
    }

    /** @return teleports to the centre, -1 stuck (nothing to pick / on the start pad), -2 past 60 */
    static int play(TeleportMazeLinks.Maze m, String mode) {
        int[] link = m.link();
        Map<Integer, Integer> links = new HashMap<>();
        Set<Integer> visited = new HashSet<>();
        int stepped = 29;
        Set<Integer> correct = null;
        boolean solver = !mode.endsWith("-blind");
        for (int hop = 1; hop <= 60; hop++) {
            int landed = link[stepped];
            if (landed == 28) return hop;
            visited.add(stepped);
            visited.add(landed);
            links.put(stepped, landed);
            links.putIfAbsent(landed, stepped);
            // The solver: the sim turns him to face the exit pad; candidates are the unvisited pads that look ray
            // crosses (column inflated 0.75, 32 blocks), narrowed landing by landing; best is a candidate here, else
            // the unvisited pad here nearest his facing.
            int ex = m.exitPad();
            double ox = xz[landed][0] + 0.5, oz = xz[landed][1] + 0.5;
            double dx = xz[ex][0] + 0.5 - ox, dz = xz[ex][1] + 0.5 - oz, len = Math.hypot(dx, dz);
            Set<Integer> next0 = new LinkedHashSet<>();
            for (int p = 0; p < 30; p++) {
                if (visited.contains(p) || (correct != null && !correct.contains(p)) || len == 0) continue;
                if (crosses(ox, oz, dx / len * 32, dz / len * 32, xz[p][0] - 0.75, xz[p][1] - 0.75, xz[p][0] + 1.75, xz[p][1] + 1.75)) next0.add(p);
            }
            correct = next0;
            int best = -1;
            if (solver) {
                double bd = 1e9;
                for (int p = 0; p < 28; p++) {
                    if (cellOf[p] != cellOf[landed] || p == landed || visited.contains(p)) continue;
                    if (correct.contains(p)) { best = p; break; }
                    double ty = Math.toDegrees(Math.atan2(xz[p][1] + 0.5 - oz, xz[p][0] + 0.5 - ox)) - 90;
                    double fy = Math.toDegrees(Math.atan2(dz, dx)) - 90;
                    double diff = Math.abs(wrap(ty) - wrap(fy));
                    if (diff < bd) { bd = diff; best = p; }
                }
            }
            Set<Integer> cand = solver ? correct : Set.of();
            int next;
            if (mode.startsWith("old")) {
                next = old(landed, visited, cand, best);
            } else {
                MazeRoute.Choice c = MazeRoute.choose(landed, xz, cellOf, links, visited, cand, best);
                next = c == null ? -1 : c.pad();
            }
            if (next < 0) return -1;
            stepped = next;
        }
        return -2;
    }

    static double wrap(double d) { d %= 360; if (d >= 180) d -= 360; if (d < -180) d += 360; return d; }

    /** Does the segment from (ox,oz) along (dx,dz) cross the box? */
    static boolean crosses(double ox, double oz, double dx, double dz, double x0, double z0, double x1, double z1) {
        double t0 = 0, t1 = 1;
        double[] o = {ox, oz}, d = {dx, dz}, lo = {x0, z0}, hi = {x1, z1};
        for (int i = 0; i < 2; i++) {
            if (Math.abs(d[i]) < 1e-9) { if (o[i] < lo[i] || o[i] > hi[i]) return false; continue; }
            double a = (lo[i] - o[i]) / d[i], b = (hi[i] - o[i]) / d[i];
            t0 = Math.max(t0, Math.min(a, b)); t1 = Math.min(t1, Math.max(a, b));
        }
        return t0 <= t1;
    }

    static int old(int cur, Set<Integer> visited, Set<Integer> correct, int best) {
        int cell = cellOf[cur];
        if (cell < 0) return -1;
        if (best >= 0 && cellOf[best] == cell && !visited.contains(best)) return best;
        if (correct.size() == 1) { int only = correct.iterator().next(); if (cellOf[only] == cell) return only; }
        List<Integer> unv = new ArrayList<>();
        for (int p = 0; p < 28; p++) if (cellOf[p] == cell && !visited.contains(p)) unv.add(p);
        for (int p : unv) if (xz[p][0] != xz[cur][0] && xz[p][1] != xz[cur][1]) return p;
        int far = -1; double fd = -1;
        for (int p : unv) { double d = Math.hypot(xz[p][0] - xz[cur][0], xz[p][1] - xz[cur][1]); if (d > fd) { fd = d; far = p; } }
        if (far >= 0) return far;
        for (int p = 0; p < 28; p++) if (cellOf[p] == cell && xz[p][0] != xz[cur][0] && xz[p][1] != xz[cur][1]) return p;
        return -1;
    }
}
