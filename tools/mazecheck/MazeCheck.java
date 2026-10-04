import com.killer560.hub.roomsim.puzzles.TeleportMazeLinks;
import java.util.*;

/** See tools/mazecheck/run.sh. */
public class MazeCheck {
    static final int[][] REL = {
            {4, 69, 14}, {10, 69, 14}, {10, 69, 20}, {4, 69, 20},
            {4, 69, 12}, {4, 69, 6}, {10, 69, 6}, {10, 69, 12},
            {12, 69, 28}, {12, 69, 22}, {18, 69, 22}, {18, 69, 28},
            {26, 69, 14}, {20, 69, 20}, {20, 69, 14}, {26, 69, 20},
            {26, 69, 28}, {26, 69, 22}, {20, 69, 28}, {20, 69, 22},
            {10, 69, 22}, {10, 69, 28}, {4, 69, 28}, {4, 69, 22},
            {20, 69, 6}, {20, 69, 12}, {26, 69, 12}, {26, 69, 6},
            {15, 69, 14}, {15, 69, 12}};
    static int ch(int p) { return TeleportMazeLinks.chamberOf(p); }

    // The old drawLinks pairing (minus its fallback), for the before numbers.
    static int[] old(Random rng) {
        for (int attempt = 0; attempt < 500; attempt++) {
            List<Integer> pads = new ArrayList<>();
            for (int i = 0; i < 28; i++) pads.add(i);
            Collections.shuffle(pads, rng);
            int[] l = new int[30]; Arrays.fill(l, -1);
            int entry = pads.remove(0), exit = -1;
            for (int i = 0; i < pads.size(); i++) if (ch(pads.get(i)) != ch(entry)) { exit = pads.remove(i); break; }
            if (exit < 0) continue;
            l[29] = entry; l[entry] = 29; l[exit] = 28;
            boolean ok = true;
            while (!pads.isEmpty() && ok) {
                int a = pads.remove(0), partner = -1;
                for (int i = 0; i < pads.size(); i++) if (ch(pads.get(i)) != ch(a)) { partner = pads.remove(i); break; }
                if (partner < 0) ok = false; else { l[a] = partner; l[partner] = a; }
            }
            if (ok) return l;
        }
        return null;
    }

    public static void main(String[] a) {
        int n = Integer.parseInt(a[0]);
        Random rng = new Random(560);
        for (String which : new String[]{"before", "after"}) {
            int[] loopHist = new int[16];
            int diagFail = 0, bad = 0; long landings = 0, trapped = 0;
            long diagLen = 0;
            for (int g = 0; g < n; g++) {
                int[] l = which.equals("before") ? old(rng) : TeleportMazeLinks.draw(rng, REL).link();
                // structural checks: two-way, cross-chamber, every pad linked, one exit, start linked
                int exits = 0;
                for (int p = 0; p < 28; p++) {
                    int t = l[p];
                    if (t == 28) { exits++; continue; }
                    if (t < 0 || (t != 29 && (ch(t) == ch(p) || l[t] != p))) bad++;
                }
                if (exits != 1 || l[29] < 0 || l[l[29]] != 29) bad++;
                int loops = TeleportMazeLinks.loops(l, REL);
                loopHist[Math.min(15, loops)]++;
                for (int land = 0; land < 28; land++) {
                    int pad = land; boolean ok = false;
                    for (int k = 0; k < 64; k++) { int nx = l[TeleportMazeLinks.diagonal(pad, REL)]; if (nx == 28) { ok = true; break; } if (nx == 29) nx = l[29]; pad = nx; }
                    landings++; if (!ok) trapped++;
                }
                List<Integer> w = TeleportMazeLinks.diagonalWalk(l, REL);
                if (w == null) diagFail++; else diagLen += w.size();
            }
            System.out.printf("%s: %d mazes, structural faults %d, diagonal walk never reaches the centre %d (%.1f%%), mean pads stepped when it does %.1f%n",
                    which, n, bad, diagFail, 100.0 * diagFail / n, (double) diagLen / Math.max(1, n - diagFail));
            StringBuilder sb = new StringBuilder("  closed loops:");
            for (int i = 1; i < 16; i++) if (loopHist[i] > 0) sb.append(" ").append(i).append("=").append(loopHist[i]);
            System.out.println(sb);
            System.out.printf("  landings from which following diagonals never reaches the centre: %.1f%%%n", 100.0 * trapped / landings);
        }
    }
}
