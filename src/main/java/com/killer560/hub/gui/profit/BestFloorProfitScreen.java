package com.killer560.hub.gui.profit;

import com.killer560.hub.croesus.CroesusProfitLog;
import com.killer560.hub.runsummary.RunRecord;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /profit floors}: coins per hour for each floor from the player's own run history and Croesus claims (see
 * {@link FloorRates}). It reads the Croesus log, so its account / profile switches and Reset Totals are that log's.
 * The rows are computed once per change of either record, never per frame.
 */
public class BestFloorProfitScreen extends ProfitTrackerScreen {

    public BestFloorProfitScreen(Screen parent) {
        super(parent, "Best Floor To Farm");
    }

    private List<FloorRates.Row> rows() {
        return FloorRates.live(false);
    }

    /** The first row if it has a rate, else null. */
    private FloorRates.Row best() {
        List<FloorRates.Row> r = rows();
        return !r.isEmpty() && r.get(0).enough() ? r.get(0) : null;
    }

    @Override
    protected void init() {
        FloorRates.live(true); // a switch changed the claims the rows read
        super.init();
    }

    @Override
    protected List<Card> cards() {
        FloorRates.Row b = best();
        if (b == null) {
            return List.of(new Card("Best Floor", "not enough data yet", ProfitPanels.DIM,
                    "needs " + FloorRates.MIN_RUNS + " claimed runs on a floor", ProfitPanels.DIM));
        }
        return List.of(
                new Card("Best Floor", b.floor(), ProfitPanels.GOOD, ProfitPanels.coins(b.perHour()) + " per hour",
                        ProfitPanels.GOOD),
                new Card("Per Run", ProfitPanels.signedCoins(b.profitPerRun()), b.profitPerRun() >= 0
                        ? ProfitPanels.GOOD : ProfitPanels.BAD, b.claimedRuns() + " claimed runs", ProfitPanels.ACCENT),
                new Card("Between Runs", b.gapSamples() == 0 ? "no data" : time(b.avgGapMs()), ProfitPanels.TEXT,
                        "run " + time(b.avgRunMs()), ProfitPanels.ACCENT));
    }

    @Override
    protected String listTitle() {
        return "COINS PER HOUR";
    }

    @Override
    protected List<String> listLines() {
        List<String> out = new ArrayList<>();
        for (FloorRates.Row r : rows()) {
            out.add(r.enough() ? r.floor() + "  " + ProfitPanels.coins(r.perHour()) + "/h  (" + r.claimedRuns() + " runs)"
                    : r.floor() + "  not enough data yet (" + r.claimedRuns() + "/" + FloorRates.MIN_RUNS + " runs)");
        }
        return out;
    }

    @Override
    protected String emptyText() {
        return "No claimed runs yet";
    }

    @Override
    protected String detailTitle() {
        return "WHAT A RUN COSTS IN TIME";
    }

    @Override
    protected List<String> detailLines() {
        List<String> out = new ArrayList<>();
        FloorRates.Row b = best();
        if (b == null) {
            out.add("Claim chests from Croesus after runs");
            out.add("and each floor gets a rate at " + FloorRates.MIN_RUNS + " runs.");
            return out;
        }
        out.add(b.floor() + ": run " + time(b.avgRunMs()) + ", between runs "
                + (b.gapSamples() == 0 ? "no data" : time(b.avgGapMs())));
        out.add("One more minute between runs costs "
                + String.format(java.util.Locale.US, "%.0f%%", b.minuteCostPercent()) + " of the hour.");
        out.add("Profit counts chest value minus keys and chests.");
        out.add("Unclaimed runs count once you claim them.");
        return out;
    }

    private static String time(long ms) {
        return RunRecord.formatTime(ms);
    }

    @Override
    protected String note() {
        return null;
    }

    @Override
    protected void reset(ProfitScope.Filter filter) {
        CroesusProfitLog.reset(filter);
    }

    @Override
    protected ProfitScope.Filter filter() {
        return CroesusProfitLog.view();
    }

    @Override
    protected void saveFilter() {
        CroesusProfitLog.saveView();
    }

    @Override
    protected java.util.Collection<ProfitScope.Key> keys() {
        return CroesusProfitLog.keys();
    }
}
