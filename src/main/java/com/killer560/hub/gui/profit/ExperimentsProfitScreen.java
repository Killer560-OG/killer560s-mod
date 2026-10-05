package com.killer560.hub.gui.profit;

import com.killer560.hub.experiments.ExperimentsConfig;
import com.killer560.hub.experiments.ExperimentsProfitTracker;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/** {@code /profit etable}: the Experimentation Table tracker, same data as the Experiments settings tab. */
public class ExperimentsProfitScreen extends ProfitTrackerScreen {

    public ExperimentsProfitScreen(Screen parent) {
        super(parent, "Experimentation Table Profit");
    }

    @Override
    protected List<Card> cards() {
        long sessions = ExperimentsProfitTracker.getTotalSessions();
        return List.of(
                new Card("Reward Value", ProfitPanels.coins(Math.round(ExperimentsProfitTracker.getTotalValueCoins())),
                        ProfitPanels.GOOD, sessions + " experiments", ProfitPanels.GOOD),
                new Card("Enchanting Exp", ExperimentsProfitTracker.formatShort(ExperimentsProfitTracker.getTotalXp()),
                        ProfitPanels.TEXT, "Superpairs " + ExperimentsProfitTracker.getSessionsFor("Superpairs")
                        + "  Chrono " + ExperimentsProfitTracker.getSessionsFor("Chronomatron")
                        + "  Ultra " + ExperimentsProfitTracker.getSessionsFor("Ultrasequencer"), ProfitPanels.ACCENT),
                new Card("Bits Spent", String.valueOf(ExperimentsProfitTracker.getTotalBitsSpent()),
                        ProfitPanels.BAD, "Bonus charges", ProfitPanels.BAD));
    }

    @Override
    protected String listTitle() {
        return "TOP REWARDS";
    }

    @Override
    protected List<String> listLines() {
        return ExperimentsProfitTracker.topItemLines(12);
    }

    @Override
    protected String emptyText() {
        return "No rewards claimed yet";
    }

    @Override
    protected String detailTitle() {
        return "VALUE PER EXPERIMENT";
    }

    @Override
    protected long[] graph() {
        return ExperimentsProfitTracker.sessionValues();
    }

    @Override
    protected String note() {
        return ExperimentsConfig.getInstance().isProfitTrackerEnabled() ? null
                : "Tracker is off - turn it on in Settings > Experiments";
    }

    @Override
    protected void reset() {
        ExperimentsProfitTracker.reset();
    }
}
