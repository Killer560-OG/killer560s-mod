package com.killer560.hub.auction;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * One real, live Hypixel Auction House BIN (buy-it-now) listing, as returned by the keyless
 * {@code https://api.hypixel.net/skyblock/auctions} endpoint (see {@link AuctionHouseApi} for the exact
 * shape and why that path, not {@code /v2/skyblock/auctions}). Non-BIN (pure-bid) auctions are never
 * turned into one of these - {@link AuctionHouseApi} filters them out while scanning, since killer560's
 * item 8.1 only asked for "Custom AH search/sell menu" browsing of BIN listings.
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
}
