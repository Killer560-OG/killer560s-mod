package com.killer560.hub.ahflip;

import java.util.Map;

/**
 * The AH Flipper's arithmetic, pure (no Minecraft type), so the testkit checks it without a client.
 *
 * <p>Fees, from hypixelskyblock.minecraft.wiki (Auction House, read 2026-11-01): a BIN listing costs "2.5% of the item's
 * price above 100M Coins, 2% between 10M and 100M, and 1% below 10M", and "When collecting auctions and BINs above 1,000,000
 * Coins, up to 1% tax is collected", never taking the amount collected below 1,000,000. The listing's duration fee is a few
 * hundred to a few thousand coins and is left out; Min Profit covers it.
 *
 * <p>A flip: a new BIN listed under what the same item (same {@link AhItemKey}) sells for elsewhere. Its sale price is the
 * cheapest OTHER BIN of that item, undercut by the Undercut setting, and it is trusted only when at least Min Listings other
 * BINs exist (one listing is a price nobody has paid). Profit is the sale after the listing fee and the collection tax, less
 * the price paid.
 */
public final class AhFlipPricing {

    private AhFlipPricing() {
    }

    /** The settings a flip is judged by. {@code maxBuyPrice} and {@code purse} 0 or less: no cap. */
    public record Settings(int minProfitPercent, int undercutTenths, long minSellPrice, long minProfit, long maxBuyPrice,
                           int minListings) {
        public static Settings of(AhFlipConfig c) {
            return new Settings(c.getMinProfitPercent(), c.getUndercutTenths(), c.getMinSellPrice(), c.getMinProfit(),
                    c.getMaxBuyPrice(), c.getMinListings());
        }
    }

    /** The cheapest, its auction, the second cheapest and the count of one item's BINs in a scan. */
    public record Market(long lowest, java.util.UUID lowestUuid, long second, int count) {

        /** The cheapest BIN that is not {@code uuid}, or -1. */
        public long lowestExcept(java.util.UUID uuid) {
            if (uuid != null && uuid.equals(lowestUuid)) {
                return count > 1 ? second : -1;
            }
            return lowest;
        }

        /** BINs other than {@code uuid}. */
        public int othersExcept(java.util.UUID uuid) {
            return uuid != null && uuid.equals(lowestUuid) ? count - 1 : count;
        }
    }

    /** One flip found. */
    public record Flip(java.util.UUID uuid, String key, String name, long price, long sellAt, long net, long profit,
                       double percent, int listings) {
    }

    /** The BIN listing fee rate at {@code price}. */
    public static double listingFeeRate(long price) {
        if (price >= 100_000_000L) {
            return 0.025;
        }
        return price >= 10_000_000L ? 0.02 : 0.01;
    }

    /** The tax taken when the coins of a sale at {@code price} are collected: 1% above 1,000,000, never under 1,000,000. */
    public static long collectTax(long price) {
        return price <= 1_000_000L ? 0L : Math.min((long) Math.ceil(price * 0.01), price - 1_000_000L);
    }

    /** What a BIN sold at {@code price} puts in the purse: the price less its listing fee and the collection tax. */
    public static long netOfSale(long price) {
        return price <= 0 ? 0L : price - (long) Math.ceil(price * listingFeeRate(price)) - collectTax(price);
    }

    /** The relist price: {@code lowestOther} less the undercut (tenths of a percent), and at least one coin under it. */
    public static long relistPrice(long lowestOther, int undercutTenths) {
        if (lowestOther <= 1) {
            return Math.max(0, lowestOther);
        }
        long cut = (long) Math.floor(lowestOther * (1 - Math.max(0, undercutTenths) / 1000.0));
        return Math.max(1, Math.min(lowestOther - 1, cut));
    }

    /**
     * Judges one new BIN. Null when it is not a flip under {@code s}: no market for its key, too few other listings, a sale
     * under Min Sell Price, a price over Max Buy Price or the purse, or a profit under Min Profit or Min Profit %.
     */
    public static Flip evaluate(java.util.UUID uuid, String key, String name, long price, Market market, Settings s,
                                long purse) {
        if (key == null || key.isEmpty() || market == null || price <= 0 || s.maxBuyPrice() > 0 && price > s.maxBuyPrice() || purse > 0 && price > purse) {
            return null;
        }
        long lowestOther = market.lowestExcept(uuid);
        int others = market.othersExcept(uuid);
        if (lowestOther <= 0 || others < Math.max(1, s.minListings())) {
            return null;
        }
        long sellAt = relistPrice(lowestOther, s.undercutTenths());
        if (sellAt < s.minSellPrice()) {
            return null;
        }
        long net = netOfSale(sellAt);
        long profit = net - price;
        double percent = profit * 100.0 / price;
        if (profit < Math.max(1, s.minProfit()) || percent < s.minProfitPercent()) {
            return null;
        }
        return new Flip(uuid, key, name, price, sellAt, net, profit, percent, others);
    }

    /** Builds the per-key market from (key, uuid, price) BIN rows. */
    public static Map<String, Market> index(Iterable<Row> rows) {
        Map<String, long[]> acc = new java.util.HashMap<>();
        Map<String, java.util.UUID> lowestUuid = new java.util.HashMap<>();
        for (Row r : rows) {
            if (r.key() == null || r.key().isEmpty() || r.price() <= 0) {
                continue;
            }
            long[] a = acc.computeIfAbsent(r.key(), k -> new long[]{Long.MAX_VALUE, Long.MAX_VALUE, 0});
            if (r.price() < a[0]) {
                a[1] = a[0];
                a[0] = r.price();
                lowestUuid.put(r.key(), r.uuid());
            } else if (r.price() < a[1]) {
                a[1] = r.price();
            }
            a[2]++;
        }
        Map<String, Market> out = new java.util.HashMap<>(acc.size() * 2);
        for (Map.Entry<String, long[]> e : acc.entrySet()) {
            long[] a = e.getValue();
            out.put(e.getKey(), new Market(a[0], lowestUuid.get(e.getKey()), a[1] == Long.MAX_VALUE ? -1 : a[1], (int) a[2]));
        }
        return out;
    }

    /** One BIN of a scan, for {@link #index}. */
    public record Row(String key, java.util.UUID uuid, long price) {
    }
}
