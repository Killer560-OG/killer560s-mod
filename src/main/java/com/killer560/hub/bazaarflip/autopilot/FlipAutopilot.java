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
 *       has a sell offer or stock in: an Insta Buy, or a buy order sized by the purse share, the expected fills and the cap.
 *       A product the session already bids on gets more buy orders, at the same price as its own, until they cover the
 *       day's expected fills or number {@link #offersFor} (at most {@link #MAX_OFFERS_PER_PRODUCT}).</li>
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

    /** Orders on a side at which a top order's expected share of the flow halves (the pricing model's). */
    public static final double COMPETITION_HALF = FlipPricing.COMPETITION_HALF;
    /** Floor of {@link #fillShare}. */
    public static final double MIN_FILL_SHARE = FlipPricing.MIN_FILL_SHARE;
    /**
     * Most buy orders the session keeps open on one product at once (killer560, 2026-10-20: "at most 5"). How many it
     * actually puts up follows the product's daily fills, {@link #offersFor}.
     */
    public static final int MAX_OFFERS_PER_PRODUCT = 5;
    /** A price this close to the best counts as matching it (Hypixel prices to 0.1). */
    public static final double PRICE_EPS = 0.05;

    /**
     * Session limits. Defaults in {@link #defaults()}.
     *
     * @param reserve              coins never spent: nothing new is opened that would take the purse under it
     * @param maxOpenOrders        Hypixel's per-player order cap (14 without upgrades - unverified, see the report)
     * @param maxOrdersPerSession  new orders and Insta Buys this session may place; relists do not count
     * @param maxRelistsPerOrder   relists of one order before it is canceled instead (price war guard)
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
     * @param holdings      units the SESSION bought (or got back from a canceled offer) and still carries, by product id.
     *                      Never the player's own items: the bot only sells what it bought.
     * @param costBasis     what each held unit cost, by product id (absent: unknown)
     * @param orders        every open order Manage Orders last showed (the bot's and the player's own)
     * @param placedThisSession new orders and Insta Buys placed so far this session
     * @param avoid         products no new position is opened in: ones the player carries of his own, because Sell
     *                      Instantly and a Sell Offer take every unit of the item, not just the bot's
     */
    public record Situation(List<BazaarProduct> products, double purse, Map<String, Long> holdings,
                            Map<String, Double> costBasis, List<FlipOrder> orders, FlipPricing.Answers answers,
                            FlipPricing.Lookups lookups, Limits limits, int placedThisSession, Set<String> avoid) {
    }

    private FlipAutopilot() {
    }

    /** The share of a side's daily flow an order at the top of that side can expect. */
    public static double fillShare(BazaarProduct p, boolean buyOrder) {
        return FlipPricing.fillShare(p, buyOrder);
    }

    /** Units a top buy order is expected to fill per day. */
    public static double expectedBuyFillsPerDay(BazaarProduct p) {
        return FlipPricing.expectedBuyOrderFillsPerDay(p);
    }

    /**
     * How many buy orders the session puts up on one product: enough orders of {@code unitsPerOrder} to cover what the
     * product fills in the hold window, at most {@link #MAX_OFFERS_PER_PRODUCT}. Under one fill in the window: none.
     */
    public static int offersFor(double fillsPerDay, double holdDays, long unitsPerOrder) {
        long fillable = (long) Math.floor(Math.max(0, fillsPerDay) * holdDays);
        if (fillable < 1 || unitsPerOrder < 1) {
            return 0;
        }
        long n = (fillable + unitsPerOrder - 1) / unitsPerOrder;
        return (int) Math.max(1, Math.min(MAX_OFFERS_PER_PRODUCT, n));
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
        if (ours == 0) {
            // Nothing of the session's is open, so nothing will change by waiting on it: a wait here was forever.
            long held = 0;
            for (Long v : s.holdings().values()) {
                held += v == null ? 0 : Math.max(0, v);
            }
            FlipPricing.Answers ans = s.answers();
            if (ans.npc() && (held > 0 || s.lookups().npcRemaining() <= 0)) {
                // The NPCs take nothing more today: what it holds cannot be sold there, and nothing new pays.
                return new FlipAction.Stop(STOP_NPC_LIMIT + (held > 0
                        ? String.format(Locale.US, " - the %,d unit(s) it holds stay with you", held) : ""));
            }
            if (held == 0) {
                return new FlipAction.Stop(STOP_NOTHING_PROFITABLE + (ans.npc() ? String.format(Locale.US,
                        " within the %,.0f coins left on today's NPC sell limit", s.lookups().npcRemaining()) : ""));
            }
        }
        return new FlipAction.Wait(ours + " order(s) open, nothing else worth doing");
    }

    /** The stop reason when the NPC sell limit leaves nothing to sell to (the driver adds when it resets). */
    public static final String STOP_NPC_LIMIT = "the daily NPC sell limit is reached";
    /** The stop reason when no product pays and nothing of the session's is open or held. */
    public static final String STOP_NOTHING_PROFITABLE = "nothing on the Bazaar is profitable right now";

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
            // The best bid may be the session's own (another of its orders on this product, relisted first): match it
            // instead of outbidding it, so the session's orders never bid against each other.
            double newPrice = round1(o.buy() ? (ourBuyAt(s, o, best) ? best : best + FlipPricing.TICK)
                    : best - FlipPricing.TICK);
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

    /** Whether another of the session's buy orders on {@code o}'s product bids {@code price}. */
    private static boolean ourBuyAt(Situation s, FlipOrder o, double price) {
        for (FlipOrder other : s.orders()) {
            if (other != o && other.ours() && other.buy() && other.productId() != null
                    && other.productId().equals(o.productId()) && Math.abs(other.price() - price) <= PRICE_EPS) {
                return true;
            }
        }
        return false;
    }

    /** What one unit nets when sold the way the answers say. */
    static double unitSellNet(Situation s, BazaarProduct p) {
        return unitSellNet(s.answers(), s.lookups(), p);
    }

    /** What one unit nets when sold the way the answers say (the driver checks live page prices against it). */
    public static double unitSellNet(FlipPricing.Answers a, FlipPricing.Lookups look, BazaarProduct p) {
        if (a.npc()) {
            Double npc = look.npcPrice(p.productId());
            return npc == null ? 0 : npc;
        }
        return FlipPricing.bazaarSellNet(p, a.instaSell());
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
        Set<String> busy = new HashSet<>(s.avoid() == null ? Set.of() : s.avoid());
        // The session's buy orders per product: a product with some may still get more, up to offersFor (buy orders only).
        Map<String, Integer> ourBuys = new HashMap<>();
        Map<String, Long> ourUnfilled = new HashMap<>();
        Map<String, Double> ourTopBid = new HashMap<>();
        double npcCommitted = 0;
        for (FlipOrder o : s.orders()) {
            if (o.ours()) {
                if (o.buy() && !ans.instaBuy() && o.productId() != null) {
                    ourBuys.merge(o.productId(), 1, Integer::sum);
                    ourUnfilled.merge(o.productId(), o.unfilled(), Long::sum);
                    ourTopBid.merge(o.productId(), o.price(), Math::max);
                } else {
                    busy.add(o.productId());
                }
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
        // Buy orders: a product is only worth an order if a meaningful part of it can fill in the hold window, and one the
        // session already bids on gets another order only while its orders are fewer than offersFor and leave part of
        // the day's fills uncovered.
        FlipPricing.Pick pick = FlipPricing.best(s.products(), ans, look, budget, id -> {
            if (busy.contains(id)) {
                return true;
            }
            BazaarProduct bp = byId.get(id);
            if (ans.instaBuy() || bp == null) {
                return false;
            }
            double fills = expectedBuyFillsPerDay(bp);
            if (fills * lim.maxHoldDays() < 1) {
                return true;
            }
            int have = ourBuys.getOrDefault(id, 0);
            if (have == 0) {
                return false;
            }
            long left = (long) Math.floor(fills * lim.maxHoldDays()) - ourUnfilled.getOrDefault(id, 0L);
            return left < 1 || have >= offersFor(fills, lim.maxHoldDays(), unitsPerOrder(bp, budget, lim));
        });
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
        double fills = expectedBuyFillsPerDay(p);
        long fillable = (long) Math.floor(fills * lim.maxHoldDays());
        int have = ourBuys.getOrDefault(pick.productId(), 0);
        // What the session's orders on it do not already cover of the day's fills.
        units = Math.min(units, fillable - ourUnfilled.getOrDefault(pick.productId(), 0L));
        double price = round1(pick.buy());
        Double ourTop = ourTopBid.get(pick.productId());
        if (ourTop != null && ourTop >= p.topBuyOrderPrice() - PRICE_EPS) {
            price = round1(ourTop); // the best bid is already the session's: match it, never outbid its own order
        }
        // Two orders with the same product, price and amount would read as one in Manage Orders: keep the amounts apart.
        for (FlipOrder o : s.orders()) {
            if (o.ours() && o.buy() && units > 1 && o.productId() != null && o.productId().equals(pick.productId())
                    && Math.abs(o.price() - price) <= PRICE_EPS && o.amount() == units) {
                units--;
            }
        }
        if (units < 1) {
            return null;
        }
        int offers = offersFor(fills, lim.maxHoldDays(), unitsPerOrder(p, budget, lim));
        return new FlipAction.PlaceBuyOrder(pick.productId(), pick.name(), price, units,
                String.format(Locale.US, "best flip: +%.1f each (%.2f%%), ~%,d fill a day, ~%,.0f expected today, offer %d of %d",
                        pick.profitEach(), pick.profitPercent(), (long) fills, pick.expectedProfit(), have + 1,
                        Math.max(offers, have + 1)));
    }

    /** Units one buy order on {@code p} may take: the purse share at the bid, and Hypixel's per-order cap. */
    static long unitsPerOrder(BazaarProduct p, double budget, Limits lim) {
        double bid = FlipPricing.buyPrice(p, false);
        if (bid <= 0) {
            return 0;
        }
        return Math.min(lim.maxOrderUnits(), (long) Math.floor(budget / bid));
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
