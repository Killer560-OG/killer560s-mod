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
        net.minecraft.world.item.ItemStack icon
) {
    /** Instant-buy minus instant-sell - what a flip on this product could earn before Bazaar's own tax. */
    public double spread() {
        return buyPrice - sellPrice;
    }
}
