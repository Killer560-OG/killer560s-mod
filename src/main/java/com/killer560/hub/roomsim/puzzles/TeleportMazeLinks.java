package com.killer560.hub.roomsim.puzzles;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The pad pairing of the sim's Teleport Maze, with no Minecraft in it so it can be checked offline.
 *
 * <p><b>The structure, and why.</b> Seven chambers of four pads in the corners; every pad is one end of a two-way
 * link to a pad in a different chamber; the start pad is linked to one chamber pad, and one pad (the exit) leads to
 * the centre. The wiki's way through (hypixelskyblock.minecraft.wiki, Catacombs Puzzle Rooms, Teleport Maze) is to
 * "run to the teleport pad diagonal of the one you used to enter the room" and keep doing so until you land facing a
 * pad in the same room, which then takes you to the centre - and Auto Teleport Maze's own fallback
 * ({@code getPad}) does the same. That only ever works if following diagonals cannot go round in a circle that
 * misses the exit.
 *
 * <p>Pair each pad with its diagonal partner: a chamber is two such diagonals, fourteen in all. Following
 * diagonals is a walk on those fourteen, where a diagonal's two pads are its two exits. With every pad linked once,
 * each diagonal has exactly two links, so the diagonals and links form paths and closed loops. killer560
 * (2026-10-04): "Make sure tp maze only has one closed loop, not multiple." A random pairing (what this used to
 * draw) splits them into several pieces - start-to-exit plus one or more separate loops - and a diagonal walk that
 * starts in the wrong piece never finds the centre. So the draw now lays all fourteen diagonals in ONE chain:
 * start, d1, d2, ... d14, exit. The centre's own pad sends him back to the room's entrance, beside the start pad,
 * so start-chain-centre-start is the one closed loop, and it goes through every diagonal of every chamber.
 *
 * <p>Pads are numbered as {@code TeleportMazeSolverFeature.PADS}: chamber {@code c} is pads {@code 4c..4c+3},
 * then {@link #END} (the centre) and {@link #START}.
 */
public final class TeleportMazeLinks {

    public static final int CHAMBERS = 7;
    public static final int PADS_PER_CHAMBER = 4;
    public static final int END = 28;
    public static final int START = 29;

    private TeleportMazeLinks() {
    }

    /** A drawn maze: {@code link[pad]} is the pad it sends you to ({@link #END} for the exit). */
    public record Maze(int[] link, int exitPad) {
    }

    public static int chamberOf(int pad) {
        return pad >= 0 && pad < END ? pad / PADS_PER_CHAMBER : -1;
    }

    /**
     * The pad diagonally across the chamber from {@code pad}, from the pads' own positions ({@code rel[pad] = {x,
     * y, z}}): the one of the other three that differs in both x and z. The order of a chamber's pads in the table
     * is not the same in every chamber, so this is measured rather than assumed.
     */
    public static int diagonal(int pad, int[][] rel) {
        int c = chamberOf(pad);
        for (int i = 0; i < PADS_PER_CHAMBER; i++) {
            int other = c * PADS_PER_CHAMBER + i;
            if (other != pad && rel[other][0] != rel[pad][0] && rel[other][2] != rel[pad][2]) {
                return other;
            }
        }
        return -1;
    }

    /**
     * Draws a maze in which every diagonal is on the one chain from the start to the exit: fourteen diagonals in a
     * random order with no two of the same chamber next to each other, each entered by one of its pads at random
     * and left by the other.
     */
    public static Maze draw(Random rng, int[][] rel) {
        List<int[]> diagonals = new ArrayList<>();
        boolean[] seen = new boolean[END];
        for (int p = 0; p < END; p++) {
            if (seen[p]) {
                continue;
            }
            int q = diagonal(p, rel);
            if (q < 0) {
                throw new IllegalStateException("pad " + p + " has no diagonal partner");
            }
            seen[p] = true;
            seen[q] = true;
            diagonals.add(new int[]{p, q});
        }
        for (int attempt = 0; attempt < 1000; attempt++) {
            List<int[]> order = new ArrayList<>(diagonals);
            Collections.shuffle(order, rng);
            if (!separated(order)) {
                continue;
            }
            int[] link = new int[END + 2];
            Arrays.fill(link, -1);
            int previous = START;
            int exit = -1;
            for (int[] d : order) {
                boolean flip = rng.nextBoolean();
                int in = flip ? d[1] : d[0];
                int out = flip ? d[0] : d[1];
                link[previous] = in;
                link[in] = previous;
                previous = out;
                exit = out;
            }
            link[exit] = END;
            return new Maze(link, exit);
        }
        throw new IllegalStateException("no ordering of the diagonals keeps chambers apart");
    }

    /** No two neighbours in the chain from the same chamber (a pad never links into its own chamber). */
    private static boolean separated(List<int[]> order) {
        for (int i = 1; i < order.size(); i++) {
            if (chamberOf(order.get(i)[0]) == chamberOf(order.get(i - 1)[0])) {
                return false;
            }
        }
        return true;
    }

    /**
     * How many separate pieces the links make of the fourteen diagonals plus the start and the centre (the start and
     * centre counted as one node, since the centre's pad returns him beside the start). One means one closed loop
     * through everything.
     */
    public static int loops(int[] link, int[][] rel) {
        int[] parent = new int[END + 1];   // 0..27 pads, 28 = start/centre
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (int p = 0; p < END; p++) {
            union(parent, p, diagonal(p, rel));
            int to = link[p];
            union(parent, p, to == END || to == START ? END : to);
        }
        int pieces = 0;
        for (int i = 0; i < parent.length; i++) {
            if (find(parent, i) == i) {
                pieces++;
            }
        }
        return pieces;
    }

    /**
     * The pads stepped on following diagonals only, from the start, until the centre - or null if that walk goes
     * round without reaching it: the wiki's rule taken literally, the pad diagonal to the one he landed on, every
     * time.
     */
    public static List<Integer> diagonalWalk(int[] link, int[][] rel) {
        List<Integer> stepped = new ArrayList<>();
        int pad = START;
        for (int guard = 0; guard < 64; guard++) {
            stepped.add(pad);
            int landed = link[pad];
            if (landed == END) {
                return stepped;
            }
            if (landed < 0 || landed == START) {
                return null;
            }
            pad = diagonal(landed, rel);
        }
        return null;
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private static void union(int[] parent, int a, int b) {
        if (a < 0 || b < 0) {
            return;
        }
        parent[find(parent, a)] = find(parent, b);
    }
}
