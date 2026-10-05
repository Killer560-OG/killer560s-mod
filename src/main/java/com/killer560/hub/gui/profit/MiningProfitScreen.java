package com.killer560.hub.gui.profit;

import com.killer560.hub.mining.profit.MiningItemPricer;
import com.killer560.hub.mining.profit.MiningProfitConfig;
import com.killer560.hub.mining.profit.MiningProfitTracker;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/** {@code /profit mining}: the Mining profit-per-hour tracker, same data as the Profit Per Hour settings tab. */
public class MiningProfitScreen extends ProfitTrackerScreen {

    public MiningProfitScreen(Screen parent) {
        super(parent, "Mining Profit");
    }

    @Override
    protected List<Card> cards() {
        return List.of(
                new Card("Coins / Hour", MiningItemPricer.shortNumber(MiningProfitTracker.getCoinsPerHour()),
                        ProfitPanels.GOOD, "Active time only", ProfitPanels.GOOD),
                new Card("Total Value", MiningItemPricer.shortNumber(MiningProfitTracker.getTotalValueCoins()),
                        ProfitPanels.TEXT, "Bazaar / AH value", ProfitPanels.ACCENT),
                new Card("Active Time", MiningProfitTracker.formatDuration(MiningProfitTracker.getActiveMs()),
                        ProfitPanels.TEXT, MiningProfitTracker.getUnpricedGains() + " unpriced gains",
                        ProfitPanels.ACCENT));
    }

    @Override
    protected String listTitle() {
        return "TOP ITEMS";
    }

    @Override
    protected List<String> listLines() {
        return MiningProfitTracker.topItemLines(12);
    }

    @Override
    protected String emptyText() {
        return "Nothing gained yet";
    }

    @Override
    protected String detailTitle() {
        return "HOW IT IS MEASURED";
    }

    @Override
    protected List<String> detailLines() {
        return List.of("Items gained in your inventory,",
                "armor and off-hand are valued at",
                "live Bazaar / Auction House prices.",
                "Time only counts while active.");
    }

    @Override
    protected String note() {
        return MiningProfitConfig.getInstance().isEnabledRaw() ? null
                : "Tracker is off - turn it on in Settings > Mining > Profit Per Hour";
    }

    @Override
    protected void reset() {
        MiningProfitTracker.reset();
    }
}
