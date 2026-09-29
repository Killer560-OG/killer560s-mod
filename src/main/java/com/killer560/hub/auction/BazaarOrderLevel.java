package com.killer560.hub.auction;

/**
 * One price level out of a real Hypixel Bazaar order book, straight from a product's
 * {@code buy_summary} / {@code sell_summary} array in {@code https://api.hypixel.net/v2/skyblock/bazaar}.
 * Real fields, verified against the live endpoint 2026-09-29: {@code amount} (units offered at this
 * level), {@code pricePerUnit}, {@code orders} (how many separate orders make up the level).
 *
 * <p><b>Read {@link BazaarApi#fetchInstantBuyBooksAsync()} before using this.</b> Hypixel's two summaries
 * are named the opposite of how they read: {@code buy_summary} is the book you INSTANT-BUY OUT OF, and
 * {@code sell_summary} is the book you instant-sell into. Getting that backwards produces fake
 * billion-coin flips.
 */
public record BazaarOrderLevel(double pricePerUnit, long amount, int orders) {
}
