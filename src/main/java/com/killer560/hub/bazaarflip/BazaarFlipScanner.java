package com.killer560.hub.bazaarflip;

import com.killer560.hub.auction.BazaarApi;
import com.killer560.hub.auction.BazaarOrderLevel;
import com.killer560.hub.itembrowser.SkyblockItemEntry;
import com.killer560.hub.itembrowser.SkyblockItemRepository;
import com.killer560.hub.itembrowser.SkyblockItemStackFactory;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import com.killer560.hub.util.ModLog;

/**
 * The pure-data half of the Bazaar-to-NPC Flipper: joins the live Bazaar order books against
 * {@code npc_sell_price} and costs out what one buy-and-sell cycle of each product would actually do. No
 * clicks, no screens, no automation - {@link BazaarFlipFeature} is the half that acts on this.
 *
 * <h2>The two endpoints and the trap between them</h2>
 * Instant-buy prices come from {@link BazaarApi#fetchInstantBuyBooksAsync()} ({@code buy_summary}, which is
 * the book an instant BUY consumes despite the name - read that method's doc, getting it backwards produces
 * fake billion-coin flips). NPC prices come from a completely different endpoint,
 * {@code /v2/resources/skyblock/items}, already parsed by {@link SkyblockItemRepository}, whose entries
 * carry {@code npcSellPrice}. Neither is re-parsed here.
 *
 * <h2>Why the book is walked instead of read</h2>
 * {@code quick_status.buyPrice} is a weighted average of the top orders, not the top price - it matched the
 * exact top on 628 of 1833 products with a book (measured 2026-09-29). And the price walks UP as a purchase
 * eats through levels, so ranking on any single number systematically overstates the result. {@link #size}
 * therefore consumes levels cheapest-first and stops at the first of three real limits: the level price
 * reaching {@code npc_sell_price} (past which a unit loses money), the inventory filling, or the purse
 * running out.
 *
 * <h2>Scale check</h2>
 * Measured live 2026-09-29, and the numbers to compare a suspicious result against: of 819 Bazaar products
 * that have an {@code npc_sell_price}, only 46 are profitable at all, and the worthwhile margins run
 * 0.4%-1.5%. The best full inventory (2,304 units) was {@code ENCHANTED_DIAMOND_BLOCK}: ~203,617 coins each
 * against an NPC price of 204,800, so ~469 million coins of capital for ~2.7 million profit. If this scanner
 * ever reports margins far above that band, the order book has been read from the wrong side.
 */
public final class BazaarFlipScanner {

    private static final Logger LOGGER = ModLog.get("killer560smod-bazaarflip");

    /** Real player inventory slots a Skyblock player can fill with one stackable material: 27 main + 9
     *  hotbar. Deliberately not "36 minus whatever he is holding" - see {@link #size}. */
    public static final int INVENTORY_SLOTS = 36;

    /**
     * Fees on one cycle, checked 2026-10-16 on hypixelskyblock.minecraft.wiki. The Bazaar's tax (1.25%, 1.125% / 1%
     * with the Community Shop's Bazaar Flipper upgrade) is taken on SALES to the Bazaar - instant sells and claimed
     * sell offers - and not on purchases: the Bazaar page describes no tax on instant buys or buy orders, and the
     * "[Bazaar] Bought 16x Ender Pearl for 112.0 coins!" line he got charges exactly the listed price. Shops (and the
     * /trades menu, which needs an active Booster Cookie to sell) "accept all sellable items at the same price" with no
     * fee. A Booster Cookie changes no rate. So both are 0 - written out so the estimate states what it assumes, and
     * so a future fee is one constant. What IS limited is the NPC side: {@link BazaarFlipNpcLimit}.
     */
    public static final double INSTANT_BUY_TAX = 0.0;
    public static final double NPC_SELL_FEE = 0.0;

    /**
     * Hypixel's instant-buy QUOTE margin. hypixelskyblock.minecraft.wiki, Bazaar (read 2026-10-17): "The Bazaar will quote a
     * price 4% higher than the cheapest sell offer ... If the order goes through at a price below the additional 4%, the
     * extra Coins are refunded." Measured the same day: the API's ENCHANTED_PUFFERFISH book started at 2,339.5 (snapshot 7 s
     * old) while his Instant Buy page said 2,433.1 each - 2,339.6 x 1.04. So the Instant Buy page's prices are the book
     * plus 4% (not a cost: the 4% comes back), and the purse must cover the quote before Hypixel will run the buy. Until
     * this was known the flipper compared the quote with the NPC price and refused every thin flip ("Enchanted Pufferfish
     * ... 2,433.1 each is no longer below the NPC's 2,400", killer560, 2026-10-17).
     */
    public static final double INSTANT_BUY_QUOTE_MARGIN = 0.04;

    /** A snapshot older than this (by the API's own {@code lastUpdated}) is refetched once, then refused. */
    public static final long MAX_BOOK_AGE_MS = 60_000L;

    /** Outcome of one scan. {@code error} is null on success. */
    public record ScanResult(
            long atMs,
            int productsWithNpcPrice,
            int profitableCount,
            /** Every candidate with a net profit above 0, best first by the requested ranking mode. */
            List<BazaarFlipCandidate> ranked,
            String error
    ) {
        public boolean ok() {
            return error == null;
        }
    }

    private static volatile ScanResult last = null;
    private static final AtomicBoolean scanning = new AtomicBoolean(false);

    private BazaarFlipScanner() {
    }

    /**
     * Testkit hook: when set, every scan answers with these candidates (best first) instead of fetching (the harness
     * runs offline). Memory only - never saved - and refused unless the JVM is a harness's (the testkit always passes
     * {@code -Dkiller560.net.offline=true} and {@code -Dkiller560.test.noExternalOpen=true}; see docs/TESTING-HOOKS.md),
     * so a real session can never be answered by a test's prices (killer560, 2026-10-17: "it is pulling data from tests
     * earlier").
     */
    private static volatile List<BazaarFlipCandidate> testCandidates;

    /** True only in a harness JVM (the testkit's properties). */
    public static boolean testHooksAllowed() {
        return Boolean.getBoolean("killer560.net.offline") || Boolean.getBoolean("killer560.test.noExternalOpen");
    }

    /** Testkit only: make scans return one fixed flip, {@code units} at {@code unitCost} each, sold at {@code npc}. */
    public static void setTestCandidateForTest(String productId, String displayName, int stackSize, int units,
                                               double unitCost, double npc) {
        testCandidates = null;
        addTestCandidateForTest(productId, displayName, stackSize, units, unitCost, npc);
    }

    /** Testkit only: one more candidate, ranked after those already set. */
    public static void addTestCandidateForTest(String productId, String displayName, int stackSize, int units,
                                               double unitCost, double npc) {
        if (!testHooksAllowed()) {
            LOGGER.warn("[BazaarFlip] Ignored a test candidate outside a test harness");
            return;
        }
        double spend = units * unitCost;
        double revenue = units * npc;
        List<BazaarFlipCandidate> list = new ArrayList<>(testCandidates == null ? List.of() : testCandidates);
        list.add(new BazaarFlipCandidate(productId, displayName, stackSize, units, spend, npc, revenue,
                revenue - spend, (revenue - spend) / spend * 100.0, false));
        testCandidates = List.copyOf(list);
    }

    public static void clearTestCandidateForTest() {
        testCandidates = null;
    }

    /** Whether a test candidate is set (the testkit checks a real-looking JVM refuses one). */
    public static boolean hasTestCandidateForTest() {
        return testCandidates != null;
    }

    public static ScanResult getLast() {
        return last;
    }

    /** Drops the cached scan, so a stopped-and-restarted session never acts on a stale book. */
    public static void clear() {
        last = null;
    }

    /**
     * One fetch-and-cost pass. The HTTP request runs off the client thread; the costing is plain arithmetic
     * over about 819 candidates and runs on whatever thread the future completes on.
     *
     * <p>Deliberately NOT hooked to any timer. {@link BazaarApi#ensureAutoStarted()}'s five-minute loop is
     * not used and is not started by this path, because killer560 asked for exactly the opposite: "only have
     * the rescan go while the bot is running, so before it starts it would scan in the bazaar then every
     * minute during it". {@link BazaarFlipFeature} is the only caller and only calls it while running.
     *
     * @param purse           coins available to spend - this is what makes the result the best flip he can
     *                        actually AFFORD rather than the best flip in the game
     * @param mode            how to order the survivors
     * @param npcCoinsLeft    coins the NPCs will still pay today ({@link BazaarFlipNpcLimit#remaining()}): no candidate
     *                        is sized past what can be sold
     */
    public static CompletableFuture<ScanResult> scanAsync(double purse, BazaarFlipConfig.RankingMode mode,
                                                          double npcCoinsLeft) {
        List<BazaarFlipCandidate> fixed = testHooksAllowed() ? testCandidates : null;
        if (fixed != null) {
            List<BazaarFlipCandidate> list = new ArrayList<>();
            for (BazaarFlipCandidate c : fixed) {
                int units = (int) Math.min(c.units(), Math.floor(npcCoinsLeft / c.npcSellPrice()));
                if (units <= 0) {
                    continue;
                }
                double unit = c.averageUnitCost();
                double spend = units * unit * (1 + INSTANT_BUY_TAX);
                double revenue = units * c.npcSellPrice() * (1 - NPC_SELL_FEE);
                list.add(new BazaarFlipCandidate(c.productId(), c.displayName(), c.stackSize(), units, spend,
                        c.npcSellPrice(), revenue, revenue - spend, (revenue - spend) / spend * 100.0, false));
            }
            ScanResult r = new ScanResult(System.currentTimeMillis(), 1, list.size(), List.copyOf(list), null);
            last = r;
            return CompletableFuture.completedFuture(r);
        }
        if (!scanning.compareAndSet(false, true)) {
            // Never hand back an older scan as this one's answer: the caller waits for a fresh scan.
            return CompletableFuture.completedFuture(
                    new ScanResult(System.currentTimeMillis(), 0, 0, List.of(), "a scan is already running"));
        }
        return CompletableFuture.supplyAsync(() -> fetchFresh(BazaarApi::fetchInstantBuyBooks, System::currentTimeMillis))
                .handle((fresh, error) -> {
                    scanning.set(false);
                    ScanResult result;
                    if (error != null) {
                        LOGGER.warn("[BazaarFlip] Order-book fetch failed", error);
                        result = new ScanResult(System.currentTimeMillis(), 0, 0, List.of(),
                                "the Bazaar order-book fetch failed");
                    } else if (fresh.error() != null) {
                        result = new ScanResult(System.currentTimeMillis(), 0, 0, List.of(), fresh.error());
                    } else {
                        result = cost(fresh.books().books(), purse, mode, npcCoinsLeft);
                    }
                    last = result;
                    return result;
                });
    }

    /** A fetch judged fresh, or why not. */
    public record Fresh(BazaarApi.InstantBuyBooks books, int attempts, String error) {
    }

    /**
     * Fetches until the snapshot's own {@code lastUpdated} is within {@link #MAX_BOOK_AGE_MS} of now: one refetch for a
     * stale or empty answer, then a refusal naming the age. Pure apart from {@code fetch}, so the testkit drives it with
     * canned snapshots.
     */
    public static Fresh fetchFresh(java.util.function.Supplier<BazaarApi.InstantBuyBooks> fetch,
                                   java.util.function.LongSupplier now) {
        BazaarApi.InstantBuyBooks got = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            got = fetch.get();
            if (got == null || got.books().isEmpty()) {
                continue;
            }
            long age = now.getAsLong() - got.lastUpdatedMs();
            if (got.lastUpdatedMs() > 0 && age <= MAX_BOOK_AGE_MS) {
                return new Fresh(got, attempt, null);
            }
            LOGGER.warn("[BazaarFlip] Bazaar snapshot is {} s old (lastUpdated {}) - refetching", age / 1000,
                    got.lastUpdatedMs());
        }
        if (got == null || got.books().isEmpty()) {
            return new Fresh(got, 2, "the Bazaar order-book fetch failed");
        }
        return new Fresh(got, 2, String.format(java.util.Locale.US,
                "the Bazaar data Hypixel sent is %d s old (it must be under %d s) - try again in a moment",
                (now.getAsLong() - got.lastUpdatedMs()) / 1000, MAX_BOOK_AGE_MS / 1000));
    }

    /**
     * The costing itself, separated from the fetch so it can be reasoned about (and driven from a command)
     * with a known book instead of a live one.
     */
    public static ScanResult cost(Map<String, List<BazaarOrderLevel>> books, double purse,
                                  BazaarFlipConfig.RankingMode mode, double npcCoinsLeft) {
        if (books == null || books.isEmpty()) {
            return new ScanResult(System.currentTimeMillis(), 0, 0, List.of(),
                    "the Bazaar returned no order books");
        }
        List<SkyblockItemEntry> catalog = SkyblockItemRepository.getItems();
        if (catalog.isEmpty()) {
            return new ScanResult(System.currentTimeMillis(), 0, 0, List.of(),
                    "the Skyblock item catalogue has not loaded yet - open the Item Browser once, or wait a moment");
        }
        int withNpcPrice = 0;
        int profitable = 0;
        List<BazaarFlipCandidate> survivors = new ArrayList<>();
        for (SkyblockItemEntry entry : catalog) {
            Double npc = entry.npcSellPrice();
            if (npc == null || npc <= 0) {
                continue;
            }
            List<BazaarOrderLevel> book = books.get(entry.id());
            if (book == null || book.isEmpty()) {
                continue;
            }
            withNpcPrice++;
            // Cheapest level already at or above the NPC price: nothing on this book can ever profit, so it
            // is not even a candidate. Checked before sizing purely to keep the loop cheap.
            if (book.getFirst().pricePerUnit() >= npc) {
                continue;
            }
            int stackSize = SkyblockItemStackFactory.materialMaxStackSize(entry.material());
            BazaarFlipCandidate candidate = size(entry.id(), entry.name(), book, npc, purse, stackSize, npcCoinsLeft);
            if (candidate == null) {
                continue;
            }
            // size() only returns a candidate whose net profit is above 0: every one of them is in the ranking.
            profitable++;
            survivors.add(candidate);
        }
        survivors.sort(comparator(mode));
        return new ScanResult(System.currentTimeMillis(), withNpcPrice, profitable, List.copyOf(survivors), null);
    }

    /** Ranking. Ties broken by the other metric so the order is stable rather than input-order-dependent. */
    private static Comparator<BazaarFlipCandidate> comparator(BazaarFlipConfig.RankingMode mode) {
        if (mode == BazaarFlipConfig.RankingMode.PERCENTAGE_MARGIN) {
            return Comparator.comparingDouble(BazaarFlipCandidate::marginPercent).reversed()
                    .thenComparing(Comparator.comparingDouble(BazaarFlipCandidate::profit).reversed());
        }
        return Comparator.comparingDouble(BazaarFlipCandidate::profit).reversed()
                .thenComparing(Comparator.comparingDouble(BazaarFlipCandidate::marginPercent).reversed());
    }

    /**
     * Walks one product's instant-buy book cheapest-first and reports what a real purchase would do.
     *
     * <p>Stops at the first of three limits:
     * <ol>
     *   <li>the level's price reaching {@code npcSellPrice} - a unit bought at or above that loses money, so
     *       the walk never crosses it even when there is purse and space left;</li>
     *   <li>{@link #INVENTORY_SLOTS} times {@code stackSize} units - one full inventory, which is what
     *       killer560 asked for ("buy as many as possible or a full inventory"). Not reduced by whatever he
     *       happens to be carrying: that is a live inventory question, and the runner checks real free space
     *       before it buys anything rather than the scanner guessing at it;</li>
     *   <li>the purse. Passing the purse in here is the whole of the "if I don't have the money for the best
     *       thing then it buys the best thing I can afford" failsafe - an unaffordable product simply sizes
     *       down to whatever the purse reaches, and if that no longer clears the profit floor it drops out of
     *       the ranking and the next product wins on its own merits.</li>
     * </ol>
     *
     * @return the costed candidate, or null if nothing profitable can be bought at all
     */
    public static BazaarFlipCandidate size(String productId, String displayName, List<BazaarOrderLevel> book,
                                           double npcSellPrice, double purse, int stackSize) {
        return size(productId, displayName, book, npcSellPrice, purse, stackSize, Double.MAX_VALUE);
    }

    /** {@link #size(String, String, List, double, double, int)}, never past what the NPCs will still pay today
     *  ({@code npcCoinsLeft / npcSellPrice} units - the daily NPC sell limit, {@link BazaarFlipNpcLimit}). */
    public static BazaarFlipCandidate size(String productId, String displayName, List<BazaarOrderLevel> book,
                                           double npcSellPrice, double purse, int stackSize, double npcCoinsLeft) {
        long sellable = npcSellPrice > 0 ? (long) Math.floor(npcCoinsLeft / npcSellPrice) : 0;
        int cap = (int) Math.min(INVENTORY_SLOTS * (long) Math.max(1, stackSize), sellable);
        if (cap <= 0) {
            return null;
        }
        long units = 0;
        double spend = 0;
        boolean capacityLimited = false;
        for (BazaarOrderLevel level : book) {
            if (level.pricePerUnit() >= npcSellPrice) {
                break;
            }
            long room = cap - units;
            if (room <= 0) {
                capacityLimited = true;
                break;
            }
            long take = Math.min(level.amount(), room);
            // Integer division on purpose: a partial unit cannot be bought, and rounding up would size a
            // purchase the purse cannot actually pay for.
            // The purse has to cover Hypixel's QUOTE (book + 4%, refunded after), not just the book.
            long affordable = (long) ((purse - spend * (1 + INSTANT_BUY_QUOTE_MARGIN))
                    / (level.pricePerUnit() * (1 + INSTANT_BUY_TAX) * (1 + INSTANT_BUY_QUOTE_MARGIN)));
            take = Math.min(take, affordable);
            if (take <= 0) {
                break;
            }
            units += take;
            spend += take * level.pricePerUnit() * (1 + INSTANT_BUY_TAX);
            if (units >= cap) {
                capacityLimited = true;
                break;
            }
        }
        if (units <= 0 || spend <= 0) {
            return null;
        }
        double revenue = units * npcSellPrice * (1 - NPC_SELL_FEE);
        double profit = revenue - spend;
        if (profit <= 0) {
            return null;
        }
        return new BazaarFlipCandidate(productId, displayName, stackSize, (int) units, spend, npcSellPrice,
                revenue, profit, profit / spend * 100.0, capacityLimited);
    }
}
