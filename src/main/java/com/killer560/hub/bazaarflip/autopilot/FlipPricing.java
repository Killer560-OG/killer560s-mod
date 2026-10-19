package com.killer560.hub.bazaarflip.autopilot;

import com.killer560.hub.auction.BazaarProduct;

import java.util.List;

/**
 * The Bazaar Advisor's pricing model, pure (no Minecraft types), so the advisor's chat line and the Bazaar Flipper's
 * autopilot rank products the same way and a simulator can run it without a client. {@code BazaarAdvisorFeature.recommend}
 * delegates here; there is no second pricing model.
 * <p>
 * For each product: <b>buy</b> is the cheapest sell offer (Insta Buy) or the best buy order plus {@link #TICK} (Buy
 * Order). <b>sell</b> is the NPC price, or on the Bazaar the best buy order less the {@link #SELL_TAX} (Insta Sell) or the
 * cheapest sell offer less {@link #TICK} and the same tax (Sell Offer). <b>How many</b>: an Insta Buy is capped by the free
 * inventory room, a Buy Order only by the purse; both by the purse, NPC sales by the daily NPC limit. <b>How much sells</b>:
 * the weekly volume on the side sold into, over seven, is a day's sales; only what sells within {@link #HORIZON_DAYS} counts.
 */
public final class FlipPricing {

    /** Bazaar sell tax taken from what a sell fills at. */
    public static final double SELL_TAX = 0.0125;
    /** How far a buy order bids above the best buy order, and a sell offer undercuts the cheapest offer, in coins. */
    public static final double TICK = 0.1;
    /** Days of sales the expected profit is counted over. */
    public static final double HORIZON_DAYS = 1.0;

    /** The four /bzflip answers as plain flags (the enums live in the Minecraft-side config class). */
    public record Answers(boolean npc, boolean percentGoal, boolean instaBuy, boolean instaSell) {
    }

    /** What the model needs from outside the Bazaar snapshot. Every method must be cheap and never throw. */
    public interface Lookups {
        /** The NPC sell price per unit, or null when the NPC does not buy it. */
        Double npcPrice(String productId);

        /** Max stack size of the item (64 when unknown). */
        int stackSize(String productId);

        /** How many more units fit in the inventory, or -1 when the inventory is not known (no cap). */
        long room(String productId, String displayName, int stackSize);

        /** Coins the NPCs will still pay today. */
        double npcRemaining();
    }

    /** One priced flip. Field names match the advisor's {@code Recommendation}. */
    public record Pick(String productId, String name, long units, double buy, double sell, double profitEach,
                       double profitPercent, double expectedProfit, long expectedSold, String buyLabel,
                       String sellLabel, String capNote) {

        public double spend() {
            return units * buy;
        }
    }

    private FlipPricing() {
    }

    /** Buy price per unit under these answers, or 0 when the side is empty. */
    public static double buyPrice(BazaarProduct p, boolean instaBuy) {
        if (instaBuy) {
            return p.topSellOfferPrice();
        }
        return p.topBuyOrderPrice() > 0 ? p.topBuyOrderPrice() + TICK : 0;
    }

    /** What a unit nets on the Bazaar after tax: Insta Sell into the best buy order, or a sell offer one tick under. */
    public static double bazaarSellNet(BazaarProduct p, boolean instaSell) {
        if (instaSell) {
            return p.topBuyOrderPrice() * (1 - SELL_TAX);
        }
        double offer = p.topSellOfferPrice() - TICK;
        return offer > 0 ? offer * (1 - SELL_TAX) : 0;
    }

    /** Units sold per day on the side a sale goes into (Hypixel's names are from the instant trader's side). */
    public static double soldPerDay(BazaarProduct p, boolean instaSell) {
        return (instaSell ? p.sellMovingWeek() : p.buyMovingWeek()) / 7.0;
    }

    /** Units a buy order fills per day: instant sells hit buy orders. */
    public static double buyOrderFillsPerDay(BazaarProduct p) {
        return p.sellMovingWeek() / 7.0;
    }

    /** The best product for these answers, or null when nothing pays. {@code skip} names product ids to leave out. */
    public static Pick best(List<BazaarProduct> products, Answers a, Lookups look, Double purse,
                            java.util.function.Predicate<String> skip) {
        Pick best = null;
        for (BazaarProduct p : products) {
            if (skip != null && skip.test(p.productId())) {
                continue;
            }
            Pick r = price(p, a, look, purse);
            if (r != null && (best == null || beats(a, r, best))) {
                best = r;
            }
        }
        return best;
    }

    /** One product priced under these answers, or null when it does not pay or none can be bought. */
    public static Pick price(BazaarProduct p, Answers a, Lookups look, Double purse) {
        double buy = buyPrice(p, a.instaBuy());
        if (buy <= 0) {
            return null;
        }
        double sell;
        double perDay;
        String sellLabel;
        if (a.npc()) {
            Double npcPrice = look.npcPrice(p.productId());
            if (npcPrice == null || npcPrice <= 0) {
                return null;
            }
            sell = npcPrice;
            perDay = Double.MAX_VALUE; // the NPC takes what it will take; its daily limit caps the count below
            sellLabel = "sell to the NPC";
        } else {
            sell = bazaarSellNet(p, a.instaSell());
            perDay = soldPerDay(p, a.instaSell());
            sellLabel = a.instaSell() ? "sell with Insta Sell" : "sell with a Sell Offer";
        }
        double profitEach = sell - buy;
        if (profitEach <= 0 || sell <= 0) {
            return null;
        }
        if (!a.npc() && perDay < 1) {
            return null; // nobody is trading it on the side we sell into
        }
        int stack = Math.max(1, look.stackSize(p.productId()));
        long cap = Long.MAX_VALUE;
        String capNote = "as many as your purse allows";
        if (a.instaBuy()) {
            long room = look.room(p.productId(), p.displayName(), stack);
            if (room >= 0) {
                cap = room;
                capNote = "capped by your inventory space";
            }
        }
        if (purse != null) {
            cap = Math.min(cap, (long) Math.floor(purse / buy));
        }
        if (a.npc()) {
            cap = Math.min(cap, (long) Math.floor(look.npcRemaining() / sell));
        }
        if (cap < 1) {
            return null;
        }
        long sold = a.npc() ? cap : (long) Math.min(cap, Math.floor(perDay * HORIZON_DAYS));
        if (sold < 1) {
            return null;
        }
        return new Pick(p.productId(), p.displayName(), cap, buy, sell, profitEach, profitEach / buy * 100.0,
                profitEach * sold, sold, a.instaBuy() ? "Insta Buy" : "Buy Order", sellLabel, capNote);
    }

    /** Total profit ranks by expected profit; percentage by profit per coin spent, then expected profit. */
    public static boolean beats(Answers a, Pick x, Pick y) {
        if (a.percentGoal()) {
            if (x.profitPercent() != y.profitPercent()) {
                return x.profitPercent() > y.profitPercent();
            }
            return x.expectedProfit() > y.expectedProfit();
        }
        if (x.expectedProfit() != y.expectedProfit()) {
            return x.expectedProfit() > y.expectedProfit();
        }
        return x.profitPercent() > y.profitPercent();
    }
}
