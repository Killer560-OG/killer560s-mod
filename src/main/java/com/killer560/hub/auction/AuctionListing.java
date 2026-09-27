package com.killer560.hub.auction;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * One real, live Hypixel Auction House listing - either a BIN (buy-it-now) or a normal bid auction, as
 * returned by the keyless {@code https://api.hypixel.net/skyblock/auctions} endpoint (see
 * {@link AuctionHouseApi} for the exact shape and why that path, not {@code /v2/skyblock/auctions}).
 * <p>
 * killer560, 2026-09-27: "make the ah viewer have an option to toggle between auctions and bins" - both
 * kinds are scanned and kept now (see {@link #bin}); {@code AuctionHouseScreen}'s mode toggle just filters
 * which half of this same list it shows, via {@link com.killer560.hub.auction.AuctionConfig.ListingMode}.
 * <p>
 * {@code icon} is either the real decoded item (built by {@link AuctionHouseApi} from the listing's own
 * real {@code item_bytes} NBT via {@link com.killer560.hub.profileviewer.item.LegacyItems#decodeBase64}
 * during a live scan - the exact real skin/reforge/model of the item actually being sold) or, for a
 * listing that only came from the on-disk cache before the first live scan of this session finishes, a
 * catalog-approximate icon resolved by {@link #skyblockId} through
 * {@link com.killer560.hub.itembrowser.SkyblockItemRepository} and
 * {@link com.killer560.hub.itembrowser.SkyblockItemStackFactory} (see {@link AuctionHouseApi#loadFromDisk}) -
 * never a placeholder that pretends to be the real item.
 */
public record AuctionListing(
        UUID uuid,
        UUID auctioneer,
        /** Hypixel's own real display name for the listing, exactly as shown in-game (includes the
         *  pet "[Lvl N]" prefix, reforge, stars, etc.) - color codes stripped for search/sort/display. */
        String itemName,
        /** Real Hypixel {@code ExtraAttributes.id} for the item being sold, or "" if the item_bytes
         *  couldn't be decoded (a listing is never dropped just because of that - it still shows). */
        String skyblockId,
        /** Real Hypixel rarity tier string (e.g. {@code LEGENDARY}), or null if unknown. */
        String tier,
        String category,
        long startingBid,
        /** True for a BIN (buy-it-now) listing, false for a normal bid auction. */
        boolean bin,
        /** Highest real bid placed so far on a non-BIN auction (the max of Hypixel's own real
         *  {@code bids[].amount} entries), or 0 if it's a BIN or has no bids yet - see
         *  {@link AuctionHouseApi#decode}. Always 0 for a BIN listing. */
        long highestBid,
        /** Real auction end time, epoch millis. */
        long end,
        /** Real pet level parsed from the listing's own {@code [Lvl N]} name prefix, or -1 if this isn't
         *  a pet listing - see {@link AuctionHouseApi#parsePetLevel}. */
        int petLevel,
        /** Real Ultimate enchant name (e.g. "Ultimate Wise"), or null if the item has none - parsed from
         *  the real decoded item's own lore text, see {@link AuctionHouseApi#findUltimateEnchant}. */
        String ultimateEnchantName,
        /** Roman-numeral tier of {@link #ultimateEnchantName}, 0 if none. */
        int ultimateEnchantTier,
        /** Real, plain (color-stripped) lore lines from the decoded item, for the hover tooltip. Empty
         *  (never null) for a disk-cache-only listing (lore isn't cached - see {@link AuctionHouseApi}). */
        List<String> lore,
        ItemStack icon
) {
    public boolean isPet() {
        return petLevel >= 0;
    }

    public boolean hasUltimateEnchant() {
        return ultimateEnchantName != null;
    }

    /** What the listing actually costs to act on right now: the current highest bid for a bid auction
     *  that already has one, otherwise the starting bid (which IS the BIN price for a BIN listing, and
     *  the opening bid for a fresh bid auction with no bids yet). Used for the "Price" sort/display in
     *  both modes so a bid auction with active bids sorts and shows by what it'd actually cost to win it,
     *  not its stale opening number. */
    public long currentPrice() {
        return highestBid > 0 ? highestBid : startingBid;
    }
}
