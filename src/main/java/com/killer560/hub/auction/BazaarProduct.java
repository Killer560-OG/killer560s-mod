package com.killer560.hub.auction;

/**
 * One real Hypixel Bazaar product, from the keyless {@code https://api.hypixel.net/v2/skyblock/bazaar}
 * resource's {@code quick_status} block (real fields: {@code sellPrice}, {@code buyPrice},
 * {@code sellVolume}, {@code buyVolume}). {@code displayName} is resolved through the shared real item
 * catalog ({@link com.killer560.hub.itembrowser.SkyblockItemRepository}) by {@code productId}, falling
 * back to a title-cased version of the raw id when the catalog doesn't have (or hasn't loaded) that id -
 * see {@link BazaarApi}.
 */
public record BazaarProduct(
        String productId,
        String displayName,
        double buyPrice,
        double sellPrice,
        long buyVolume,
        long sellVolume,
        /** Real Hypixel Skyblock category from the shared catalog entry (e.g. "misc", "blocks"), or null
         *  if the catalog doesn't have (or hasn't loaded) this product's id - killer560, 2026-09-27:
         *  "for the custom bazaar... set it up differently", used by {@code BazaarScreen}'s category rail
         *  so it groups the same way {@code AuctionHouseScreen} does, per "have the two feel like a
         *  consistent pair". */
        String category,
        /** Real Hypixel rarity tier from the shared catalog entry, or null if unknown - used only to color
         *  the product name in {@code BazaarScreen}'s row/tooltip, same as {@code AuctionListing#tier()}
         *  does for the Auction House. */
        String tier,
        net.minecraft.world.item.ItemStack icon
) {
    /** Instant-buy minus instant-sell - what a flip on this product could earn before Bazaar's own tax. */
    public double spread() {
        return buyPrice - sellPrice;
    }
}
