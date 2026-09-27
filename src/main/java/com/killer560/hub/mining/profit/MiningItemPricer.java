package com.killer560.hub.mining.profit;

import com.killer560.hub.auction.BazaarApi;
import com.killer560.hub.auction.BazaarProduct;
import com.killer560.hub.rngmeter.RngItem;
import com.killer560.hub.rngmeter.RngMeterEngine;
import com.killer560.hub.rngmeter.RngSource;

import java.util.Locale;

/**
 * Shared coin-value lookup for both mining profit trackers ({@link MiningProfitTracker} and
 * {@code com.killer560.hub.mining.nucleus.NucleusRunProfitTracker}), per the brief: "reuse the mod's
 * EXISTING price data (see the auction package - BazaarApi and the price caches) rather than fetching
 * anything new."
 * <p>
 * Two existing sources, tried in this order, and NOTHING here ever makes its own HTTP call:
 * <ol>
 *   <li>{@link BazaarApi#getProducts()} - the same live-refreshing Bazaar scan that already powers the
 *   Bazaar tab. Covers almost everything mining actually drops in bulk (raw ores, gemstones, powder-
 *   adjacent items, mithril-related materials): real instant-sell price, no extra network traffic since
 *   {@link BazaarApi#ensureAutoStarted()} is already running for the Bazaar/Auction House tabs.</li>
 *   <li>{@link RngMeterEngine#PRICES} ({@code HypixelMarketPrices}) - the AH-lowest-BIN + Bazaar cache the
 *   dungeon-side {@code experiments.ExperimentsProfitTracker} already prices arbitrary drops with. Used as
 *   the fallback for anything Bazaar doesn't list (Divan's Alloy, Bal, pets, pristine equipment...).</li>
 * </ol>
 * An item neither source has a price for returns {@code null} - callers must show it as "unpriced" rather
 * than counting it as zero, the same "unknown stays unknown" rule {@link com.killer560.hub.runsummary.RunRecord}
 * documents for its own fields.
 */
public final class MiningItemPricer {

    private MiningItemPricer() {
    }

    /**
     * @param skyblockId the item's {@code ExtraAttributes.id} (see {@code autoroutes.ItemIdentity#of}), or a
     *                    best-effort id built from its display name. May be null.
     * @return coins for ONE unit, or null if unpriced anywhere.
     */
    public static Double priceOf(String skyblockId) {
        if (skyblockId == null || skyblockId.isBlank()) {
            return null;
        }
        String id = skyblockId.trim().toUpperCase(Locale.ROOT);

        // 1) Bazaar - explicitly called out in the brief, and already running for the Bazaar tab.
        for (BazaarProduct product : BazaarApi.getProducts()) {
            if (product.productId().equalsIgnoreCase(id)) {
                return product.sellPrice() > 0 ? product.sellPrice() : null;
            }
        }

        // 2) AH lowest-BIN / Bazaar fallback via the same cache ExperimentsProfitTracker already uses.
        RngSource source = id.startsWith("PET_") || id.startsWith("RUNE_") ? RngSource.AH : RngSource.BAZAAR;
        Long price = RngMeterEngine.PRICES.getPrice(new RngItem("Mining", id, id, source, 0L, false));
        return price == null ? null : price.doubleValue();
    }

    /** Ensures the Bazaar scan this class depends on is actually running - safe to call every tick, no-ops
     *  after the first call (same guard {@link BazaarApi#ensureAutoStarted()} already has). */
    public static void ensurePricesLoading() {
        BazaarApi.ensureAutoStarted();
        RngMeterEngine.PRICES.refreshIfStale();
    }

    public static String shortNumber(double value) {
        double abs = Math.abs(value);
        if (abs >= 1_000_000_000d) return String.format(Locale.US, "%.2fB", value / 1_000_000_000d);
        if (abs >= 1_000_000d) return String.format(Locale.US, "%.2fM", value / 1_000_000d);
        if (abs >= 1_000d) return String.format(Locale.US, "%.1fk", value / 1_000d);
        return String.format(Locale.US, "%.0f", value);
    }
}
