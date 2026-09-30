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

    /** Outcome of one scan. {@code error} is null on success. */
    public record ScanResult(
            long atMs,
            int productsWithNpcPrice,
            int profitableCount,
            /** Everything that cleared the profit floor, best first by the requested ranking mode. */
            List<BazaarFlipCandidate> ranked,
            String error
    ) {
        public BazaarFlipCandidate best() {
            return ranked.isEmpty() ? null : ranked.getFirst();
        }

        public boolean ok() {
            return error == null;
        }
    }

    private static volatile ScanResult last = null;
    private static final AtomicBoolean scanning = new AtomicBoolean(false);

    private BazaarFlipScanner() {
    }

    /** The most recent scan, or null if none has completed this session. */
    public static ScanResult getLast() {
        return last;
    }

    public static boolean isScanning() {
        return scanning.get();
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
     * @param minProfitPerRun the floor every candidate must clear, in both modes
     */
    public static CompletableFuture<ScanResult> scanAsync(double purse, BazaarFlipConfig.RankingMode mode,
                                                          long minProfitPerRun) {
        if (!scanning.compareAndSet(false, true)) {
            ScanResult inFlight = last;
            return CompletableFuture.completedFuture(inFlight != null ? inFlight
                    : new ScanResult(System.currentTimeMillis(), 0, 0, List.of(), "a scan is already running"));
        }
        return BazaarApi.fetchInstantBuyBooksAsync()
                .handle((books, error) -> {
                    scanning.set(false);
                    if (error != null) {
                        LOGGER.warn("[BazaarFlip] Order-book fetch failed", error);
                        ScanResult failed = new ScanResult(System.currentTimeMillis(), 0, 0, List.of(),
                                "the Bazaar order-book fetch failed");
                        last = failed;
                        return failed;
                    }
                    ScanResult result = cost(books, purse, mode, minProfitPerRun);
                    last = result;
                    return result;
                });
    }

    /**
     * The costing itself, separated from the fetch so it can be reasoned about (and driven from a command)
     * with a known book instead of a live one.
     */
    public static ScanResult cost(Map<String, List<BazaarOrderLevel>> books, double purse,
                                  BazaarFlipConfig.RankingMode mode, long minProfitPerRun) {
        if (books == null || books.isEmpty()) {
            return new ScanResult(System.currentTimeMillis(), 0, 0, List.of(),
                    "the Bazaar returned no order books");
        }
        List<SkyblockItemEntry> catalog = SkyblockItemRepository.getItems();
        if (catalog.isEmpty()) {
            return new ScanResult(System.currentTimeMillis(), 0, 0, List.of(),
                    "the Skyblock item catalog has not loaded yet - open the Item Browser once, or wait a moment");
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
            BazaarFlipCandidate candidate = size(entry.id(), entry.name(), book, npc, purse, stackSize);
            if (candidate == null) {
                continue;
            }
            profitable++;
            if (candidate.profit() >= minProfitPerRun) {
                survivors.add(candidate);
            }
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
        int cap = INVENTORY_SLOTS * Math.max(1, stackSize);
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
            long affordable = (long) ((purse - spend) / level.pricePerUnit());
            take = Math.min(take, affordable);
            if (take <= 0) {
                break;
            }
            units += take;
            spend += take * level.pricePerUnit();
            if (units >= cap) {
                capacityLimited = true;
                break;
            }
        }
        if (units <= 0 || spend <= 0) {
            return null;
        }
        double revenue = units * npcSellPrice;
        double profit = revenue - spend;
        if (profit <= 0) {
            return null;
        }
        return new BazaarFlipCandidate(productId, displayName, stackSize, (int) units, spend, npcSellPrice,
                revenue, profit, profit / spend * 100.0, capacityLimited);
    }
}
