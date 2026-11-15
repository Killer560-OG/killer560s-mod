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
        /** The neighbors that were still unknown when this tile was dug: the tiles its clue counts. Null reads the
         *  neighbors that are unknown now (older callers); a clue counted at dig time must not lose a tile that was
         *  dug, marked or destroyed since, or its Anchor's marked fruit and its Mines count stop fitting any board. */
        public int[] from;
        /** Which dig this was (digs used when it was made), 0 when not dug: the oldest clues are dropped first. */
        public int dugAt;

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
            c.from = from;
            c.dugAt = dugAt;
            return c;
        }
    }

    /** How the next dig is chosen (the Fruit Digging "Strategy" setting). The shovel mode never changes the dug tile's own
     *  points, only the clue it gives, so every strategy picks the mode whose clue pays the most on the next dig. */
    public enum Strategy {
        /** Most expected points per game: the plain pick (default). */
        CONSISTENT("Consistent", 1.0, 0.0, false),
        /** A very high score: bomb and Rum risk costs half, tiles that can pay big get a bonus, and the mode is the one
         *  whose best 20% of outcomes pay the most. */
        LEADERBOARD("Leaderboard", 0.5, 0.35, true),
        /** The Carnival goal "Reach 3,000 score in a single game": risk costs double, the mode is the safest earner. */
        DAILY("Daily Quest", 2.0, 0.0, false);

        public final String label;
        final double risk;
        final double spread;
        final boolean upside;

        Strategy(String label, double risk, double spread, boolean upside) {
            this.label = label;
            this.risk = risk;
            this.spread = spread;
            this.upside = upside;
        }

        public static Strategy of(int i) {
            return values()[Math.floorMod(i, values().length)];
        }
    }

    /** The shovel modes the lookahead compares, in {@link Recommendation#modeEv} order. */
    public static final String[] MODES = {"Mines", "Anchor", "Treasure"};

    /** The solver's answer. {@code modeEv} is, per mode in {@link #MODES} order, the expected points of the best dig that
     *  follows this one when this dig is made in that mode (null when there were no samples). */
    public record Recommendation(int cell, String shovel, double expected, double pBomb, double pRum, int samples,
                                 double[] modeEv) {
        public Recommendation(int cell, String shovel, double expected, double pBomb, double pRum, int samples) {
            this(cell, shovel, expected, pBomb, pRum, samples, null);
        }

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
    /** Every 20th thinned sample of the last {@link #solve} (about 600), for the mode lookahead, and the tile-to-slot map. */
    private final List<Fruit[]> kept = new ArrayList<>();
    private int[] slotOfCell = new int[CELLS];
    private Strategy strategy = Strategy.CONSISTENT;

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
        return solve(cells, digsUsed, nextMultiplier, coconut, keep, Strategy.CONSISTENT);
    }

    /** {@link #solve(Cell[], int, double, boolean, int)} choosing the tile and the shovel mode for {@code strategy}. */
    public Recommendation solve(Cell[] cells, int digsUsed, double nextMultiplier, boolean coconut, int keep,
                                Strategy strategy) {
        this.strategy = strategy;
        kept.clear();
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
        java.util.Map<Clue, Integer> owner = new java.util.IdentityHashMap<>();
        List<Clue> clues = clues(cells, slotOf, owner);
        dropped = null;
        if (n > 0) {
            clues = fitting(clues, owner, cells, pool.toArray(new Fruit[0]));
        }
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

        slotOfCell = slotOf;
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
                        if (samples++ % 20 == 0) {
                            kept.add(deal.clone());
                        }
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

    private String dropped;

    /** What the last solve had to drop to find any board (null when every clue held): "what (tile)" entries. */
    public String dropped() {
        return dropped;
    }

    /**
     * {@code clues} when some board fits them; else the clues with the least trusted dropped until one does: a "no
     * fruit" read off a bomb's blast first, then every clue of a dug bomb, then the oldest dig's, one at a time.
     */
    private List<Clue> fitting(List<Clue> clues, java.util.Map<Clue, Integer> owner, Cell[] cells, Fruit[] pool) {
        if (start(pool.clone(), clues)) {
            return clues;
        }
        List<Clue> kept = new ArrayList<>(clues);
        List<Clue> gone = new ArrayList<>();
        List<Clue> order = new ArrayList<>();
        clues.stream().filter(c -> c instanceof Edible e && !e.must()).forEach(order::add);
        clues.stream().filter(c -> !order.contains(c) && cells[owner.get(c)].content == Fruit.BOMB).forEach(order::add);
        clues.stream().filter(c -> !order.contains(c))
                .sorted(java.util.Comparator.comparingInt(c -> c instanceof Edible ? Integer.MAX_VALUE : cells[owner.get(c)].dugAt))
                .forEach(order::add);
        int tier0 = (int) order.stream().filter(c -> c instanceof Edible e && !e.must()).count();
        int tier1 = (int) order.stream().filter(c -> !(c instanceof Edible) && cells[owner.get(c)].content == Fruit.BOMB).count();
        int next = 0;
        for (int tries = 0; tries < 12 && next < order.size(); tries++) {
            int upTo = tries == 0 && tier0 > 0 ? tier0 : tries <= 1 && tier1 > 0 ? tier0 + tier1 : next + 1;
            for (; next < upTo; next++) {
                kept.remove(order.get(next));
                gone.add(order.get(next));
            }
            if (start(pool.clone(), kept)) {
                break;
            }
        }
        dropped = gone.stream().map(c -> c.getClass().getSimpleName() + " " + label(owner.get(c))).collect(java.util.stream.Collectors.joining(", "));
        return kept;
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

    private static List<Clue> clues(Cell[] cells, int[] slotOf, java.util.Map<Clue, Integer> owner) {
        List<Clue> out = new ArrayList<>();
        for (int i = 0; i < CELLS; i++) {
            Cell c = cells[i];
            List<Integer> hidden = new ArrayList<>();
            int knownBombs = 0;
            for (int nb : c.from != null ? c.from : NEIGHBORS[i]) {
                if (slotOf[nb] >= 0 && (c.from != null || cells[nb].diggable)) {
                    hidden.add(slotOf[nb]);
                } else if (c.from != null && cells[nb].content == Fruit.BOMB) {
                    knownBombs++;
                }
            }
            int[] h = hidden.stream().mapToInt(Integer::intValue).toArray();
            if (c.mines >= 0) {
                add(out, owner, i, new BombCount(h, Math.max(0, c.mines - knownBombs)));
            }
            if (c.noFruitNearby) {
                add(out, owner, i, new NoFruit(h));
            }
            if (c.treasure != null) {
                add(out, owner, i, new Order(h, c.treasure.rank(), c.treasure, knownNeighbor(cells, i, c.treasure), true));
            }
            if (c.anchor != null) {
                add(out, owner, i, new Order(h, c.anchor.rank(), c.anchor, knownNeighbor(cells, i, c.anchor), false));
            }
            if (slotOf[i] >= 0 && c.mustFruit) {
                add(out, owner, i, new Edible(slotOf[i], true));
            }
            if (slotOf[i] >= 0 && c.notFruit && c.diggable) {
                add(out, owner, i, new Edible(slotOf[i], false));
            }
        }
        return out;
    }

    private static void add(List<Clue> out, java.util.Map<Clue, Integer> owner, int cell, Clue c) {
        out.add(c);
        owner.put(c, cell);
    }

    private static boolean knownNeighbor(Cell[] cells, int i, Fruit f) {
        boolean stamped = cells[i].from != null;
        for (int nb : stamped ? cells[i].from : NEIGHBORS[i]) {
            if ((stamped || cells[nb].diggable) && cells[nb].content == f) {
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
            score -= (pb + pr) * opportunity * strategy.risk;
            if (strategy.spread > 0) {
                score += strategy.spread * spread(cells, c, apples, cherries);
            }
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
        double[] ev = modeEv(cells, best, apples, cherries, opportunity);
        int mode = 0;
        if (ev != null) {
            for (int m = 1; m < ev.length; m++) {
                if (ev[m] > ev[mode]) {
                    mode = m;
                }
            }
        } else {
            mode = fruitValueAround(cells, best, apples, cherries, true) >= 0.25 ? 1 : 0;
        }
        return new Recommendation(best, MODES[mode], expected(cells, best, mult, apples, cherries), p(cells, best, Fruit.BOMB),
                p(cells, best, Fruit.RUM), samples, ev);
    }

    /** Standard deviation of a tile's points (a bomb or rum pays 0): how much it can swing a game. */
    private double spread(Cell[] cells, int cell, int apples, int cherries) {
        double e = 0;
        double e2 = 0;
        for (Fruit f : Fruit.values()) {
            double pr = p(cells, cell, f);
            double v = f.edible ? f.value(apples, cherries) : 0;
            e += pr * v;
            e2 += pr * v * v;
        }
        return Math.sqrt(Math.max(0, e2 - e * e));
    }

    /**
     * What each shovel mode is worth for a dig of {@code tile}: over the kept sample boards, the clue that mode would give
     * (Mines the bomb count among the tile's unknown neighbors, Treasure the best fruit among them, Anchor the lowest and
     * where it lies), the best next tile given that clue (its mean points over the boards that give the same clue, a bomb
     * or rum costing {@code opportunity}), and so the points of the dig that follows. For {@link Strategy#upside} the mean
     * of the best 20% of boards instead of the mean. Null with no samples.
     */
    private double[] modeEv(Cell[] cells, int tile, int apples, int cherries, double opportunity) {
        int boards = kept.size();
        if (boards < 20 || slotOfCell[tile] < 0) {
            return null;
        }
        List<Integer> hidden = new ArrayList<>();
        for (int nb : NEIGHBORS[tile]) {
            if (cells[nb].diggable && slotOfCell[nb] >= 0) {
                hidden.add(slotOfCell[nb]);
            }
        }
        List<Integer> cand = new ArrayList<>();
        for (int c = 0; c < CELLS; c++) {
            if (c != tile && cells[c].diggable) {
                cand.add(c);
            }
        }
        double[] out = new double[MODES.length];
        for (int m = 0; m < MODES.length; m++) {
            java.util.Map<Integer, List<Integer>> groups = new java.util.HashMap<>();
            for (int b = 0; b < boards; b++) {
                groups.computeIfAbsent(clueKey(kept.get(b), slotOfCell[tile], hidden, m), k -> new ArrayList<>()).add(b);
            }
            double[] value = new double[boards];
            for (List<Integer> g : groups.values()) {
                int pick = -1;
                double best = Double.NEGATIVE_INFINITY;
                for (int c : cand) {
                    double sum = 0;
                    for (int b : g) {
                        sum += nextScore(cells, c, kept.get(b), apples, cherries, opportunity);
                    }
                    if (sum / g.size() > best) {
                        best = sum / g.size();
                        pick = c;
                    }
                }
                // The group's pick, scored on each board's own fruit: that spread is what the upside strategy reads.
                for (int b : g) {
                    value[b] = pick < 0 ? 0 : nextScore(cells, pick, kept.get(b), apples, cherries, opportunity);
                }
            }
            double s = 0;
            if (strategy.upside) {
                java.util.Arrays.sort(value);
                int top = Math.max(1, boards / 5);
                for (int i = boards - top; i < boards; i++) {
                    s += value[i];
                }
                out[m] = s / top;
            } else {
                for (double v : value) {
                    s += v;
                }
                out[m] = s / boards;
            }
        }
        return out;
    }

    private double nextScore(Cell[] cells, int c, Fruit[] board, int apples, int cherries, double opportunity) {
        Fruit f = slotOfCell[c] >= 0 ? board[slotOfCell[c]] : cells[c].content;
        if (f == null) {
            return 0;
        }
        return f.edible ? f.value(apples, cherries) : -opportunity;
    }

    /** The clue a mode gives on {@code board} for a dig of slot {@code tileSlot}, as one int (equal clue, equal key). */
    private static int clueKey(Fruit[] board, int tileSlot, List<Integer> hidden, int mode) {
        if (!board[tileSlot].edible) {
            return -1; // a bomb or rum gives no clue in any mode
        }
        int bombs = 0;
        int best = -1;
        int bestRank = 0;
        for (int s : hidden) {
            Fruit f = board[s];
            if (f == Fruit.BOMB) {
                bombs++;
            } else if (f.edible) {
                boolean better = best < 0 || (mode == 2 ? f.rank() > bestRank : f.rank() < bestRank);
                if (better) {
                    best = s;
                    bestRank = f.rank();
                }
            }
        }
        return switch (mode) {
            case 0 -> bombs;
            case 1 -> best < 0 ? 100000 : best * 16 + board[best].ordinal() + 1;
            default -> best < 0 ? 100000 : board[best].ordinal() + 1;
        };
    }
}
