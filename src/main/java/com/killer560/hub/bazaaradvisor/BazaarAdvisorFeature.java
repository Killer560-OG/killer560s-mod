package com.killer560.hub.bazaaradvisor;

import com.killer560.hub.auction.BazaarApi;
import com.killer560.hub.auction.BazaarProduct;
import com.killer560.hub.bazaarflip.BazaarFlipFeature;
import com.killer560.hub.bazaarflip.BazaarFlipMenus;
import com.killer560.hub.bazaarflip.BazaarFlipNpcLimit;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.itembrowser.SkyblockItemEntry;
import com.killer560.hub.itembrowser.SkyblockItemRepository;
import com.killer560.hub.itembrowser.SkyblockItemStackFactory;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.List;
import java.util.Locale;

/**
 * Bazaar Advisor (killer560, 2026-10-19: "use all the info they give to recommend the right item ... make sure to account
 * for things like amount sold ... Make this highly advanced"). Works from the answers {@code /bzflip} stored in
 * {@link BazaarAdvisorConfig} and prints one recommendation in chat every time a Bazaar menu opens.
 * <p>
 * For each product: <b>buy</b> is the cheapest sell offer (Insta Buy) or the best buy order plus 0.1 (Buy Order, a bid that
 * outbids the current best). <b>sell</b> is the NPC price, or on the Bazaar the best buy order less the 1.25% tax (Insta Sell)
 * or the cheapest sell offer less 0.1 and the same tax (Sell Offer). <b>How many</b>: an Insta Buy is capped by the free
 * inventory room, a Buy Order only by the purse (as high as possible); both are capped by the purse, and NPC sales by the
 * daily NPC limit. <b>How much sells</b>: the Bazaar's weekly volume on the side you sell into, divided by seven, is what
 * sells in a day, so the expected profit counts only the units that can be sold within one day - a big stack of a product
 * nobody buys ranks low. The ranking is that expected profit (Total profit per inventory) or the profit percentage (Best
 * profit percentage).
 */
public final class BazaarAdvisorFeature {

    private static final String CHAT = "Bazaar Advisor";
    /** Bazaar sell tax taken from the amount a sell fills at. */
    private static final double SELL_TAX = 0.0125;
    /** How far a buy order bids above the best buy order, and an undercut below the cheapest sell offer, in coins. */
    private static final double TICK = 0.1;
    /** Days of sales the expected profit is counted over: what sells within one day counts. */
    private static final double HORIZON_DAYS = 1.0;

    /** The screen the advice was last given for: one line per menu open, not one per tick. */
    private static AbstractContainerScreen<?> advisedScreen;

    private BazaarAdvisorFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(BazaarAdvisorFeature::tick);
    }

    private static void tick(Minecraft client) {
        try {
            if (!BazaarAdvisorConfig.getInstance().isEnabled() || !(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)) {
                return;
            }
            if (screen == advisedScreen || !BazaarFlipMenus.titleOf(screen).startsWith("Bazaar")) {
                return;
            }
            advisedScreen = screen;
            advise(screen.getMenu());
        } catch (RuntimeException e) {
            // A bad read must never stop the game's tick; the advisor just says nothing for this menu.
            advisedScreen = null;
        }
    }

    /** Prints the current recommendation for the open Bazaar menu. Also used by the setup screen's summary. */
    public static void advise(AbstractContainerMenu menu) {
        List<BazaarProduct> products = BazaarApi.getProducts();
        if (products == null || products.isEmpty()) {
            ModChat.send(CHAT, ModChat.dim("No Bazaar prices loaded yet - open the menu again in a moment."));
            return;
        }
        BazaarAdvisorConfig cfg = BazaarAdvisorConfig.getInstance();
        Double purse = BazaarFlipFeature.readPurse();
        Recommendation r = recommend(products, cfg, menu, purse);
        if (r == null) {
            ModChat.send(CHAT, ModChat.text("Nothing is worth buying with your settings right now ("),
                    ModChat.dim(cfg.summary() + ")."));
            return;
        }
        ModChat.send(CHAT, ModChat.text("Buy "), ModChat.value(String.format(Locale.US, "%,d", r.units()) + " " + r.name()),
                ModChat.text(" - " + r.buyLabel() + " at ~" + money(r.buy()) + " each, " + r.sellLabel() + " at ~" + money(r.sell())
                        + " for +" + money(r.profitEach()) + " each (" + String.format(Locale.US, "%.1f", r.profitPercent()) + "%)."));
        ModChat.send(CHAT, ModChat.dim("Spend ~" + money(r.spend()) + ". Expected profit ~" + money(r.expectedProfit())
                + " from ~" + String.format(Locale.US, "%,d", r.expectedSold()) + " sold in a day. " + r.capNote()
                + " Settings: " + cfg.summary() + "."));
    }

    /**
     * The best product for these answers, or null when nothing is profitable under them. Pure apart from the inventory room
     * read from {@code menu}, so the setup summary and the menu hook share it.
     */
    public static Recommendation recommend(List<BazaarProduct> products, BazaarAdvisorConfig cfg, AbstractContainerMenu menu, Double purse) {
        boolean insta = cfg.getBuyMethod() == BazaarAdvisorConfig.BuyMethod.INSTA_BUY;
        boolean npc = cfg.getMode() == BazaarAdvisorConfig.Mode.NPC;
        Recommendation best = null;
        for (BazaarProduct p : products) {
            SkyblockItemEntry entry = SkyblockItemRepository.findById(p.productId());
            double buy = insta ? p.topSellOfferPrice() : p.topBuyOrderPrice() + TICK;
            if (buy <= 0) {
                continue;
            }
            double sell;
            double soldPerDay;
            String sellLabel;
            if (npc) {
                Double npcPrice = entry == null ? null : entry.npcSellPrice();
                if (npcPrice == null || npcPrice <= 0) {
                    continue;
                }
                sell = npcPrice;
                soldPerDay = Double.MAX_VALUE; // the NPC takes what it will take; its daily limit caps the count below
                sellLabel = "sell to the NPC";
            } else if (cfg.getSellMethod() == BazaarAdvisorConfig.SellMethod.INSTA_SELL) {
                sell = p.topBuyOrderPrice() * (1 - SELL_TAX);
                soldPerDay = p.sellMovingWeek() / 7.0;
                sellLabel = "sell with Insta Sell";
            } else {
                sell = (p.topSellOfferPrice() - TICK) * (1 - SELL_TAX);
                soldPerDay = p.buyMovingWeek() / 7.0;
                sellLabel = "sell with a Sell Offer";
            }
            double profitEach = sell - buy;
            if (profitEach <= 0 || sell <= 0) {
                continue;
            }
            if (!npc && soldPerDay < 1) {
                continue; // nobody is trading it on the Bazaar side we sell into
            }
            int stack = entry == null ? 64 : Math.max(1, SkyblockItemStackFactory.materialMaxStackSize(entry.material()));
            long cap = Long.MAX_VALUE;
            String capNote = "as many as your purse allows";
            if (insta && menu != null) {
                cap = Math.max(0, BazaarFlipMenus.roomFor(menu, p.productId(), p.displayName(), stack));
                capNote = "capped by your inventory space";
            }
            if (purse != null) {
                cap = Math.min(cap, (long) Math.floor(purse / buy));
            }
            if (npc) {
                cap = Math.min(cap, (long) Math.floor(BazaarFlipNpcLimit.remaining() / sell));
            }
            if (cap < 1) {
                continue;
            }
            long units = cap;
            long sold = npc ? units : (long) Math.min(units, Math.floor(soldPerDay * HORIZON_DAYS));
            if (sold < 1) {
                continue;
            }
            double expected = profitEach * sold;
            double percent = profitEach / buy * 100.0;
            Recommendation r = new Recommendation(p.productId(), p.displayName(), units, buy, sell, profitEach, percent,
                    expected, sold, insta ? "Insta Buy" : "Buy Order", sellLabel, capNote);
            if (best == null || beats(cfg, r, best)) {
                best = r;
            }
        }
        return best;
    }

    /** Total profit ranks by the expected profit; percentage ranks by the profit per coin spent, then by expected profit. */
    private static boolean beats(BazaarAdvisorConfig cfg, Recommendation a, Recommendation b) {
        if (cfg.getGoal() == BazaarAdvisorConfig.Goal.PROFIT_PERCENT) {
            if (a.profitPercent() != b.profitPercent()) {
                return a.profitPercent() > b.profitPercent();
            }
            return a.expectedProfit() > b.expectedProfit();
        }
        if (a.expectedProfit() != b.expectedProfit()) {
            return a.expectedProfit() > b.expectedProfit();
        }
        return a.profitPercent() > b.profitPercent();
    }

    private static String money(double coins) {
        return String.format(Locale.US, "%,.1f", coins);
    }

    /** One recommendation: what to buy, how many, both prices, the profit, and what the sales can absorb in a day. */
    public record Recommendation(String productId, String name, long units, double buy, double sell, double profitEach,
                                 double profitPercent, double expectedProfit, long expectedSold, String buyLabel,
                                 String sellLabel, String capNote) {

        public double spend() {
            return units * buy;
        }
    }
}
