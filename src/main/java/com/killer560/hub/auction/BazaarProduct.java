package com.killer560.hub.auction;

import java.util.List;

/**
 * One Hypixel Bazaar product from the keyless {@code https://api.hypixel.net/v2/skyblock/bazaar} resource.
 * <p>
 * <b>Hypixel names the two sides from the instant trader's point of view</b> (see {@link BazaarApi}'s order-book doc):
 * {@code buyPrice}/{@code buyVolume}/{@code buyOrders}/{@code buyMovingWeek} describe the SELL OFFERS an instant buy
 * consumes, and the {@code sell*} fields the BUY ORDERS an instant sell fills. Verified on ENCHANTED_DIAMOND
 * 2026-10-07: {@code buy_summary} starts at 1,319.1 next to {@code quick_status.buyPrice} 1,320.0. The fields below
 * keep Hypixel's names; the screen labels them by what they are.
 * <p>
 * Name, category, group and rarity come from {@link BazaarCatalog} (the bundled table) first and the live item catalog
 * second. The icon is not stored here: {@link BazaarIcons} builds it on the render thread when first drawn.
 *
 * @param topBuyOrders  highest buy orders first (Hypixel's {@code sell_summary}), at most {@link BazaarApi#BOOK_DEPTH}
 * @param topSellOffers lowest sell offers first (Hypixel's {@code buy_summary}), at most {@link BazaarApi#BOOK_DEPTH}
 */
public record BazaarProduct(
        String productId,
        String displayName,
        double buyPrice,
        double sellPrice,
        long buyVolume,
        long sellVolume,
        long buyMovingWeek,
        long sellMovingWeek,
        int buyOrders,
        int sellOrders,
        /** Hypixel Bazaar category ("Farming", "Mining", "Combat", "Woods &amp; Fishes", "Oddities"). */
        String category,
        /** The group inside that category ("Wheat &amp; Seeds"), Hypixel's own sub-menu. */
        String group,
        /** Index into {@link BazaarCatalog#groups()}, or -1 when the product is newer than the bundled table. */
        int groupIndex,
        /** Rarity tier (COMMON...), or null if unknown; colours the name. */
        String tier,
        /** An ultimate enchantment's book: drawn in Hypixel's bold pink. */
        boolean ultimate,
        List<BazaarOrderLevel> topBuyOrders,
        List<BazaarOrderLevel> topSellOffers
) {
    /** Instant-buy minus instant-sell - what a flip on this product could earn before Bazaar's own tax. */
    public double spread() {
        return buyPrice - sellPrice;
    }

    /** The spread as a percentage of the instant-sell price (0 when there is nothing to sell into). */
    public double marginPercent() {
        return sellPrice > 0 ? spread() / sellPrice * 100.0 : 0;
    }

    /** Price of the best buy order right now (what a new buy order has to beat), or 0. */
    public double topBuyOrderPrice() {
        return topBuyOrders.isEmpty() ? 0 : topBuyOrders.get(0).pricePerUnit();
    }

    /** Price of the best (cheapest) sell offer right now (what a new sell offer has to undercut), or 0. */
    public double topSellOfferPrice() {
        return topSellOffers.isEmpty() ? 0 : topSellOffers.get(0).pricePerUnit();
    }

    /** Items traded instantly both ways over the last seven days. */
    public long weeklyVolume() {
        return buyMovingWeek + sellMovingWeek;
    }
}
