package com.killer560.hub.luck;

import java.util.Locale;

/**
 * The numbers behind How Lucky Are You. Pure functions, no Minecraft, so the testkit can check them directly.
 *
 * <p>Expected drops = the sum of each attempt's chance (an attempt at 200% Magic Find adds more than one at 0%). Luck is
 * where the drops he got sit in the spread he could have got: the count of a drop over many attempts follows a Poisson
 * distribution with mean = expected (each attempt is a small independent chance), and the "luck percentile" is the
 * mid-P value {@code P(X < got) + P(X = got) / 2}: 50% is exactly average, 90% means luckier than 90 out of 100 players
 * who did the same, 10% unluckier.
 */
public final class LuckMath {

    private LuckMath() {
    }

    /**
     * A slayer drop's chance at {@code magicFind}%: Slayer#Boss Loot multiplies the weight of a Main/Extra item under 5%
     * by (1 + MF/100). The pool's total weight grows by the same amount, which this leaves out: for the rare items MF
     * applies to it is a fraction of a percent of the pool (Warden Heart's weight is 2 of 14,697), so the chance itself
     * is scaled. Capped at 1.
     */
    public static double withMagicFind(DropTable.Drop d, double magicFind) {
        if (!d.mf() || magicFind <= 0 || Double.isNaN(d.chance())) {
            return d.chance();
        }
        return Math.min(1.0, d.chance() * (1.0 + magicFind / 100.0));
    }

    /**
     * What raised the odds of one attempt.
     *
     * @param magicFind     Magic Find %
     * @param looting       Looting level of the weapon held at the kill (mob drops only)
     * @param slayerLevel   his slayer level for the boss killed, -1 when not known (every drop counted as unlocked)
     * @param meterItem     the RNG Meter's selected drop for this activity, or null
     * @param meterProgress that meter's fill, 0..1 (1 = full: the drop is guaranteed)
     */
    public record Bonus(int magicFind, int looting, int slayerLevel, String meterItem, double meterProgress) {
        public static Bonus mf(int magicFind) {
            return new Bonus(magicFind, 0, -1, null, 0);
        }
    }

    /** Whether a drop can drop at all: a slayer drop above his slayer level cannot. */
    public static boolean unlocked(DropTable.Drop d, Bonus b) {
        return b.slayerLevel() < 0 || d.req() <= b.slayerLevel();
    }

    /**
     * A drop's chance for one attempt with {@code b}: Looting first (mob drops: x(1 + 0.15 x level), Looting page), then
     * Magic Find (slayers: the table's flag; mobs: only while the looted chance is under 5%, Magic Find page), then the RNG
     * Meter (Pity and RNG Meters: the selected drop's rate goes 1x to 3x as the meter fills, 100% once full; a slayer
     * Separate-pool drop is not raised until the meter is full). Capped at 1.
     */
    public static double adjusted(DropTable.Drop d, Bonus b) {
        double c = d.chance();
        if (Double.isNaN(c)) {
            return c;
        }
        if (d.loot() && b.looting() > 0) {
            c *= 1.0 + 0.15 * Math.min(10, b.looting());
        }
        boolean mobDrop = d.activity().startsWith("mob:");
        boolean mf = mobDrop ? c < 0.05 : d.mf();
        if (mf && b.magicFind() > 0) {
            c *= 1.0 + b.magicFind() / 100.0;
        }
        if (b.meterItem() != null && DropTable.normalize(b.meterItem()).equals(DropTable.normalize(d.name()))) {
            if (b.meterProgress() >= 1.0) {
                c = 1.0;
            } else if (!"Separate".equals(d.pool())) {
                c *= 1.0 + 2.0 * Math.max(0, b.meterProgress());
            }
        }
        return Math.min(1.0, c);
    }

    /** {@code P(X = k)} for Poisson(lambda), in logs so 10,000 runs do not overflow. */
    static double poissonPmf(int k, double lambda) {
        if (lambda <= 0) {
            return k == 0 ? 1.0 : 0.0;
        }
        return Math.exp(k * Math.log(lambda) - lambda - logFactorial(k));
    }

    private static double logFactorial(int k) {
        double s = 0;
        for (int i = 2; i <= k; i++) {
            s += Math.log(i);
        }
        return s;
    }

    /**
     * The luck percentile (0..1) of getting {@code got} when {@code expected} were due. 0.5 when nothing was expected
     * (no attempts: no luck either way). Large means are summed exactly up to {@code got}; above 5,000 drops a normal
     * approximation with continuity correction is used instead (the sum would be slow and the two agree there).
     */
    public static double percentile(long got, double expected) {
        if (expected <= 0) {
            return 0.5;
        }
        if (got > 5_000 || expected > 5_000) {
            double z = (got - expected) / Math.sqrt(expected);
            return normalCdf(z);
        }
        double below = 0;
        for (int i = 0; i < got; i++) {
            below += poissonPmf(i, expected);
        }
        return Math.max(0, Math.min(1, below + poissonPmf((int) got, expected) / 2.0));
    }

    static double normalCdf(double z) {
        // Abramowitz-Stegun 7.1.26 on erf: |error| < 1.5e-7, plenty for a percentage shown to one decimal.
        double x = Math.abs(z) / Math.sqrt(2);
        double t = 1 / (1 + 0.3275911 * x);
        double erf = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t
                * Math.exp(-x * x);
        return z >= 0 ? 0.5 * (1 + erf) : 0.5 * (1 - erf);
    }

    /** Chance of at least one drop in {@code attempts} tries at {@code chance} each. */
    public static double atLeastOne(double chance, long attempts) {
        if (chance <= 0 || attempts <= 0) {
            return 0;
        }
        if (chance >= 1) {
            return 1;
        }
        return 1 - Math.exp(attempts * Math.log1p(-chance));
    }

    /** Attempts needed for a {@code target} (0..1) chance of at least one drop; -1 when it never gets there. */
    public static long attemptsFor(double chance, double target) {
        if (chance <= 0 || target <= 0 || target >= 1) {
            return -1;
        }
        if (chance >= 1) {
            return 1;
        }
        return (long) Math.ceil(Math.log1p(-target) / Math.log1p(-chance));
    }

    /** "1/7,348" for a chance, or "-" for none. */
    public static String oneIn(double chance) {
        if (Double.isNaN(chance) || chance <= 0) {
            return "-";
        }
        double n = 1 / chance;
        return n < 10 ? String.format(Locale.US, "1/%.1f", n) : String.format(Locale.US, "1/%,d", Math.round(n));
    }

    /** "luckier than 83% of players" / "unluckier than 70%" / "right on the odds". */
    public static String describe(double percentile) {
        int p = (int) Math.round(percentile * 100);
        if (p >= 45 && p <= 55) {
            return "right on the odds";
        }
        return p > 50 ? "luckier than " + p + "% of players" : "unluckier than " + (100 - p) + "% of players";
    }

    /** "+42%" / "-18%": got against expected as a ratio; "-" when nothing was expected. */
    public static String ratio(long got, double expected) {
        if (expected <= 0) {
            return "-";
        }
        long pct = Math.round((got / expected - 1) * 100);
        return (pct >= 0 ? "+" : "") + pct + "%";
    }

    public static String expected(double e) {
        if (e <= 0) {
            return "0";
        }
        if (e < 0.01) {
            return String.format(Locale.US, "%.4f", e);
        }
        if (e < 100) {
            return String.format(Locale.US, "%.2f", e);
        }
        return String.format(Locale.US, "%,.0f", e);
    }
}
