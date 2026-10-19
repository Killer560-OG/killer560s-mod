import com.killer560.hub.auction.BazaarOrderLevel;
import com.killer560.hub.auction.BazaarProduct;
import com.killer560.hub.bazaarflip.autopilot.FlipAction;
import com.killer560.hub.bazaarflip.autopilot.FlipAutopilot;
import com.killer560.hub.bazaarflip.autopilot.FlipOrder;
import com.killer560.hub.bazaarflip.autopilot.FlipPricing;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Offline simulator for the Bazaar Flipper autopilot ({@code FlipAutopilot}). Runs the real decision core, compiled
 * straight from {@code src/main/java} with no Minecraft on the class path, against a market and reports expected profit,
 * fills, capital tied up in open orders and stuck orders.
 *
 * <h2>Run (from the repo root)</h2>
 * <pre>
 *   javac -d build/flipsim tools/bazaarflip-sim/FlipSim.java src/main/java/com/killer560/hub/bazaarflip/autopilot/*.java \
 *         src/main/java/com/killer560/hub/auction/BazaarProduct.java src/main/java/com/killer560/hub/auction/BazaarOrderLevel.java
 *   java -cp build/flipsim FlipSim                       # synthetic order-book walks, every answer combination
 *   java -cp build/flipsim FlipSim snapshots.json         # recorded snapshots
 * </pre>
 *
 * <h2>Snapshot file</h2>
 * Either one raw response of {@code https://api.hypixel.net/v2/skyblock/bazaar}, a JSON array of them, or
 * {@code {"snapshots": [ ... ], "npcPrices": {"ENCHANTED_DIAMOND": 6400, ...}}}. Snapshots are replayed in order, each
 * step lasting the gap between their {@code lastUpdated} values (5 minutes when absent). NPC prices are optional; without
 * them only the Bazaar-mode answers are simulated. Record one by saving the API response every few minutes.
 *
 * <h2>Market model</h2>
 * A buy order at or above the market's best buy order fills from instant sells as a Poisson process with mean
 * {@code sellMovingWeek / 7} a day times a "true" share {@code 1 / (1 + orders / 15)} - deliberately harsher than the
 * autopilot's own estimate (orders / 25) so the result is not the model agreeing with itself. Sell offers likewise from
 * {@code buyMovingWeek}. An order below the best is outbid and does not fill. Synthetic products random-walk their mid
 * price (0.3% sigma per 5 minutes) with a mean-reverting spread.
 */
public final class FlipSim {

    static final double TRUE_COMPETITION = 15.0;
    static final double NPC_DAILY = 500_000_000.0;
    static final int SLOTS = 36;
    static final int ACTIONS_PER_STEP = 6;

    // ---- market ----------------------------------------------------------------------------------------------------

    interface Market {
        /** Advances to the next step; false when the market has no more steps. */
        boolean next();

        List<BazaarProduct> products();

        double stepMinutes();

        Double npcPrice(String id);
    }

    static final class Synthetic implements Market {
        final Random rnd;
        final int steps;
        int step = -1;
        final String[] ids;
        final double[] mid;
        final double[] spread;
        final double[] spreadMean;
        final long[] weekBuy;
        final long[] weekSell;
        final int[] offers;
        final int[] bids;
        final Double[] npc;
        List<BazaarProduct> current = List.of();

        Synthetic(long seed, int products, int steps) {
            this.rnd = new Random(seed);
            this.steps = steps;
            ids = new String[products];
            mid = new double[products];
            spread = new double[products];
            spreadMean = new double[products];
            weekBuy = new long[products];
            weekSell = new long[products];
            offers = new int[products];
            bids = new int[products];
            npc = new Double[products];
            for (int i = 0; i < products; i++) {
                ids[i] = "SYN_" + i;
                mid[i] = Math.exp(Math.log(5) + rnd.nextDouble() * (Math.log(200_000) - Math.log(5)));
                spreadMean[i] = 0.004 + rnd.nextDouble() * 0.05;
                spread[i] = spreadMean[i];
                double vol = Math.exp(Math.log(500) + rnd.nextDouble() * (Math.log(20_000_000) - Math.log(500)));
                weekBuy[i] = (long) (vol * (0.7 + rnd.nextDouble() * 0.6));
                weekSell[i] = (long) (vol * (0.7 + rnd.nextDouble() * 0.6));
                offers[i] = 2 + rnd.nextInt(400);
                bids[i] = 2 + rnd.nextInt(400);
                // About a fifth have an NPC price near the mid; a few of those sit above the cheapest offer.
                npc[i] = rnd.nextDouble() < 0.2 ? Math.round(mid[i] * (0.97 + rnd.nextDouble() * 0.045) * 10) / 10.0 : null;
            }
        }

        @Override
        public boolean next() {
            if (++step >= steps) {
                return false;
            }
            List<BazaarProduct> out = new ArrayList<>();
            for (int i = 0; i < ids.length; i++) {
                if (step > 0) {
                    mid[i] *= Math.exp(rnd.nextGaussian() * 0.003);
                    spread[i] = Math.max(0.001, spread[i] + 0.2 * (spreadMean[i] - spread[i]) + rnd.nextGaussian() * spreadMean[i] * 0.15);
                }
                double bid = round1(mid[i] * (1 - spread[i] / 2));
                double ask = Math.max(bid + 0.1, round1(mid[i] * (1 + spread[i] / 2)));
                long depth = Math.max(10, weekSell[i] / 7 / 24);
                List<BazaarOrderLevel> bidsBook = new ArrayList<>();
                List<BazaarOrderLevel> asksBook = new ArrayList<>();
                for (int l = 0; l < 6; l++) {
                    bidsBook.add(new BazaarOrderLevel(round1(bid * (1 - 0.002 * l)), depth * (l + 1), 1 + l));
                    asksBook.add(new BazaarOrderLevel(round1(ask * (1 + 0.002 * l)), depth * (l + 1), 1 + l));
                }
                out.add(new BazaarProduct(ids[i], "Synthetic " + i, ask, bid, depth * 21, depth * 21, weekBuy[i], weekSell[i],
                        offers[i], bids[i], "Oddities", "Synthetic", -1, null, false, bidsBook, asksBook));
            }
            current = out;
            return true;
        }

        @Override
        public List<BazaarProduct> products() {
            return current;
        }

        @Override
        public double stepMinutes() {
            return 5;
        }

        @Override
        public Double npcPrice(String id) {
            int i = Integer.parseInt(id.substring(4));
            return npc[i];
        }
    }

    static final class Recorded implements Market {
        final List<List<BazaarProduct>> snaps;
        final List<Double> minutes;
        final Map<String, Double> npc;
        int step = -1;

        Recorded(List<List<BazaarProduct>> snaps, List<Double> minutes, Map<String, Double> npc) {
            this.snaps = snaps;
            this.minutes = minutes;
            this.npc = npc;
        }

        @Override
        public boolean next() {
            return ++step < snaps.size();
        }

        @Override
        public List<BazaarProduct> products() {
            return snaps.get(Math.min(step, snaps.size() - 1)); // the last one values what is left at the end
        }

        @Override
        public double stepMinutes() {
            return minutes.get(Math.min(step, minutes.size() - 1));
        }

        @Override
        public Double npcPrice(String id) {
            return npc.get(id);
        }
    }

    // ---- the simulated player ----------------------------------------------------------------------------------------

    static final class SimOrder {
        boolean buy;
        String id;
        String name;
        long amount;
        long filled;
        long claimed;
        double price;
        double ageMin;
        int relists;
        double openMinutes;
    }

    static final class Result {
        double startWorth;
        double endWorth;
        long buyFills;
        long sellFills;
        long instaBought;
        long instaSold;
        long npcSold;
        int placed;
        int relists;
        int cancels;
        int collects;
        double capitalSum;
        double capitalMax;
        int capitalSamples;
        int stuckAtEnd;
        int everStuck;
        String stopped = "";
        Map<String, Integer> actions = new LinkedHashMap<>();
    }

    static Result run(Market m, FlipPricing.Answers ans, double purse0, long seed) {
        Random rnd = new Random(seed);
        double purse = purse0;
        double npcSold = 0;
        Map<String, Long> holdings = new HashMap<>();
        Map<String, Double> cost = new HashMap<>();
        List<SimOrder> orders = new ArrayList<>();
        Result r = new Result();
        r.startWorth = purse0;
        FlipAutopilot.Limits lim = FlipAutopilot.Limits.defaults().withReserve(purse0 * 0.05);
        int placed = 0;
        double elapsed = 0;
        java.util.Set<SimOrder> everStuck = new java.util.HashSet<>();
        while (m.next()) {
            double dt = m.stepMinutes();
            elapsed += dt;
            Map<String, BazaarProduct> byId = new HashMap<>();
            for (BazaarProduct p : m.products()) {
                byId.put(p.productId(), p);
            }
            // Fills for the step.
            for (SimOrder o : orders) {
                o.ageMin += dt;
                o.openMinutes += dt;
                BazaarProduct p = byId.get(o.id);
                if (p == null || o.filled >= o.amount) {
                    continue;
                }
                boolean top = o.buy ? o.price >= p.topBuyOrderPrice() - 0.05 : o.price <= p.topSellOfferPrice() + 0.05;
                if (!top) {
                    continue;
                }
                double perDay = (o.buy ? p.sellMovingWeek() : p.buyMovingWeek()) / 7.0;
                int comp = o.buy ? p.sellOrders() : p.buyOrders();
                double mean = perDay * (dt / 1440.0) / (1 + comp / TRUE_COMPETITION);
                long got = Math.min(o.amount - o.filled, poisson(rnd, mean));
                o.filled += got;
                if (o.buy) {
                    r.buyFills += got;
                } else {
                    r.sellFills += got;
                }
            }
            for (SimOrder o : orders) {
                if (o.openMinutes > 12 * 60 && o.filled < o.amount * 0.1) {
                    everStuck.add(o);
                }
            }
            String stop = null;
            // Book levels the session's own instant trades took this step (the snapshot it decides from does not show it).
            Map<String, Long> askUsed = new HashMap<>();
            Map<String, Long> bidUsed = new HashMap<>();
            for (int k = 0; k < ACTIONS_PER_STEP && stop == null; k++) {
                List<FlipOrder> view = new ArrayList<>();
                for (SimOrder o : orders) {
                    long claimItems = o.buy ? o.filled - o.claimed : -1;
                    double claimCoins = o.buy ? -1 : (o.filled - o.claimed) * o.price * (1 - FlipPricing.SELL_TAX);
                    view.add(new FlipOrder(o.buy, o.id, o.name, o.amount, o.filled, o.price, claimItems, claimCoins,
                            o.filled > o.claimed, o.ageMin > 7 * 1440, (long) (o.ageMin * 60_000), o.relists, true));
                }
                final Map<String, Long> hold = holdings;
                final double npcLeft = Math.max(0, NPC_DAILY - npcSold);
                FlipPricing.Lookups look = new FlipPricing.Lookups() {
                    @Override
                    public Double npcPrice(String productId) {
                        return m.npcPrice(productId);
                    }

                    @Override
                    public int stackSize(String productId) {
                        return 64;
                    }

                    @Override
                    public long room(String productId, String displayName, int stackSize) {
                        int used = 0;
                        long partial = 0;
                        for (Map.Entry<String, Long> e : hold.entrySet()) {
                            long u = e.getValue();
                            used += (int) Math.ceil(u / 64.0);
                            if (e.getKey().equals(productId) && u % 64 != 0) {
                                partial = 64 - u % 64;
                            }
                        }
                        return Math.max(0, SLOTS - used) * 64L + partial;
                    }

                    @Override
                    public double npcRemaining() {
                        return npcLeft;
                    }
                };
                FlipAction a = FlipAutopilot.decide(new FlipAutopilot.Situation(m.products(), purse, holdings, cost, view,
                        ans, look, lim, placed, java.util.Set.of()));
                r.actions.merge(a.getClass().getSimpleName(), 1, Integer::sum);
                if (a instanceof FlipAction.Wait) {
                    break;
                }
                if (a instanceof FlipAction.Stop s) {
                    stop = s.why();
                    break;
                }
                if (a instanceof FlipAction.InstaBuy b) {
                    BazaarProduct p = byId.get(b.productId());
                    long room = look.room(b.productId(), b.name(), 64);
                    long n = Math.min(b.amount(), room);
                    long used = askUsed.getOrDefault(b.productId(), 0L);
                    double spent = walk(p.topSellOffers(), used, n);
                    if (n < 1 || spent > purse) {
                        break;
                    }
                    // The driver reads the live Instant Buy page and refuses a price that no longer beats the sale
                    // (the NPC price, as BazaarFlipFeature.priceOk does); the live page here is the depleted book.
                    Double npcEach = m.npcPrice(b.productId());
                    if (ans.npc() && npcEach != null && spent / n >= npcEach) {
                        r.actions.merge("InstaBuyRefusedLive", 1, Integer::sum);
                        break;
                    }
                    askUsed.merge(b.productId(), n, Long::sum);
                    purse -= spent;
                    addHolding(holdings, cost, b.productId(), n, spent / n);
                    r.instaBought += n;
                    placed++;
                } else if (a instanceof FlipAction.InstaSell s) {
                    BazaarProduct p = byId.get(s.productId());
                    long n = holdings.getOrDefault(s.productId(), 0L);
                    purse += walk(p.topBuyOrders(), bidUsed.getOrDefault(s.productId(), 0L), n) * (1 - FlipPricing.SELL_TAX);
                    bidUsed.merge(s.productId(), n, Long::sum);
                    holdings.remove(s.productId());
                    r.instaSold += n;
                } else if (a instanceof FlipAction.SellToNpc s) {
                    long n = Math.min(s.amount(), holdings.getOrDefault(s.productId(), 0L));
                    purse += n * s.npcEach();
                    npcSold += n * s.npcEach();
                    holdings.merge(s.productId(), -n, Long::sum);
                    if (holdings.get(s.productId()) <= 0) {
                        holdings.remove(s.productId());
                    }
                    r.npcSold += n;
                } else if (a instanceof FlipAction.PlaceBuyOrder b) {
                    if (b.price() * b.amount() > purse) {
                        break;
                    }
                    purse -= b.price() * b.amount();
                    orders.add(order(true, b.productId(), b.name(), b.amount(), b.price(), 0));
                    placed++;
                } else if (a instanceof FlipAction.PlaceSellOffer s) {
                    long n = holdings.getOrDefault(s.productId(), 0L);
                    holdings.remove(s.productId());
                    orders.add(order(false, s.productId(), s.name(), n, s.price(), 0));
                    placed++;
                } else if (a instanceof FlipAction.Collect c) {
                    SimOrder o = find(orders, c.order());
                    long n = o.filled - o.claimed;
                    if (o.buy) {
                        addHolding(holdings, cost, o.id, n, o.price);
                    } else {
                        purse += n * o.price * (1 - FlipPricing.SELL_TAX);
                    }
                    o.claimed = o.filled;
                    if (o.filled >= o.amount) {
                        orders.remove(o);
                    }
                    r.collects++;
                } else if (a instanceof FlipAction.Relist rl) {
                    SimOrder o = find(orders, rl.order());
                    purse += cancel(o, holdings, cost);
                    orders.remove(o);
                    long left = o.amount - o.filled;
                    if (o.buy) {
                        if (rl.newPrice() * left > purse) {
                            r.cancels++;
                            continue;
                        }
                        purse -= rl.newPrice() * left;
                    } else {
                        holdings.merge(o.id, -left, Long::sum);
                        if (holdings.get(o.id) <= 0) {
                            holdings.remove(o.id);
                        }
                    }
                    orders.add(order(o.buy, o.id, o.name, left, rl.newPrice(), o.relists + 1));
                    r.relists++;
                } else if (a instanceof FlipAction.Cancel c) {
                    SimOrder o = find(orders, c.order());
                    purse += cancel(o, holdings, cost);
                    orders.remove(o);
                    r.cancels++;
                }
            }
            double tied = 0;
            for (SimOrder o : orders) {
                tied += o.buy ? (o.amount - o.filled) * o.price : (o.amount - o.filled) * o.price;
            }
            r.capitalSum += tied;
            r.capitalMax = Math.max(r.capitalMax, tied);
            r.capitalSamples++;
            if (stop != null) {
                r.stopped = stop + " (after " + Math.round(elapsed) + " min)";
                break;
            }
        }
        // Worth at the end: purse, coins and items still on the Bazaar, stock valued at Insta Sell.
        Map<String, BazaarProduct> byId = new HashMap<>();
        for (BazaarProduct p : m.products()) {
            byId.put(p.productId(), p);
        }
        double worth = purse;
        for (SimOrder o : orders) {
            BazaarProduct p = byId.get(o.id);
            double instaNet = p == null ? 0 : p.topBuyOrderPrice() * (1 - FlipPricing.SELL_TAX);
            if (o.buy) {
                worth += (o.amount - o.filled) * o.price + (o.filled - o.claimed) * instaNet;
            } else {
                worth += (o.filled - o.claimed) * o.price * (1 - FlipPricing.SELL_TAX) + (o.amount - o.filled) * instaNet;
            }
            if (o.openMinutes > 12 * 60 && o.filled < o.amount * 0.1) {
                r.stuckAtEnd++;
            }
        }
        for (Map.Entry<String, Long> e : holdings.entrySet()) {
            BazaarProduct p = byId.get(e.getKey());
            worth += e.getValue() * (p == null ? 0 : p.topBuyOrderPrice() * (1 - FlipPricing.SELL_TAX));
        }
        r.endWorth = worth;
        r.placed = placed;
        r.everStuck = everStuck.size();
        return r;
    }

    static SimOrder order(boolean buy, String id, String name, long amount, double price, int relists) {
        SimOrder o = new SimOrder();
        o.buy = buy;
        o.id = id;
        o.name = name;
        o.amount = amount;
        o.price = price;
        o.relists = relists;
        return o;
    }

    static SimOrder find(List<SimOrder> orders, FlipOrder f) {
        for (SimOrder o : orders) {
            if (o.buy == f.buy() && o.id.equals(f.productId()) && o.price == f.price() && o.amount == f.amount()) {
                return o;
            }
        }
        throw new IllegalStateException("autopilot named an order that does not exist: " + f);
    }

    /** Cancels an order: the unfilled coins of a buy order come back, a sell offer's unsold items go back to holdings.
     *  Anything filled and unclaimed is claimed in the same step (Hypixel claims on cancel). Returns coins refunded. */
    static double cancel(SimOrder o, Map<String, Long> holdings, Map<String, Double> cost) {
        long unclaimed = o.filled - o.claimed;
        long left = o.amount - o.filled;
        if (o.buy) {
            if (unclaimed > 0) {
                addHolding(holdings, cost, o.id, unclaimed, o.price);
            }
            return left * o.price;
        }
        holdings.merge(o.id, left, Long::sum);
        return unclaimed * o.price * (1 - FlipPricing.SELL_TAX);
    }

    static void addHolding(Map<String, Long> holdings, Map<String, Double> cost, String id, long n, double each) {
        long had = holdings.getOrDefault(id, 0L);
        double oldCost = cost.getOrDefault(id, each);
        holdings.put(id, had + n);
        cost.put(id, (had * oldCost + n * each) / (had + n));
    }

    /** Coins to trade {@code n} units through a book's levels, best first (the last level repeats if the book runs out). */
    static double walk(List<BazaarOrderLevel> book, long n) {
        return walk(book, 0, n);
    }

    /** The same, after the first {@code used} units of the book were already taken this step. */
    static double walk(List<BazaarOrderLevel> book, long used, long n) {
        double total = 0;
        long left = n;
        long skip = used;
        for (BazaarOrderLevel l : book) {
            long avail = l.amount();
            if (skip > 0) {
                long s = Math.min(skip, avail);
                skip -= s;
                avail -= s;
            }
            long take = Math.min(left, avail);
            total += take * l.pricePerUnit();
            left -= take;
            if (left == 0) {
                return total;
            }
        }
        return total + left * (book.isEmpty() ? 0 : book.get(book.size() - 1).pricePerUnit());
    }

    static long poisson(Random rnd, double mean) {
        if (mean <= 0) {
            return 0;
        }
        if (mean > 50) {
            return Math.max(0, Math.round(mean + rnd.nextGaussian() * Math.sqrt(mean)));
        }
        double l = Math.exp(-mean);
        long k = 0;
        double p = 1;
        do {
            k++;
            p *= rnd.nextDouble();
        } while (p > l);
        return k - 1;
    }

    static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    // ---- snapshot file -------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Recorded load(Path file) throws Exception {
        Object root = new Json(Files.readString(file, StandardCharsets.UTF_8)).value();
        List<Object> raw = new ArrayList<>();
        Map<String, Double> npc = new HashMap<>();
        if (root instanceof List<?> l) {
            raw.addAll(l);
        } else if (root instanceof Map<?, ?> mp && mp.containsKey("snapshots")) {
            raw.addAll((List<Object>) mp.get("snapshots"));
            Object n = mp.get("npcPrices");
            if (n instanceof Map<?, ?> nm) {
                nm.forEach((k, v) -> npc.put((String) k, ((Number) v).doubleValue()));
            }
        } else {
            raw.add(root);
        }
        List<List<BazaarProduct>> snaps = new ArrayList<>();
        List<Double> minutes = new ArrayList<>();
        Double lastAt = null;
        for (Object o : raw) {
            Map<String, Object> snap = (Map<String, Object>) o;
            Map<String, Object> products = (Map<String, Object>) snap.get("products");
            List<BazaarProduct> list = new ArrayList<>();
            for (Map.Entry<String, Object> e : products.entrySet()) {
                Map<String, Object> p = (Map<String, Object>) e.getValue();
                Map<String, Object> q = (Map<String, Object>) p.get("quick_status");
                list.add(new BazaarProduct(e.getKey(), e.getKey(), num(q, "buyPrice"), num(q, "sellPrice"),
                        (long) num(q, "buyVolume"), (long) num(q, "sellVolume"), (long) num(q, "buyMovingWeek"),
                        (long) num(q, "sellMovingWeek"), (int) num(q, "buyOrders"), (int) num(q, "sellOrders"), "", "", -1,
                        null, false, levels(p.get("sell_summary")), levels(p.get("buy_summary"))));
            }
            snaps.add(list);
            Object at = snap.get("lastUpdated");
            double mins = 5;
            if (at instanceof Number n) {
                if (lastAt != null && n.doubleValue() > lastAt) {
                    mins = (n.doubleValue() - lastAt) / 60_000.0;
                }
                lastAt = n.doubleValue();
            }
            minutes.add(mins);
        }
        return new Recorded(snaps, minutes, npc);
    }

    @SuppressWarnings("unchecked")
    static List<BazaarOrderLevel> levels(Object o) {
        List<BazaarOrderLevel> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                Map<String, Object> m = (Map<String, Object>) x;
                out.add(new BazaarOrderLevel(num(m, "pricePerUnit"), (long) num(m, "amount"), (int) num(m, "orders")));
            }
        }
        return out;
    }

    static double num(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    /** Just enough JSON for the Bazaar response: objects, arrays, strings, numbers, true/false/null. */
    static final class Json {
        final String s;
        int i;

        Json(String s) {
            this.s = s;
        }

        Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    ws();
                    String k = str();
                    ws();
                    i++; // :
                    m.put(k, value());
                    ws();
                    if (s.charAt(i++) == '}') {
                        return m;
                    }
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    l.add(value());
                    ws();
                    if (s.charAt(i++) == ']') {
                        return l;
                    }
                }
            }
            if (c == '"') {
                return str();
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            return Double.parseDouble(s.substring(st, i));
        }

        String str() {
            StringBuilder b = new StringBuilder();
            i++; // opening quote
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> b.append('\n');
                        case 't' -> b.append('\t');
                        case 'u' -> {
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }

    // ---- main -------------------------------------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        double purse = 10_000_000;
        FlipPricing.Answers[] combos = {
                new FlipPricing.Answers(false, false, true, true),
                new FlipPricing.Answers(false, false, true, false),
                new FlipPricing.Answers(false, false, false, true),
                new FlipPricing.Answers(false, false, false, false),
                new FlipPricing.Answers(false, true, false, false),
                new FlipPricing.Answers(true, false, true, true),
                new FlipPricing.Answers(true, false, false, true),
        };
        int seeds = 20;
        if (args.length > 0) {
            Recorded probe = load(Path.of(args[0]));
            System.out.printf(Locale.US, "Recorded: %d snapshot(s), %d NPC price(s)%n", probe.snaps.size(), probe.npc.size());
            for (FlipPricing.Answers a : combos) {
                if (a.npc() && probe.npc.isEmpty()) {
                    continue;
                }
                Result r = run(load(Path.of(args[0])), a, purse, 1);
                print(a, List.of(r));
            }
            return;
        }
        System.out.printf(Locale.US, "Synthetic: 60 products, 24 h in 5-minute steps, purse %,.0f, reserve 5%%, %d seeds each%n",
                purse, seeds);
        for (FlipPricing.Answers a : combos) {
            List<Result> rs = new ArrayList<>();
            for (int s = 0; s < seeds; s++) {
                rs.add(run(new Synthetic(1000 + s, 60, 288), a, purse, 7 + s));
            }
            print(a, rs);
        }
    }

    static void print(FlipPricing.Answers a, List<Result> rs) {
        int n = rs.size();
        double profit = 0;
        double worst = Double.MAX_VALUE;
        double best = -Double.MAX_VALUE;
        double buyFills = 0, sellFills = 0, instaB = 0, instaS = 0, npc = 0, placed = 0, rel = 0, can = 0, col = 0;
        double capAvg = 0, capMax = 0, stuck = 0, ever = 0;
        int stopped = 0;
        Map<String, Integer> acts = new LinkedHashMap<>();
        for (Result r : rs) {
            double p = r.endWorth - r.startWorth;
            profit += p;
            worst = Math.min(worst, p);
            best = Math.max(best, p);
            buyFills += r.buyFills;
            sellFills += r.sellFills;
            instaB += r.instaBought;
            instaS += r.instaSold;
            npc += r.npcSold;
            placed += r.placed;
            rel += r.relists;
            can += r.cancels;
            col += r.collects;
            capAvg += r.capitalSamples == 0 ? 0 : r.capitalSum / r.capitalSamples;
            capMax = Math.max(capMax, r.capitalMax);
            stuck += r.stuckAtEnd;
            ever += r.everStuck;
            if (!r.stopped.isEmpty()) {
                stopped++;
            }
            r.actions.forEach((k, v) -> acts.merge(k, v, Integer::sum));
        }
        String label = (a.npc() ? "NPC" : "Bazaar") + " / " + (a.percentGoal() ? "percent" : "total") + " / "
                + (a.instaBuy() ? "Insta Buy" : "Buy Order") + (a.npc() ? "" : " / " + (a.instaSell() ? "Insta Sell" : "Sell Offer"));
        System.out.printf(Locale.US, "%n== %s ==%n", label);
        System.out.printf(Locale.US, "  profit/run avg %,.0f (worst %,.0f, best %,.0f)%n", profit / n, worst, best);
        System.out.printf(Locale.US, "  fills/run: buy orders %,.0f, sell offers %,.0f; insta bought %,.0f, insta sold %,.0f, NPC sold %,.0f%n",
                buyFills / n, sellFills / n, instaB / n, instaS / n, npc / n);
        System.out.printf(Locale.US, "  placed/run %.1f, relists %.1f, cancels %.1f, collects %.1f%n", placed / n, rel / n, can / n, col / n);
        System.out.printf(Locale.US, "  capital in open orders: avg %,.0f, max %,.0f%n", capAvg / n, capMax);
        System.out.printf(Locale.US, "  stuck orders (open >12 h, <10%% filled): %.2f at end, %.2f ever, per run; runs stopped early %d/%d%n",
                stuck / n, ever / n, stopped, n);
        if (n == 1 && !rs.get(0).stopped.isEmpty()) {
            System.out.println("  stopped: " + rs.get(0).stopped);
        }
        System.out.println("  decisions: " + acts);
    }
}
