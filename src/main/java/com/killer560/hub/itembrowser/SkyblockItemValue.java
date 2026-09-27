package com.killer560.hub.itembrowser;

import com.killer560.hub.rngmeter.RngItem;
import com.killer560.hub.rngmeter.RngMeterEngine;
import com.killer560.hub.rngmeter.RngSource;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Locale;

/**
 * Shared "what is this item worth" lookup - killer560 (2026-09-27): "make a whole new setting just like
 * skyhanni that shows the items value on its tooltip and use that info as well [for the item browser's
 * sort]." One value source for both features, per the brief ("use that info as well" / item 3's own
 * "use the same value source for the browser's value sort"):
 * <ul>
 *   <li>{@link #getValue(String)} - a catalog {@link SkyblockItemEntry#id()} straight to
 *   {@link RngMeterEngine#PRICES}, the mod's existing Bazaar-first/AH-lowest-BIN price engine
 *   ({@link com.killer560.hub.rngmeter.HypixelMarketPrices}) - already running its own background
 *   refresh (see {@code RngMeterConfig}), reused here rather than fetching anything new, exactly like
 *   {@link com.killer560.hub.croesus.DungeonChestValuer} already does for Croesus.</li>
 *   <li>{@link #getValue(ItemStack)} - resolves a real, in-game {@code ItemStack} (anywhere: inventory,
 *   a container, held) down to its Hypixel internal id first, ported from
 *   {@link com.killer560.hub.croesus.DungeonChestValuer}'s own {@code priceStack} (enchanted books and
 *   pets need their id built from NBT sub-fields, not the plain {@code id} tag), then the same lookup.</li>
 * </ul>
 * Both return null (never 0 or a guess) when nothing is known - "no price known" is a real, common case
 * (most of the ~5,655-item catalog has no Bazaar/AH listing at all) that every call site has to handle
 * gracefully rather than showing a misleading "0 coins".
 */
public final class SkyblockItemValue {

    private SkyblockItemValue() {
    }

    /** Live unit price for a catalog id, or null if nothing is known for it yet (prices not loaded,
     *  or this item just isn't tradable on the Bazaar or Auction House). */
    public static Long getValue(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        return RngMeterEngine.PRICES.getPrice(new RngItem("", "", id, RngSource.BAZAAR, 0, false));
    }

    /** Live unit price for a real item stack (anywhere in the game), or null if it couldn't be
     *  identified or has no known price. */
    public static Long getValue(ItemStack stack) {
        String id = extractId(stack);
        return id == null ? null : getValue(id);
    }

    /** Hypixel internal item id for a real stack, ported from
     *  {@link com.killer560.hub.croesus.DungeonChestValuer}'s own {@code priceStack} id resolution
     *  (enchanted books resolve to {@code ENCHANTMENT_<NAME>_<LEVEL>}, pets to {@code PET_<TYPE>}, a
     *  starred item's {@code STARRED_} prefix is stripped) - null for anything with no Hypixel item NBT
     *  at all (a plain vanilla block/item never sold through Skyblock's own economy). */
    public static String extractId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        String id = tag.getStringOr("id", null);
        if (id == null || id.isEmpty()) {
            return null;
        }
        if (id.equals("ENCHANTED_BOOK")) {
            CompoundTag ench = tag.getCompoundOrEmpty("enchantments");
            if (ench.keySet().size() != 1) {
                return null; // a book with 0 or 2+ enchants isn't one specific tradable id
            }
            String key = ench.keySet().iterator().next();
            int level = ench.getIntOr(key, 1);
            return "ENCHANTMENT_" + key.toUpperCase(Locale.US) + "_" + level;
        }
        if (id.equals("PET")) {
            try {
                String type = com.google.gson.JsonParser.parseString(tag.getStringOr("petInfo", "{}"))
                        .getAsJsonObject().get("type").getAsString();
                return "PET_" + type;
            } catch (Exception e) {
                return null;
            }
        }
        if (id.startsWith("STARRED_")) {
            id = id.substring("STARRED_".length());
        }
        return id;
    }

    /** "1.25M", "350k" - same shorthand {@code DungeonChestValuer.formatCoins} uses for Croesus, kept as
     *  its own tiny copy here rather than a cross-package call (itembrowser is a shared/lower-level
     *  feature other packages - including croesus - already depend on; not the other way around). */
    public static String formatCoins(long coins) {
        long abs = Math.abs(coins);
        String sign = coins < 0 ? "-" : "";
        if (abs >= 1_000_000_000L) {
            return sign + String.format(Locale.US, "%.2fB", abs / 1e9);
        }
        if (abs >= 1_000_000L) {
            return sign + String.format(Locale.US, "%.2fM", abs / 1e6);
        }
        if (abs >= 1_000L) {
            return sign + String.format(Locale.US, "%.1fk", abs / 1e3);
        }
        return sign + abs;
    }
}
