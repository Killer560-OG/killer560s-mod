package com.killer560.hub.carnival;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Picks the next Fruit Digging tile (the approach SkyHanni's Fruit Digging solver takes, written fresh here): every
 * unknown tile gets a probability for each content by sampling whole boards that fit every clue seen so far, and the
 * tile with the best expected points, less the risk of wasting the dig on a bomb or rum (and of a bomb destroying the
 * fruit around it), plus a little for the clues digging it would give, is recommended, with the shovel mode to use.
 *
 * <p>The boards are sampled by a Markov chain over swaps of two unknown tiles' contents, started from a board that fits
 * (found by shuffling, then by repairing the fewest broken clues): a swap is kept only when every clue still holds, so
 * every fitting board is equally likely, as a plain shuffle-and-reject would make them but without starving when the
 * clues are tight. Pure: no Minecraft types, so a test can feed it a board.
 *
 * <p>Clues: a Mines count (bombs among the tile's unknown neighbors), Treasure (the best fruit among the neighbors is the
 * named one), Anchor (the worst is the named one), "no fruits nearby", a tile a bomb destroyed (it held a fruit) and a
 * tile left standing beside a bomb that went off (it holds no fruit: a bomb destroys only fruits).
 */
public final class FruitDiggingSolver {

    public static final int SIZE = 7;
    public static final int CELLS = SIZE * SIZE;
    public static final int MAX_DIGS = 15;

    /** One tile as the tracker knows it. */
    public static final class Cell {
        /** What the tile holds when known (dug and named, or shown by an Anchor), else null. */
        public Fruit content;
        /** The tile is still sand and can be dug. */
        public boolean diggable = true;
        /** A Mines clue from digging here, or -1. */
        public int mines = -1;
        /** A Treasure clue from digging here, or null. */
        public Fruit treasure;
        /** An Anchor clue from digging here, or null. */
        public Fruit anchor;
        /** The Treasure or Anchor said there are no fruits nearby. */
        public boolean noFruitNearby;
        /** The tile is known to hold a fruit (a bomb destroyed it, or an Anchor marked it without naming it). */
        public boolean mustFruit;
        /** The tile is known to hold no fruit (it stood beside a bomb that went off). */
        public boolean notFruit;

        public Cell copy() {
            Cell c = new Cell();
            c.content = content;
            c.diggable = diggable;
            c.mines = mines;
            c.treasure = treasure;
            c.anchor = anchor;
            c.noFruitNearby = noFruitNearby;
            c.mustFruit = mustFruit;
            c.notFruit = notFruit;
            return c;
        }
    }

    /** The solver's answer. */
    public record Recommendation(int cell, String shovel, double expected, double pBomb, double pRum, int samples) {
        public int row() {
            return cell / SIZE;
        }

        public int col() {
            return cell % SIZE;
        }

        public String label() {
            return FruitDiggingSolver.label(cell);
        }
    }

    /** "A1" for the top-left tile: row letter, column number. */
    public static String label(int cell) {
        return (char) ('A' + cell / SIZE) + Integer.toString(cell % SIZE + 1);
    }

    private static final int[][] NEIGHBORS = new int[CELLS][];

    static {
        for (int id = 0; id < CELLS; id++) {
            int r = id / SIZE;
            int c = id % SIZE;
            List<Integer> l = new ArrayList<>();
            for (int dr = -1; dr <= 1; dr++) {
                for (int dc = -1; dc <= 1; dc++) {
                    int rr = r + dr;
                    int cc = c + dc;
                    if ((dr != 0 || dc != 0) && rr >= 0 && rr < SIZE && cc >= 0 && cc < SIZE) {
                        l.add(rr * SIZE + cc);
                    }
                }
            }
            NEIGHBORS[id] = l.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    public static int[] neighbors(int cell) {
        return NEIGHBORS[cell];
    }

    private static final double RISK_WEIGHT = 1.0;
    private static final double DESTRUCTION_WEIGHT = 0.10;
    private static final double SETUP_WEIGHT = 0.5;
    private static final double INFO_WEIGHT = 15.0;
    private static final int CHAINS = 4;
    /** Points within which the last pick is kept over a new best. */
    static final double KEEP_MARGIN = 10.0;
    private static final int BURN_IN = 1000;
    private static final int STEPS = 12000;
    private static final int THIN = 4;

    private final Random random;
    /** Per tile, per content ordinal: the probability from the last {@link #solve} (null for a known tile). */
    private double[][] belief = new double[CELLS][];
    private int samples;

    public FruitDiggingSolver(Random random) {
        this.random = random;
    }

    public double[] belief(int cell) {
        return belief[cell];
    }

    public int samples() {
        return samples;
    }

    // ---- clues ---------------------------------------------------------------------------------------------------

    private interface Clue {
        boolean holds(Fruit[] deal);
    }

    private record BombCount(int[] slots, int bombs) implements Clue {
        @Override
        public boolean holds(Fruit[] deal) {
            int n = 0;
            for (int s : slots) {
                if (deal[s] == Fruit.BOMB) {
                    n++;
                }
            }
            return n == bombs;
        }
    }

    /** Treasure (ceiling: no fruit above {@code rank}) or Anchor (floor: none below), and the named one present. */
    private record Order(int[] slots, int rank, Fruit named, boolean namedKnown, boolean ceiling) implements Clue {
        @Override
        public boolean holds(Fruit[] deal) {
            boolean found = namedKnown;
            for (int s : slots) {
                Fruit f = deal[s];
                if (!f.edible) {
                    continue;
                }
                if (ceiling ? f.rank() > rank : f.rank() < rank) {
                    return false;
                }
                if (f == named) {
                    found = true;
                }
            }
            return found;
        }
    }

    private record NoFruit(int[] slots) implements Clue {
        @Override
        public boolean holds(Fruit[] deal) {
            for (int s : slots) {
                if (deal[s].edible) {
                    return false;
                }
            }
            return true;
        }
    }

    private record Edible(int slot, boolean must) implements Clue {
        @Override
        public boolean holds(Fruit[] deal) {
            return deal[slot].edible == must;
        }
    }

    // ---- solving -------------------------------------------------------------------------------------------------

    /**
     * Works out the beliefs and the best next dig for {@code cells} (49, row-major). {@code nextMultiplier} is 1.5 after a
     * Pomegranate, 0.5 after a Durian; {@code coconut} is true after a Coconut (the next bomb does not go off).
     */
    public Recommendation solve(Cell[] cells, int digsUsed, double nextMultiplier, boolean coconut) {
        return solve(cells, digsUsed, nextMultiplier, coconut, -1);
    }

    /**
     * {@link #solve(Cell[], int, double, boolean)} keeping {@code keep} (the last pick, or -1) while it scores within
     * {@link #KEEP_MARGIN} of the best: two picks that close are a tie the sampling noise decides, and a box that jumps
     * between them on every solve tells the player nothing.
     */
    public Recommendation solve(Cell[] cells, int digsUsed, double nextMultiplier, boolean coconut, int keep) {
        // The unknown tiles (on the board or destroyed) are the slots the remaining contents are dealt into.
        int[] slotOf = new int[CELLS];
        List<Integer> slotCells = new ArrayList<>();
        int[] remaining = new int[Fruit.values().length];
        for (Fruit f : Fruit.values()) {
            remaining[f.ordinal()] = f.count;
        }
        int apples = 0;
        int cherries = 0;
        for (int i = 0; i < CELLS; i++) {
            Cell c = cells[i];
            slotOf[i] = -1;
            if (c.content != null) {
                remaining[c.content.ordinal()]--;
                if (!c.diggable && c.content == Fruit.APPLE) {
                    apples++;
                }
                if (!c.diggable && c.content == Fruit.CHERRY) {
                    cherries++;
                }
            } else {
                slotOf[i] = slotCells.size();
                slotCells.add(i);
            }
        }
        List<Fruit> pool = new ArrayList<>();
        for (Fruit f : Fruit.values()) {
            for (int k = 0; k < remaining[f.ordinal()]; k++) {
                pool.add(f);
            }
        }
        int n = slotCells.size();
        // A board whose counts no longer add up (a missed reveal) is sampled with what fits: trim or pad with bombs.
        while (pool.size() > n) {
            pool.remove(pool.size() - 1);
        }
        while (pool.size() < n) {
            pool.add(Fruit.BOMB);
        }
        List<Clue> clues = clues(cells, slotOf);
        List<List<Clue>> bySlot = new ArrayList<>();
        for (int s = 0; s < n; s++) {
            bySlot.add(new ArrayList<>());
        }
        for (Clue cl : clues) {
            for (int s : slotsOf(cl)) {
                if (!bySlot.get(s).contains(cl)) {
                    bySlot.get(s).add(cl);
                }
            }
        }

        double[][] counts = new double[n][Fruit.values().length];
        samples = 0;
        if (n > 0) {
            Fruit[] deal = pool.toArray(new Fruit[0]);
            boolean fits = false;
            // Several chains, each from its own fitting start, so one slow-mixing chain cannot decide alone.
            for (int chain = 0; chain < CHAINS; chain++) {
                if (!start(deal, clues)) {
                    continue;
                }
                fits = true;
                for (int step = 0; step < BURN_IN + STEPS; step++) {
                    int a = random.nextInt(n);
                    int b = random.nextInt(n);
                    if (a != b && deal[a] != deal[b]) {
                        swap(deal, a, b);
                        if (!holdsAll(bySlot.get(a), deal) || !holdsAll(bySlot.get(b), deal)) {
                            swap(deal, a, b);
                        }
                    }
                    if (step >= BURN_IN && step % THIN == 0) {
                        for (int s = 0; s < n; s++) {
                            counts[s][deal[s].ordinal()]++;
                        }
                        samples++;
                    }
                }
            }
            if (!fits) {
                // Nothing fits (a clue misread): fall back to the plain odds of what is left.
                for (int s = 0; s < n; s++) {
                    for (Fruit f : pool) {
                        counts[s][f.ordinal()]++;
                    }
                }
                samples = 0;
            }
        }
        belief = new double[CELLS][];
        for (int s = 0; s < n; s++) {
            double total = 0;
            for (double v : counts[s]) {
                total += v;
            }
            double[] p = new double[Fruit.values().length];
            for (int k = 0; k < p.length; k++) {
                p[k] = total > 0 ? counts[s][k] / total : 0;
            }
            belief[slotCells.get(s)] = p;
        }
        return recommend(cells, digsUsed, nextMultiplier, coconut, apples, cherries, keep);
    }

    private static int[] slotsOf(Clue c) {
        if (c instanceof BombCount b) {
            return b.slots();
        }
        if (c instanceof Order o) {
            return o.slots();
        }
        if (c instanceof NoFruit nf) {
            return nf.slots();
        }
        if (c instanceof Edible e) {
            return new int[]{e.slot()};
        }
        return new int[0];
    }

    private static boolean holdsAll(List<Clue> clues, Fruit[] deal) {
        for (Clue c : clues) {
            if (!c.holds(deal)) {
                return false;
            }
        }
        return true;
    }

    private static int broken(List<Clue> clues, Fruit[] deal) {
        int b = 0;
        for (Clue c : clues) {
            if (!c.holds(deal)) {
                b++;
            }
        }
        return b;
    }

    private static void swap(Fruit[] deal, int a, int b) {
        Fruit t = deal[a];
        deal[a] = deal[b];
        deal[b] = t;
    }

    /** Arranges {@code deal} into a board that fits every clue; false when none was found. */
    private boolean start(Fruit[] deal, List<Clue> clues) {
        int n = deal.length;
        for (int attempt = 0; attempt < 3000; attempt++) {
            for (int i = n - 1; i > 0; i--) {
                swap(deal, i, random.nextInt(i + 1));
            }
            if (holdsAll(clues, deal)) {
                return true;
            }
        }
        // Repair: keep swaps that do not break more clues, until none are broken.
        int bad = broken(clues, deal);
        for (int step = 0; step < 40000 && bad > 0; step++) {
            int a = random.nextInt(n);
            int b = random.nextInt(n);
            if (a == b || deal[a] == deal[b]) {
                continue;
            }
            swap(deal, a, b);
            int now = broken(clues, deal);
            if (now <= bad) {
                bad = now;
            } else {
                swap(deal, a, b);
            }
        }
        return bad == 0;
    }

    private static List<Clue> clues(Cell[] cells, int[] slotOf) {
        List<Clue> out = new ArrayList<>();
        for (int i = 0; i < CELLS; i++) {
            Cell c = cells[i];
            List<Integer> hidden = new ArrayList<>();
            for (int nb : NEIGHBORS[i]) {
                if (cells[nb].diggable && slotOf[nb] >= 0) {
                    hidden.add(slotOf[nb]);
                }
            }
            int[] h = hidden.stream().mapToInt(Integer::intValue).toArray();
            if (c.mines >= 0) {
                out.add(new BombCount(h, c.mines));
            }
            if (c.noFruitNearby) {
                out.add(new NoFruit(h));
            }
            if (c.treasure != null) {
                out.add(new Order(h, c.treasure.rank(), c.treasure, knownNeighbor(cells, i, c.treasure), true));
            }
            if (c.anchor != null) {
                out.add(new Order(h, c.anchor.rank(), c.anchor, knownNeighbor(cells, i, c.anchor), false));
            }
            if (slotOf[i] >= 0 && c.mustFruit) {
                out.add(new Edible(slotOf[i], true));
            }
            if (slotOf[i] >= 0 && c.notFruit && c.diggable) {
                out.add(new Edible(slotOf[i], false));
            }
        }
        return out;
    }

    private static boolean knownNeighbor(Cell[] cells, int i, Fruit f) {
        for (int nb : NEIGHBORS[i]) {
            if (cells[nb].diggable && cells[nb].content == f) {
                return true;
            }
        }
        return false;
    }

    // ---- scoring -------------------------------------------------------------------------------------------------

    private double p(Cell[] cells, int cell, Fruit f) {
        if (cells[cell].content != null) {
            return cells[cell].content == f ? 1 : 0;
        }
        double[] b = belief[cell];
        return b == null ? 0 : b[f.ordinal()];
    }

    private double fruitValueAround(Cell[] cells, int cell, int apples, int cherries, boolean countOnly) {
        double total = 0;
        for (int nb : NEIGHBORS[cell]) {
            if (!cells[nb].diggable) {
                continue;
            }
            for (Fruit f : Fruit.values()) {
                if (f.edible) {
                    double pr = p(cells, nb, f);
                    total += countOnly ? pr : pr * f.value(apples, cherries);
                }
            }
        }
        return total;
    }

    private double expected(Cell[] cells, int cell, double mult, int apples, int cherries) {
        double e = 0;
        for (Fruit f : Fruit.values()) {
            double pr = p(cells, cell, f);
            if (pr <= 0 || !f.edible) {
                continue;
            }
            double v = f.value(apples, cherries);
            if (f == Fruit.WATERMELON) {
                // Reveals a neighbor for half its value: half the average fruit around it.
                double cnt = fruitValueAround(cells, cell, apples, cherries, true);
                v += cnt > 0 ? 0.5 * fruitValueAround(cells, cell, apples, cherries, false) / cnt : 100;
            }
            e += pr * v;
        }
        return e * mult;
    }

    private Recommendation recommend(Cell[] cells, int digsUsed, double mult, boolean coconut, int apples, int cherries,
                                     int keep) {
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < CELLS; i++) {
            if (cells[i].diggable) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty() || digsUsed >= MAX_DIGS) {
            return null;
        }
        double bestSafe = 0;
        for (int c : candidates) {
            if (p(cells, c, Fruit.BOMB) < 0.05 && p(cells, c, Fruit.RUM) < 0.05) {
                bestSafe = Math.max(bestSafe, expected(cells, c, mult, apples, cherries));
            }
        }
        double opportunity = bestSafe > 0 ? bestSafe : 250;
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        double keepScore = Double.NEGATIVE_INFINITY;
        for (int c : candidates) {
            double pb = p(cells, c, Fruit.BOMB);
            double pr = p(cells, c, Fruit.RUM);
            double score = expected(cells, c, mult, apples, cherries);
            score -= (pb + pr) * opportunity * RISK_WEIGHT;
            if (!coconut) {
                score -= pb * DESTRUCTION_WEIGHT * fruitValueAround(cells, c, apples, cherries, false);
            }
            score += (p(cells, c, Fruit.POMEGRANATE) - p(cells, c, Fruit.DURIAN)) * SETUP_WEIGHT * opportunity;
            int hiddenNb = 0;
            for (int nb : NEIGHBORS[c]) {
                if (cells[nb].diggable && cells[nb].content == null) {
                    hiddenNb++;
                }
            }
            score += hiddenNb * INFO_WEIGHT;
            if (c == keep) {
                keepScore = score;
            }
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        if (keep >= 0 && cells[keep].diggable && keepScore >= bestScore - KEEP_MARGIN) {
            best = keep;
        }
        // After a Durian halves the next dig, spend it on the cheapest safe tile and keep the good ones for full value.
        if (mult < 1.0 && MAX_DIGS - digsUsed > 1) {
            int absorber = -1;
            double low = Double.MAX_VALUE;
            for (int c : candidates) {
                if (p(cells, c, Fruit.BOMB) < 0.15 && p(cells, c, Fruit.RUM) < 0.15) {
                    double e = expected(cells, c, mult, apples, cherries);
                    if (e < low) {
                        low = e;
                        absorber = c;
                    }
                }
            }
            if (absorber >= 0) {
                best = absorber;
            }
        }
        double nearbyFruit = fruitValueAround(cells, best, apples, cherries, true);
        String shovel = nearbyFruit >= 0.25 ? "Anchor" : "Mines";
        return new Recommendation(best, shovel, expected(cells, best, mult, apples, cherries), p(cells, best, Fruit.BOMB),
                p(cells, best, Fruit.RUM), samples);
    }
}
