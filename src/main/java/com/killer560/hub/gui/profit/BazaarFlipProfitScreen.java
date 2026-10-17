package com.killer560.hub.gui.profit;

import com.killer560.hub.bazaarflip.BazaarFlipProfitLog;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;
import java.util.Locale;

/**
 * {@code /profit bazaarflip}: the Bazaar Flipper's cycles (CHEAT BUILD ONLY - {@link ProfitTracker#available()} leaves
 * the card and the command out of a legit jar). Net, Spent, Received and Tax across the cycles the switches select,
 * recent cycles on the left, net per cycle on the right. Every number is Hypixel's own (see {@link BazaarFlipProfitLog}).
 */
public class BazaarFlipProfitScreen extends ProfitTrackerScreen {

    public BazaarFlipProfitScreen(Screen parent) {
        super(parent, "Bazaar Flipper Profit");
    }

    @Override
    protected List<Card> cards() {
        BazaarFlipProfitLog.Totals t = BazaarFlipProfitLog.totals(BazaarFlipProfitLog.scope());
        long net = Math.round(t.net());
        long unsold = t.bought() - t.sold();
        return List.of(
                new Card("Net Profit", ProfitPanels.signedCoins(net), net >= 0 ? ProfitPanels.GOOD : ProfitPanels.BAD,
                        t.cycles() + " cycles", net >= 0 ? ProfitPanels.GOOD : ProfitPanels.BAD),
                new Card("Spent", ProfitPanels.coins(Math.round(t.spent())), ProfitPanels.BAD,
                        String.format(Locale.US, "%,d bought", t.bought()), ProfitPanels.BAD),
                new Card("Received", ProfitPanels.coins(Math.round(t.received())), ProfitPanels.GOOD,
                        String.format(Locale.US, "%,d sold", t.sold()) + (unsold > 0
                                ? String.format(Locale.US, ", %,d unsold", unsold) : ""), ProfitPanels.GOOD),
                new Card("Tax", ProfitPanels.coins(Math.round(t.tax())), ProfitPanels.TEXT,
                        "Instant buy and NPC sales: none", ProfitPanels.ACCENT));
    }

    @Override
    protected String listTitle() {
        return "RECENT CYCLES";
    }

    @Override
    protected List<String> listLines() {
        return BazaarFlipProfitLog.recentLines(14);
    }

    @Override
    protected String emptyText() {
        return "No flips finished yet";
    }

    @Override
    protected String detailTitle() {
        return "NET PER CYCLE";
    }

    @Override
    protected long[] graph() {
        return BazaarFlipProfitLog.netPerCycle();
    }

    @Override
    protected String note() {
        return null;
    }

    @Override
    protected void reset(ProfitScope.Filter filter) {
        BazaarFlipProfitLog.reset(filter);
    }

    @Override
    protected ProfitScope.Filter filter() {
        return BazaarFlipProfitLog.view();
    }

    @Override
    protected void saveFilter() {
        BazaarFlipProfitLog.saveView();
    }

    @Override
    protected java.util.Collection<ProfitScope.Key> keys() {
        return BazaarFlipProfitLog.keys();
    }
}
