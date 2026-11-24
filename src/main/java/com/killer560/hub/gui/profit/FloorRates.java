package com.killer560.hub.gui.profit;

import com.killer560.hub.croesus.CroesusProfitLog;
import com.killer560.hub.runsummary.RunRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Coins per hour per floor from the player's own data (idea bestfloor-1124): the run history gives run times and the
 * gap between runs, the Croesus claim log gives chest profit (value minus key and chest cost, already priced when it
 * was claimed). A claim belongs to the latest run of its floor that ended before it; a run counts only once it has a
 * claim, so an unclaimed run never drags a floor down and never counts for free. A floor needs {@link #MIN_RUNS}
 * claimed runs before it gets a rate. Pure functions over the two lists, so the screen and the testkit share them.
 */
public final class FloorRates {

    public static final int MIN_RUNS = 3;
    /** A gap longer than this is a break, not queue time between runs. */
    public static final long MAX_GAP_MS = 20L * 60_000L;
    /** Croesus keeps a run's chests for three days; a claim never maps to a run older than that. */
    private static final long CLAIM_WINDOW_MS = 3L * 24 * 3_600_000L;

    /** One floor's numbers; {@code perHour} is meaningful only when {@code enough}. */
    public record Row(String floor, int claimedRuns, long profitPerRun, long avgRunMs, long avgGapMs, int gapSamples,
                      long perHour, boolean enough) {
        /** Share of the hour one more minute between runs takes. */
        public double minuteCostPercent() {
            long cycle = avgRunMs + avgGapMs;
            return cycle <= 0 ? 0 : 6000000.0 / cycle;
        }
    }

    private FloorRates() {
    }

    /** Best rate first, floors without enough data after them (most claimed runs first). */
    public static List<Row> compute(List<RunRecord> runs, List<CroesusProfitLog.Claim> claims) {
        List<RunRecord> sorted = new ArrayList<>();
        for (RunRecord r : runs) {
            if (r.floor() != null && !r.floor().isBlank() && r.totalMs() > 0) {
                sorted.add(r);
            }
        }
        sorted.sort(Comparator.comparingLong(FloorRates::endOf));
        Map<String, long[]> acc = new HashMap<>(); // floor -> claimedRuns, profit, runMs, gapMs, gapSamples
        Map<RunRecord, Boolean> claimed = new java.util.IdentityHashMap<>();
        for (CroesusProfitLog.Claim c : claims) {
            RunRecord match = null;
            for (RunRecord r : sorted) {
                if (r.floor().equals(c.floor()) && endOf(r) <= c.timestampMs() + 60_000L
                        && c.timestampMs() - endOf(r) <= CLAIM_WINDOW_MS) {
                    match = r; // oldest first, so the last one that fits is the latest
                }
            }
            if (match == null) {
                continue;
            }
            long[] a = acc.computeIfAbsent(match.floor(), k -> new long[5]);
            a[1] += c.profit();
            if (claimed.put(match, Boolean.TRUE) == null) {
                a[0]++;
                a[2] += match.totalMs();
            }
        }
        for (int i = 1; i < sorted.size(); i++) {
            long gap = sorted.get(i).startedAtMs() - endOf(sorted.get(i - 1));
            long[] a = acc.get(sorted.get(i).floor());
            if (a != null && gap >= 0 && gap <= MAX_GAP_MS) {
                a[3] += gap;
                a[4]++;
            }
        }
        List<Row> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : acc.entrySet()) {
            long[] a = e.getValue();
            long n = a[0];
            long perRun = a[1] / n;
            long runMs = a[2] / n;
            long gapMs = a[4] == 0 ? 0 : a[3] / a[4];
            long cycle = runMs + gapMs;
            boolean enough = n >= MIN_RUNS && cycle > 0;
            out.add(new Row(e.getKey(), (int) n, perRun, runMs, gapMs, (int) a[4],
                    enough ? Math.round(perRun * 3_600_000.0 / cycle) : 0, enough));
        }
        out.sort(Comparator.comparing(Row::enough).reversed().thenComparing(
                (Row r) -> r.enough() ? -r.perHour() : -(long) r.claimedRuns()));
        return out;
    }

    private static List<Row> cached = List.of();
    private static int cachedRevision = -1;
    private static int cachedRuns = -1;

    /** The live rows, recomputed only when the claim log or the run history changed (the hub card and the screen
     *  ask every frame); the scope is the Croesus screen's own. {@code force} recomputes after a scope switch. */
    public static synchronized List<Row> live(boolean force) {
        int rev = CroesusProfitLog.revision();
        int n = com.killer560.hub.runsummary.RunHistoryStore.size();
        if (force || rev != cachedRevision || n != cachedRuns) {
            cached = compute(com.killer560.hub.runsummary.RunHistoryStore.runs(), CroesusProfitLog.claims());
            cachedRevision = rev;
            cachedRuns = n;
        }
        return cached;
    }

    private static long endOf(RunRecord r) {
        return r.endedAtMs() > 0 ? r.endedAtMs() : r.startedAtMs() + r.totalMs();
    }
}
