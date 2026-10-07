package com.killer560.hub.gui.profit;

import com.killer560.hub.mining.nucleus.NucleusRunProfitConfig;
import com.killer560.hub.mining.nucleus.NucleusRunProfitTracker;
import com.killer560.hub.mining.profit.MiningItemPricer;
import com.killer560.hub.mining.profit.MiningProfitTracker;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/** {@code /profit nucleus}: the Crystal Nucleus run tracker, same data as the Nucleus Run Profit settings tab. */
public class NucleusProfitScreen extends ProfitTrackerScreen {

    public NucleusProfitScreen(Screen parent) {
        super(parent, "Nucleus Run Profit");
    }

    @Override
    protected List<Card> cards() {
        return List.of(
                new Card("Runs", String.valueOf(NucleusRunProfitTracker.getRunsCompleted()), ProfitPanels.TEXT,
                        "Active " + MiningProfitTracker.formatDuration(NucleusRunProfitTracker.getActiveMs()),
                        ProfitPanels.ACCENT),
                new Card("Coins / Run", MiningItemPricer.shortNumber(NucleusRunProfitTracker.getCoinsPerRun()),
                        ProfitPanels.GOOD, "Average per run", ProfitPanels.GOOD),
                new Card("Coins / Hour", MiningItemPricer.shortNumber(NucleusRunProfitTracker.getCoinsPerHour()),
                        ProfitPanels.GOOD, MiningItemPricer.shortNumber(NucleusRunProfitTracker.getTotalValueCoins())
                        + " total", ProfitPanels.GOOD));
    }

    @Override
    protected String listTitle() {
        return "SESSION";
    }

    @Override
    protected List<String> listLines() {
        long runs = NucleusRunProfitTracker.getRunsCompleted();
        if (runs == 0) {
            return List.of();
        }
        return List.of("Runs completed: " + runs,
                "Total value: " + MiningItemPricer.shortNumber(NucleusRunProfitTracker.getTotalValueCoins()));
    }

    @Override
    protected String emptyText() {
        return "No runs yet";
    }

    @Override
    protected String detailTitle() {
        return "HOW IT IS MEASURED";
    }

    @Override
    protected List<String> detailLines() {
        return List.of("A run is Hypixel's own Crystal",
                "Nucleus loot-bundle message.",
                "Item values are best-effort; key,",
                "Apparatus and robot part costs are",
                "not subtracted. The run count is exact.");
    }

    @Override
    protected String note() {
        return NucleusRunProfitConfig.getInstance().isEnabledRaw() ? null
                : "Tracker is off - turn it on in Settings > Mining > Nucleus Run Profit";
    }

    @Override
    protected void reset() {
        NucleusRunProfitTracker.reset();
    }
}
