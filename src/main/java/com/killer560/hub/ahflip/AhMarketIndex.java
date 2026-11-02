package com.killer560.hub.ahflip;

import com.killer560.hub.auction.AuctionHouseApi;
import com.killer560.hub.auction.AuctionListing;
import com.killer560.hub.util.ModLog;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The lowest BINs per {@link AhItemKey}, built from the Auction House's last full scan ({@code AuctionHouseApi}, every five
 * minutes while something uses it). Rebuilt off the render thread whenever a new scan lands; until then the last index
 * answers. Only listings whose item was decoded count (a disk-cache listing has a catalog icon, not the item).
 */
public final class AhMarketIndex {

    private static final Logger LOGGER = ModLog.get("killer560smod-ahflip");
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-ahflip-index");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean building = new AtomicBoolean(false);

    private static volatile Map<String, AhFlipPricing.Market> markets = Map.of();
    private static volatile List<AuctionListing> builtFrom;

    private AhMarketIndex() {
    }

    /** Starts a rebuild when the scan changed since the last one. Cheap; call it every tick. */
    public static void refreshIfStale() {
        List<AuctionListing> now = AuctionHouseApi.getListings();
        if (now == builtFrom || now.isEmpty() || !building.compareAndSet(false, true)) {
            return;
        }
        WORKER.execute(() -> {
            try {
                rebuild(now);
            } catch (Exception e) {
                LOGGER.warn("[AhFlip] Couldn't index the AH scan", e);
            } finally {
                building.set(false);
            }
        });
    }

    /** Indexes {@code listings} now, on the calling thread (the testkit's way in). */
    public static void rebuild(List<AuctionListing> listings) {
        List<AhFlipPricing.Row> rows = new ArrayList<>(listings.size());
        for (AuctionListing l : listings) {
            if (!l.bin() || !l.decoded()) {
                continue;
            }
            String key = AhItemKey.of(l.icon(), l.itemName());
            if (!key.isEmpty()) {
                rows.add(new AhFlipPricing.Row(key, l.uuid(), l.startingBid()));
            }
        }
        markets = AhFlipPricing.index(rows);
        builtFrom = listings;
        LOGGER.info("[AhFlip] Indexed {} BINs into {} items", rows.size(), markets.size());
    }

    /** The market of {@code key}, or null when the scan has no BIN of it. */
    public static AhFlipPricing.Market market(String key) {
        return key == null ? null : markets.get(key);
    }

    /** The lowest BIN of {@code key}, or -1. A plain id the index has not built yet falls back to the scan's own lowest. */
    public static long lowestBin(String key) {
        AhFlipPricing.Market m = market(key);
        if (m != null && m.lowest() > 0) {
            return m.lowest();
        }
        if (key != null && !key.isEmpty() && !AhItemKey.isPet(key) && key.indexOf('*') < 0 && key.indexOf('+') < 0) {
            return com.killer560.hub.auction.ah.AhMarket.lowestBin(key);
        }
        return -1;
    }

    /** How many items the index holds (the testkit asserts it was built). */
    public static int size() {
        return markets.size();
    }
}
