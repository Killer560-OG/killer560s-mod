package com.killer560.hub.rngmeter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Shared, process-lifetime price cache so re-opening the RNG Meter screen doesn't refetch. */
public final class RngMeterEngine {

    public static final HypixelMarketPrices PRICES = new HypixelMarketPrices();

    private RngMeterEngine() {
    }

    public record RankedItem(RngItem item, Long price, Double coinsPerPity, int rank) {
    }

    /** Ranks every item given, with no group filtering - for a live-scanned menu's own item list. */
    public static List<RankedItem> rankAll(List<RngItem> items) {
        return rank(items, item -> true);
    }

    private static List<RankedItem> rank(List<RngItem> items, Predicate<RngItem> filter) {
        List<RankedItem> ranked = new ArrayList<>();
        for (RngItem item : items) {
            if (!filter.test(item)) {
                continue;
            }
            Long price = item.soulbound() ? null : PRICES.getPrice(item);
            Double cpp = price != null ? price / (double) item.pityXp() : null;
            ranked.add(new RankedItem(item, price, cpp, 0));
        }
        ranked.sort((a, b) -> {
            if (a.coinsPerPity() == null && b.coinsPerPity() == null) return 0;
            if (a.coinsPerPity() == null) return 1;
            if (b.coinsPerPity() == null) return -1;
            return Double.compare(b.coinsPerPity(), a.coinsPerPity());
        });

        List<RankedItem> result = new ArrayList<>(ranked.size());
        int rank = 1;
        for (RankedItem r : ranked) {
            int thisRank = r.coinsPerPity() != null ? rank++ : 0;
            result.add(new RankedItem(r.item(), r.price(), r.coinsPerPity(), thisRank));
        }
        return result;
    }
}
