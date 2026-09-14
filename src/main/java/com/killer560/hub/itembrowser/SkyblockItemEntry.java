package com.killer560.hub.itembrowser;

/** One real Skyblock item, as returned by Hypixel's own public
 * {@code https://api.hypixel.net/v2/resources/skyblock/items} resource (no API key needed - a real
 * public "resources" endpoint, not an authenticated one). {@code itemModel}/{@code skinValue}/
 * {@code skinSignature} are null when the item has neither (a plain vanilla-material item). */
public record SkyblockItemEntry(
        String id,
        String name,
        String material,
        String itemModel,
        String skinValue,
        String skinSignature
) {
}
