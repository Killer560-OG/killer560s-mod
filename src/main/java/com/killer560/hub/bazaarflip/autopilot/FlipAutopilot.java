package com.killer560.hub.bazaarflip.autopilot;

import com.killer560.hub.auction.BazaarProduct;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Bazaar Flipper autopilot's decision core: given everything the bot knows, the ONE next action. Pure - no Minecraft
 * type, no clock, no randomness - so {@code tools/bazaarflip-sim} runs it over recorded or synthetic order books and the
 * menu driver runs it in game, and both get the same answer from the same inputs.
 * <p>
 * Pricing is {@link FlipPricing}, the Bazaar Advisor's model, and the answers are the advisor's /bzflip answers. On top
 * of that this adds what a bot needs and a one-shot recommendation does not: order bookkeeping, relisting, collecting,
 * capital limits and stop conditions.
 *
 * <h2>Order of business, every decision</h2>
 * <ol>
 *   <li><b>Collect</b> any of the session's orders with something to claim (items need inventory room).</li>
 *   <li><b>Sell</b> what the session bought and still carries: to the NPC (NPC mode), or on the Bazaar by the sell answer.
 *       A sell offer is replaced by an Insta Sell when Insta Sell nets within {@link Limits#instaSellSlack} of the offer,
 *       when the offer would take longer than {@link Limits#maxHoldDays} to fill, or when every order slot is taken.</li>
 *   <li><b>Relist</b> the session's orders that are outbid or expired, at the current best price, once they are
 *       {@link Limits#minRelistAgeMs} old - or <b>cancel</b> them when the new price no longer pays or the order has been
 *       relisted {@link Limits#maxRelistsPerOrder} times (a price war).</li>
 *   <li><b>Open</b> a new position on the best product the advisor's model picks, skipping products the session already
 *       has an order or stock in: an Insta Buy, or a buy order sized by the purse share, the expected fills and the cap.</li>
 *   <li>Otherwise <b>wait</b>, or <b>stop</b> when nothing is open and nothing more may be opened.</li>
 * </ol>
 *
 * <h2>Fill model</h2>
 * A buy order fills from instant sells ({@code sellMovingWeek / 7} a day), a sell offer from instant buys
 * ({@code buyMovingWeek / 7}). Sitting at the top of the book, the order gets a share of that flow that shrinks with
 * the number of competing orders on its side ({@link #fillShare}): flippers outbid each other, so a crowded product
 * fills a top order only part of the time.
 */
public final class FlipAutopilot {

    /** Orders on a side at which a top order's expected share of the flow halves. */
    public static final double COMPETITION_HALF = 25.0;
    /** Floor of {@link #fillShare}. */
    public static final double MIN_FILL_SHARE = 0.05;
    /** A price this close to the best counts as matching it (Hypixel prices to 0.1). */
    public static final double PRICE_EPS = 0.05;

    /**
     * Session limits. Defaults in {@link #defaults()}.
     *
     * @param reserve              coins never spent: nothing new is opened that would take the purse under it
     * @param maxOpenOrders        Hypixel's per-player order cap (14 without upgrades - unverified, see the report)
     * @param maxOrdersPerSession  new orders and Insta Buys this session may place; relists do not count
     * @param maxRelistsPerOrder   relists of one order before it is cancelled instead (price war guard)
     * @param minRelistAgeMs       an order is left alone this long after it was placed or relisted
     * @param maxOrderUnits        units in one order or one Insta Buy
     * @param maxSpendFraction     share of the spendable purse (purse - reserve) one new buy order may take
     * @param instaSellSlack       Insta Sell is preferred over a sell offer when it nets at least (1 - this) of the offer
     * @param maxHoldDays          stock or an order expected to take longer than this to sell or fill is sized down or sold
     * @param collectMinFraction   a part-filled order is collected once this share of it is waiting (a full one always)
     */
    public record Limits(double reserve, int maxOpenOrders, int maxOrdersPerSession, int maxRelistsPerOrder,
                         long minRelistAgeMs, long maxOrderUnits, double maxSpendFraction, double instaSellSlack,
                         double maxHoldDays, double collectMinFraction) {

        public static Limits defaults() {
            return new Limits(0, 14, 50, 5, 60_000L, 71_680L, 0.34, 0.005, 1.0, 0.25);
        }

        public Limits withReserve(double coins) {
            return new Limits(Math.max(0, coins), maxOpenOrders, maxOrdersPerSession, maxRelistsPerOrder, minRelistAgeMs,
                    maxOrderUnits, maxSpendFraction, instaSellSlack, maxHoldDays, collectMinFraction);
        }

        public Limits withMaxOrdersPerSession(int n) {
            return new Limits(reserve, maxOpenOrders, Math.max(0, n), maxRelistsPerOrder, minRelistAgeMs, maxOrderUnits,
                    maxSpendFraction, instaSellSlack, maxHoldDays, collectMinFraction);
        }
    }

    /** Hypixel wants an Insta Buy's quote (the cheapest offer plus 4%, the unused part refunded) in the purse up front
     *  (docs/LESSONS.md, "The Instant Buy page quotes the book plus 4%"). Same value as {@code BazaarFlipScanner}'s. */
    public static final double INSTANT_BUY_QUOTE_MARGIN = 0.04;

    /** Units the sell offers hold below {@code limitEach}, cheapest first: an Insta Buy bigger than this pays more
     *  than the sale brings back for the extra units. The levels the API gives are only the top of the book, so a
     *  book that runs out below the limit caps the buy at what was seen. */
    public static long profitableDepth(BazaarProduct p, double limitEach) {
        long units = 0;
        for (com.killer560.hub.auction.BazaarOrderLevel l : p.topSellOffers()) {
            if (l.pricePerUnit() >= limitEach) {
                break;
            }
            units += l.amount();
        }
        return units;
    }

    /** Coins an Insta Buy of {@code n} units costs, walking the sell offers cheapest first (the last level seen
     *  repeats past the end of the known book). */
    public static double walkCost(BazaarProduct p, long n) {
        double total = 0;
        long left = n;
        double last = 0;
        for (com.killer560.hub.auction.BazaarOrderLevel l : p.topSellOffers()) {
            long take = Math.min(left, l.amount());
            total += take * l.pricePerUnit();
            left -= take;
            last = l.pricePerUnit();
            if (left == 0) {
                return total;
            }
        }
        return total + left * last;
    }

    /**
     * Everything one decision is made from.
     *
     * @param products      the Bazaar snapshot
     * @param purse         coins in the purse now
     * @param holdings      units the SESSION bought (or got back from a cancelled offer) and still carries, by product id.
     *                      Never the player's own items: the bot only sells what it bought.
     * @param costBasis     what each held unit cost, by product id (absent: unknown)
     * @param orders        every open order Manage Orders last showed (the bot's and the player's own)
     * @param placedThisSession new orders and Insta Buys placed so far this session
     */
    public record Situation(List<BazaarProduct> products, double purse, Map<String, Long> holdings,
                            Map<String, Double> costBasis, List<FlipOrder> orders, FlipPricing.Answers answers,
                            FlipPricing.Lookups lookups, Limits limits, int placedThisSession) {
    }

    private FlipAutopilot() {
    }

    /** The share of a side's daily flow an order at the top of that side can expect. */
    public static double fillShare(BazaarProduct p, boolean buyOrder) {
        // Hypixel's names are the instant trader's: sellOrders counts the BUY ORDERS, buyOrders the SELL OFFERS.
        int competitors = buyOrder ? p.sellOrders() : p.buyOrders();
        return Math.max(MIN_FILL_SHARE, 1.0 / (1.0 + Math.max(0, competitors) / COMPETITION_HALF));
    }

    /** Units a top buy order is expected to fill per day. */
    public static double expectedBuyFillsPerDay(BazaarProduct p) {
        return FlipPricing.buyOrderFillsPerDay(p) * fillShare(p, true);
    }

    /** Units a top sell offer is expected to sell per day. */
    public static double expectedOfferFillsPerDay(BazaarProduct p) {
        return FlipPricing.soldPerDay(p, false) * fillShare(p, false);
    }

    public static FlipAction decide(Situation s) {
        Map<String, BazaarProduct> byId = new HashMap<>();
        for (BazaarProduct p : s.products()) {
            byId.put(p.productId(), p);
        }
        FlipAction a = collect(s);
        if (a != null) {
            return a;
        }
        a = sellHoldings(s, byId);
        if (a != null) {
            return a;
        }
        a = relistOrCancel(s, byId);
        if (a != null) {
            return a;
        }
        a = open(s, byId);
        if (a != null) {
            return a;
        }
        int ours = 0;
        for (FlipOrder o : s.orders()) {
            if (o.ours()) {
                ours++;
            }
        }
        return new FlipAction.Wait(ours + " order(s) open, nothing else worth doing");
    }

    // ---- 1. collect --------------------------------------------------------------------------------------------

    private static FlipAction collect(Situation s) {
        for (FlipOrder o : s.orders()) {
            if (!o.ours() || !(o.claimable() || o.claimableItems() > 0 || o.claimableCoins() > 0)) {
                continue;
            }
            // Every claim is a trip to Manage Orders: wait for a full fill or a worthwhile part of one.
            long waiting = o.buy() ? o.claimableItems()
                    : o.claimableCoins() >= 0 && o.price() > 0 ? (long) Math.floor(o.claimableCoins() / o.price()) : -1;
            boolean full = o.filled() >= o.amount();
            if (!full && waiting >= 0 && waiting < Math.ceil(o.amount() * s.limits().collectMinFraction())) {
                continue;
            }
            if (o.buy() && o.claimableItems() != 0) {
                int stack = Math.max(1, s.lookups().stackSize(o.productId()));
                long room = s.lookups().room(o.productId(), o.name(), stack);
                if (room == 0) {
                    continue; // nothing fits; selling the stock first makes room
                }
            }
            return new FlipAction.Collect(o, o.buy() ? "filled items to claim" : "coins to claim");
        }
        return null;
    }

    // ---- 2. sell what the session holds ---------------------------------------------------------------------------

    private static FlipAction sellHoldings(Situation s, Map<String, BazaarProduct> byId) {
        FlipPricing.Answers ans = s.answers();
        for (Map.Entry<String, Long> e : s.holdings().entrySet()) {
            long units = e.getValue() == null ? 0 : e.getValue();
            if (units < 1) {
                continue;
            }
            String id = e.getKey();
            BazaarProduct p = byId.get(id);
            String name = p != null ? p.displayName() : id;
            Double cost = s.costBasis().get(id);
            if (ans.npc()) {
                Double npc = s.lookups().npcPrice(id);
                if (npc != null && npc > 0) {
                    long amount = Math.min(units, (long) Math.floor(s.lookups().npcRemaining() / npc));
                    if (amount >= 1) {
                        return new FlipAction.SellToNpc(id, name, amount, npc, "sell the stock to the NPC");
                    }
                }
                // The NPC will not take it today: Insta Sell only if that loses nothing against what it cost.
                if (p != null && cost != null && FlipPricing.bazaarSellNet(p, true) >= cost) {
                    return new FlipAction.InstaSell(id, name, units, FlipPricing.bazaarSellNet(p, true),
                            "the NPC limit is reached; Insta Sell covers the cost");
                }
                continue;
            }
            if (p == null) {
                continue; // no price for it: leave it, never sell blind
            }
            double instaNet = FlipPricing.bazaarSellNet(p, true);
            double offerNet = FlipPricing.bazaarSellNet(p, false);
            if (ans.instaSell()) {
                if (instaNet > 0) {
                    return new FlipAction.InstaSell(id, name, units, instaNet, "your sell answer is Insta Sell");
                }
                continue;
            }
            if (hasOrder(s, id, false)) {
                continue; // an offer for it is already up; this stock waits for it (or for a relist)
            }
            if (instaNet > 0 && instaNet >= offerNet * (1 - s.limits().instaSellSlack())) {
                return new FlipAction.InstaSell(id, name, units, instaNet, String.format(Locale.US,
                        "Insta Sell nets %.1f against the offer's %.1f", instaNet, offerNet));
            }
            double perDay = expectedOfferFillsPerDay(p);
            boolean slow = perDay <= 0 || units / perDay > s.limits().maxHoldDays();
            boolean noSlot = s.orders().size() >= s.limits().maxOpenOrders();
            if ((slow || noSlot) && instaNet > 0 && (cost == null || instaNet >= cost)) {
                return new FlipAction.InstaSell(id, name, units, instaNet,
                        noSlot ? "every order slot is taken" : "an offer would take more than a day to fill");
            }
            if (offerNet > 0 && !noSlot) {
                return new FlipAction.PlaceSellOffer(id, name, round1(p.topSellOfferPrice() - FlipPricing.TICK), units,
                        "list the stock one tick under the cheapest offer");
            }
        }
        return null;
    }

    // ---- 3. relist or cancel --------------------------------------------------------------------------------------

    private static FlipAction relistOrCancel(Situation s, Map<String, BazaarProduct> byId) {
        Limits lim = s.limits();
        for (FlipOrder o : s.orders()) {
            if (!o.ours() || o.unfilled() < 1) {
                continue;
            }
            BazaarProduct p = byId.get(o.productId());
            if (p == null) {
                continue;
            }
            boolean old = o.ageMs() < 0 || o.ageMs() >= lim.minRelistAgeMs();
            if (!old && !o.expired()) {
                continue;
            }
            double best = o.buy() ? p.topBuyOrderPrice() : p.topSellOfferPrice();
            if (best <= 0) {
                continue;
            }
            boolean outbid = o.buy() ? o.price() < best - PRICE_EPS : o.price() > best + PRICE_EPS;
            if (!outbid && !o.expired()) {
                continue;
            }
            double newPrice = round1(o.buy() ? best + FlipPricing.TICK : best - FlipPricing.TICK);
            if (o.relists() >= lim.maxRelistsPerOrder()) {
                return new FlipAction.Cancel(o, "relisted " + o.relists() + " times - a price war, giving up on it");
            }
            if (o.buy()) {
                double sellNet = unitSellNet(s, p);
                if (sellNet <= newPrice) {
                    return new FlipAction.Cancel(o, String.format(Locale.US,
                            "outbid, and a bid of %.1f no longer pays (sells for %.1f)", newPrice, sellNet));
                }
            } else {
                double newNet = newPrice * (1 - FlipPricing.SELL_TAX);
                if (newNet <= FlipPricing.bazaarSellNet(p, true)) {
                    return new FlipAction.Cancel(o, "undercut below what Insta Sell pays - cancel and Insta Sell");
                }
            }
            return new FlipAction.Relist(o, newPrice, o.expired() ? "expired" : String.format(Locale.US,
                    "outbid (%.1f against the best %.1f)", o.price(), best));
        }
        return null;
    }

    /** What one unit nets when sold the way the answers say. */
    static double unitSellNet(Situation s, BazaarProduct p) {
        if (s.answers().npc()) {
            Double npc = s.lookups().npcPrice(p.productId());
            return npc == null ? 0 : npc;
        }
        return FlipPricing.bazaarSellNet(p, s.answers().instaSell());
    }

    // ---- 4. open a new position -------------------------------------------------------------------------------------

    private static FlipAction open(Situation s, Map<String, BazaarProduct> byId) {
        Limits lim = s.limits();
        FlipPricing.Answers ans = s.answers();
        boolean anythingOpen = !s.orders().isEmpty() && s.orders().stream().anyMatch(FlipOrder::ours);
        boolean anythingHeld = s.holdings().values().stream().anyMatch(v -> v != null && v > 0);
        if (s.placedThisSession() >= lim.maxOrdersPerSession()) {
            return anythingOpen || anythingHeld ? null
                    : new FlipAction.Stop("the session's order cap (" + lim.maxOrdersPerSession() + ") is reached");
        }
        double spendable = s.purse() - lim.reserve();
        if (spendable <= 0) {
            return anythingOpen || anythingHeld ? null : new FlipAction.Stop(String.format(Locale.US,
                    "the purse (%,.0f) is at or under the reserve (%,.0f)", s.purse(), lim.reserve()));
        }
        if (!ans.instaBuy() && s.orders().size() >= lim.maxOpenOrders()) {
            return null;
        }
        Set<String> busy = new HashSet<>();
        double npcCommitted = 0;
        for (FlipOrder o : s.orders()) {
            if (o.ours()) {
                busy.add(o.productId());
                if (o.buy() && ans.npc()) {
                    Double npc = s.lookups().npcPrice(o.productId());
                    npcCommitted += npc == null ? 0 : npc * o.amount();
                }
            }
        }
        for (Map.Entry<String, Long> e : s.holdings().entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) {
                busy.add(e.getKey());
                if (ans.npc()) {
                    Double npc = s.lookups().npcPrice(e.getKey());
                    npcCommitted += npc == null ? 0 : npc * e.getValue();
                }
            }
        }
        final double npcLeft = Math.max(0, s.lookups().npcRemaining() - npcCommitted);
        FlipPricing.Lookups base = s.lookups();
        FlipPricing.Lookups look = new FlipPricing.Lookups() {
            @Override
            public Double npcPrice(String productId) {
                return base.npcPrice(productId);
            }

            @Override
            public int stackSize(String productId) {
                return base.stackSize(productId);
            }

            @Override
            public long room(String productId, String displayName, int stackSize) {
                return base.room(productId, displayName, stackSize);
            }

            @Override
            public double npcRemaining() {
                return npcLeft;
            }
        };
        double budget = ans.instaBuy() ? spendable / (1 + INSTANT_BUY_QUOTE_MARGIN) : spendable * lim.maxSpendFraction();
        // Buy orders: a product is only worth an order if a meaningful part of it can fill in the hold window.
        FlipPricing.Pick pick = FlipPricing.best(s.products(), ans, look, budget, id -> busy.contains(id)
                || (!ans.instaBuy() && byId.get(id) != null && expectedBuyFillsPerDay(byId.get(id)) * lim.maxHoldDays() < 1));
        if (pick == null) {
            return null;
        }
        BazaarProduct p = byId.get(pick.productId());
        long units = Math.min(pick.units(), lim.maxOrderUnits());
        if (!ans.npc()) {
            units = Math.min(units, Math.max(1, pick.expectedSold())); // never buy more than sells in the horizon
        }
        if (ans.instaBuy()) {
            // Only the units the offers hold below the sale price, and only what the purse covers at the walked price.
            units = Math.min(units, profitableDepth(p, pick.sell()));
            while (units >= 1 && walkCost(p, units) * (1 + INSTANT_BUY_QUOTE_MARGIN) > spendable) {
                units = units / 2;
            }
            if (units < 1) {
                return null;
            }
            return new FlipAction.InstaBuy(pick.productId(), pick.name(), units, pick.buy(), String.format(Locale.US,
                    "best flip: +%.1f each (%.2f%%)", pick.profitEach(), pick.profitPercent()));
        }
        long fillable = (long) Math.floor(expectedBuyFillsPerDay(p) * lim.maxHoldDays());
        units = Math.min(units, fillable);
        if (units < 1) {
            return null;
        }
        return new FlipAction.PlaceBuyOrder(pick.productId(), pick.name(), round1(pick.buy()), units,
                String.format(Locale.US, "best flip: +%.1f each (%.2f%%), ~%,d fill a day", pick.profitEach(),
                        pick.profitPercent(), (long) expectedBuyFillsPerDay(p)));
    }

    private static boolean hasOrder(Situation s, String productId, boolean buy) {
        for (FlipOrder o : s.orders()) {
            if (o.ours() && o.buy() == buy && o.productId() != null && o.productId().equals(productId)) {
                return true;
            }
        }
        return false;
    }

    /** Hypixel prices to one decimal. */
    public static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
