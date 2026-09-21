package com.killer560.hub.itembrowser;

import java.util.List;

/** One real Skyblock item, as returned by Hypixel's own public
 * {@code https://api.hypixel.net/v2/resources/skyblock/items} resource (no API key needed - a real
 * public "resources" endpoint, not an authenticated one). {@code itemModel}/{@code skinValue}/
 * {@code skinSignature} are null when the item has neither (a plain vanilla-material item).
 * <p>
 * The fields below back the craft/obtain view ({@link ItemCraftView}) - all real fields the live
 * catalog actually carries, not invented ones:
 * <ul>
 *   <li>{@code recipeIngredients}/{@code recipeOutputId}/{@code recipeOutputCount} - Hypixel's own
 *   {@code recipes[0]} entry (a 3x3 crafting-grid {@code matrix} of symbols resolved through
 *   {@code ingredient_symbols}). Verified live (2026-09-21): only 1 of 5,655 real items
 *   ({@code PRECURSOR_APPARATUS}) actually carries this any more - Hypixel stopped shipping recipe
 *   data for almost everything a long time ago. {@code recipeIngredients} is null when the entry has
 *   none - {@link ItemCraftView} says so plainly instead of drawing an empty grid.</li>
 *   <li>{@code obtainLines} - real, pre-formatted lines built from whatever of
 *   {@code requirements}/{@code catacombs_requirements}/{@code npc_sell_price}/{@code generator}/
 *   {@code museum}/{@code salvage}/{@code salvages} the item actually has (494-2575 items each,
 *   depending on the field - see {@link SkyblockItemRepository}). Empty (never null) when the item has
 *   none of these.</li>
 *   <li>{@code description} - Hypixel's own real {@code description} flavor-text field (494 of 5,655
 *   items), with its {@code %%color%%} tokens converted to section-sign codes. Not full item lore (no
 *   stats) - Hypixel's public resource doesn't expose that.</li>
 * </ul>
 */
public record SkyblockItemEntry(
        String id,
        String name,
        String material,
        String itemModel,
        String skinValue,
        String skinSignature,
        String tier,
        String category,
        Double npcSellPrice,
        String description,
        List<String> recipeIngredients,
        String recipeOutputId,
        int recipeOutputCount,
        List<String> obtainLines
) {
}
