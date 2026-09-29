package com.killer560.hub.bazaarflip;

import java.util.Locale;

/**
 * One costed Bazaar-to-NPC flip: what {@link BazaarFlipScanner} worked out that a single buy-and-sell cycle
 * of this product would actually do, for a specific purse and a specific inventory size.
 *
 * <p>Every number here comes from walking the product's real instant-buy order book level by level (see
 * {@link BazaarFlipScanner#size}), not from {@code quick_status.buyPrice}, which is a weighted average and
 * overstates what a real purchase realises.
 *
 * @param productId       real Hypixel Bazaar product id, e.g. {@code ENCHANTED_DIAMOND_BLOCK}
 * @param displayName     the catalog's display name, which is also what gets typed at {@code /bz}
 * @param stackSize       units per inventory slot used for the capacity cap (vanilla figure - see
 *                        {@code SkyblockItemStackFactory#materialMaxStackSize})
 * @param units           units the walk says can actually be bought
 * @param spend           coins the walk says those units cost, level prices included
 * @param npcSellPrice    the item's {@code npc_sell_price} from the resources endpoint
 * @param revenue         {@code units * npcSellPrice}
 * @param profit          {@code revenue - spend}
 * @param marginPercent   {@code profit / spend * 100} - realised, not top-of-book
 * @param capacityLimited true when the walk stopped because the inventory filled rather than because the
 *                        purse ran out or the book got too expensive
 */
public record BazaarFlipCandidate(
        String productId,
        String displayName,
        int stackSize,
        int units,
        double spend,
        double npcSellPrice,
        double revenue,
        double profit,
        double marginPercent,
        boolean capacityLimited
) {

    /** Average coins actually paid per unit across the whole walk. */
    public double averageUnitCost() {
        return units > 0 ? spend / units : 0;
    }

    /** One-line summary for chat and the settings tab. */
    public String summary() {
        return String.format(Locale.US, "%s x%,d - spend %,.0f, profit %,.0f (%.2f%%)",
                displayName, units, spend, profit, marginPercent);
    }
}
