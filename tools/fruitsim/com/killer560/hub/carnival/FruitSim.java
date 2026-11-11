package com.killer560.hub.carnival;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Plays whole Fruit Digging games against {@link FruitDiggingSolver} for each {@link FruitDiggingSolver.Strategy} and
 * prints the mean, the high percentiles and the share of games reaching 3,000 points (the Carnival Goal), with the same
 * boards for every strategy. The rules are the ones the solver and the tracker model: 49 tiles (34 fruits, 10 bombs,
 * 5 rums), 15 digs, a Mines clue (bombs among the unknown neighbors), Treasure (the best neighbor fruit named) and Anchor
 * (the lowest marked and named), a bomb destroys the fruit around it unless the dig before was a Coconut, a Rum gives no
 * clue on the next dig, a Pomegranate makes the next dig x1.5 and a Durian x0.5, an Apple and a Cherry grow with each
 * collected, and a Watermelon breaks a neighbor fruit for half its value. Run it with the compiled classes on the path:
 * {@code java -cp build/classes/java/main:tools/fruitsim/out com.killer560.hub.carnival.FruitSim [games]}.
 */
public final class FruitSim {

    private static final int[][] SPOTS = new int[FruitDiggingSolver.CELLS][];

    static {
        for (int i = 0; i < SPOTS.length; i++) {
            SPOTS[i] = FruitDiggingSolver.neighbors(i);
        }
    }

    private FruitSim() {
    }

    record Result(double score, int fallbacks, int digs, int[] modeUse) {
    }

    static Result play(long seed, FruitDiggingSolver.Strategy strategy, int forcedMode) {
        Random deal = new Random(seed * 7919 + 13);
        List<Fruit> pool = new ArrayList<>();
        for (Fruit f : Fruit.values()) {
            for (int k = 0; k < f.count; k++) {
                pool.add(f);
            }
        }
        java.util.Collections.shuffle(pool, deal);
        Fruit[] truth = pool.toArray(new Fruit[0]);
        FruitDiggingSolver.Cell[] cells = new FruitDiggingSolver.Cell[FruitDiggingSolver.CELLS];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = new FruitDiggingSolver.Cell();
        }
        FruitDiggingSolver solver = new FruitDiggingSolver(new Random(seed * 104729 + 7));
        double score = 0;
        double mult = 1.0;
        boolean coconutBefore = false; // the dig before was a Coconut: this dig's bomb does not go off
        boolean rumBefore = false;     // the dig before was a Rum: this dig gives no clue
        int fallbacks = 0;
        int digs = 0;
        int[] modeUse = new int[3];
        for (; digs < FruitDiggingSolver.MAX_DIGS; digs++) {
            FruitDiggingSolver.Recommendation r = solver.solve(cells, digs, mult, coconutBefore, -1, strategy);
            if (r == null) {
                break;
            }
            if (r.samples() == 0) {
                fallbacks++;
            }
            int mode = forcedMode >= 0 ? forcedMode : Arrays.asList(FruitDiggingSolver.MODES).indexOf(r.shovel());
            modeUse[mode]++;
            int cell = r.cell();
            Fruit f = truth[cell];
            int apples = 0;
            int cherries = 0;
            for (FruitDiggingSolver.Cell c : cells) {
                if (!c.diggable && c.content == Fruit.APPLE) {
                    apples++;
                }
                if (!c.diggable && c.content == Fruit.CHERRY) {
                    cherries++;
                }
            }
            FruitDiggingSolver.Cell c = cells[cell];
            c.diggable = false;
            c.content = f;
            c.mustFruit = false;
            if (f.edible) {
                score += f.value(apples, cherries) * mult;
            }
            // The clue, over the unknown neighbors as they stand before the dig's own effects.
            c.from = Arrays.stream(SPOTS[cell]).filter(nb -> cells[nb].diggable && cells[nb].content == null).toArray();
            if (f.edible && !rumBefore) {
                int bombs = 0;
                Fruit best = null;
                int bestAt = -1;
                for (int nb : SPOTS[cell]) {
                    if (!cells[nb].diggable || cells[nb].content != null) {
                        continue;
                    }
                    Fruit g = truth[nb];
                    if (g == Fruit.BOMB) {
                        bombs++;
                    } else if (g.edible) {
                        boolean better = best == null || (mode == 2 ? g.rank() > best.rank() : g.rank() < best.rank());
                        if (better) {
                            best = g;
                            bestAt = nb;
                        }
                    }
                }
                if (mode == 0) {
                    c.mines = bombs;
                } else if (best == null) {
                    c.noFruitNearby = true;
                } else if (mode == 2) {
                    c.treasure = best;
                } else {
                    c.anchor = best;
                    cells[bestAt].content = best;
                }
            }
            double nextMult = f == Fruit.POMEGRANATE ? 1.5 : f == Fruit.DURIAN ? 0.5 : 1.0;
            if (f == Fruit.WATERMELON) {
                for (int nb : SPOTS[cell]) {
                    if (cells[nb].diggable && truth[nb].edible && (cells[nb].content == null || cells[nb].content == truth[nb])) {
                        score += 0.5 * truth[nb].value(apples, cherries);
                        cells[nb].diggable = false;
                        cells[nb].content = truth[nb];
                        break;
                    }
                }
            }
            if (f == Fruit.BOMB && !coconutBefore) {
                for (int nb : SPOTS[cell]) {
                    FruitDiggingSolver.Cell n = cells[nb];
                    if (!n.diggable) {
                        continue;
                    }
                    if (truth[nb].edible) {
                        if (n.content == null) {
                            n.mustFruit = true;
                        }
                        n.diggable = false; // destroyed: its fruit is lost
                    } else if (n.content == null) {
                        n.notFruit = true;
                    }
                }
            }
            coconutBefore = f == Fruit.COCONUT;
            rumBefore = f == Fruit.RUM;
            mult = nextMult;
        }
        return new Result(score, fallbacks, digs, modeUse);
    }

    private static double pct(double[] sorted, double p) {
        return sorted[Math.min(sorted.length - 1, (int) Math.floor(p * sorted.length))];
    }

    public static void main(String[] args) {
        int games = args.length > 0 ? Integer.parseInt(args[0]) : 300;
        System.out.println("games per strategy: " + games + ", same boards for every row");
        System.out.printf("%-22s %7s %7s %7s %7s %7s %9s %8s  %s%n", "strategy", "mean", "p50", "p90", "p99", "max",
                "P(>=3000)", "fallback", "modes M/A/T");
        for (int s = -3; s < FruitDiggingSolver.Strategy.values().length; s++) {
            FruitDiggingSolver.Strategy st = FruitDiggingSolver.Strategy.CONSISTENT;
            int forced = -1;
            String name;
            if (s < 0) {
                forced = s + 3; // a fixed shovel mode every dig, solver's Consistent tile pick: the baseline
                name = "fixed " + FruitDiggingSolver.MODES[forced];
            } else {
                st = FruitDiggingSolver.Strategy.values()[s];
                name = st.label;
            }
            final FruitDiggingSolver.Strategy fs = st;
            final int ff = forced;
            Result[] res = IntStream.range(0, games).parallel().mapToObj(g -> play(g + 1, fs, ff)).toArray(Result[]::new);
            double[] sc = Arrays.stream(res).mapToDouble(Result::score).sorted().toArray();
            double mean = Arrays.stream(sc).average().orElse(0);
            long goal = Arrays.stream(sc).filter(v -> v >= 3000).count();
            int fb = Arrays.stream(res).mapToInt(Result::fallbacks).sum();
            int[] use = new int[3];
            for (Result r : res) {
                for (int m = 0; m < 3; m++) {
                    use[m] += r.modeUse()[m];
                }
            }
            double sd = Math.sqrt(Arrays.stream(sc).map(v -> (v - mean) * (v - mean)).sum() / Math.max(1, sc.length - 1));
            System.out.printf("%-22s %7.0f %7.0f %7.0f %7.0f %7.0f %8.1f%% %8d  %d/%d/%d  (mean +-%.0f)%n", name, mean,
                    pct(sc, 0.5), pct(sc, 0.9), pct(sc, 0.99), sc[sc.length - 1], 100.0 * goal / sc.length, fb, use[0],
                    use[1], use[2], sd / Math.sqrt(sc.length));
        }
    }
}
