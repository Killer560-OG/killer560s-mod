package com.killer560.hub.ahflip;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which listings count as "the same item" for the AH Flipper's sale price and the AH Price Buttons' Copy LBIN.
 *
 * <p>The SkyBlock id alone lumps a 0-star item with a 5-star one, and every pet is id {@code PET}. The key adds what moves
 * the price most and is cheap to read off the item's custom data (Hypixel's ExtraAttributes, the same keys
 * {@code ListingHelperFeature} reads): dungeon stars ({@code upgrade_level}, or the older {@code dungeon_item_level}), a
 * Recombobulator ({@code rarity_upgrades}), and for a pet its type, rarity and level ({@code petInfo}, "[Lvl N]" in the
 * name). Enchantments, reforges, gemstones and the rest are not in it, so an item whose value is mostly its enchants can read
 * as cheap next to a bare one; Min Listings and Min Profit % are the guard, and the flipper's chat line names the item so it
 * can be checked before buying.
 */
public final class AhItemKey {

    private static final Pattern PET_LEVEL = Pattern.compile("^\\[Lvl (\\d{1,3})]");

    private AhItemKey() {
    }

    /** The key of {@code stack} (named {@code plainName}, color codes stripped), or "" when it has no SkyBlock id. */
    public static String of(ItemStack stack, String plainName) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return "";
        }
        CompoundTag tag = data.copyTag();
        String id = tag.getStringOr("id", "");
        if (id.isEmpty()) {
            return "";
        }
        if ("PET".equals(id)) {
            return petKey(tag.getStringOr("petInfo", ""), plainName);
        }
        StringBuilder key = new StringBuilder(id);
        int stars = Math.max(tag.getIntOr("upgrade_level", 0), tag.getIntOr("dungeon_item_level", 0));
        if (stars > 0) {
            key.append('*').append(stars);
        }
        if (tag.getIntOr("rarity_upgrades", 0) > 0) {
            key.append("+R");
        }
        return key.toString();
    }

    /** "PET_GOLDEN_DRAGON_LEGENDARY_L100", or "" when the pet info is unreadable. */
    static String petKey(String petInfo, String plainName) {
        try {
            JsonObject o = JsonParser.parseString(petInfo).getAsJsonObject();
            String type = o.get("type").getAsString();
            String tier = o.has("tier") ? o.get("tier").getAsString() : "";
            String level = "";
            if (plainName != null) {
                Matcher m = PET_LEVEL.matcher(plainName.trim());
                if (m.find()) {
                    level = "_L" + m.group(1);
                }
            }
            return ("PET_" + type + "_" + tier + level).toUpperCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    /** Whether the key is a pet's (the flipper leaves pets out: their price rides on held items and experience). */
    public static boolean isPet(String key) {
        return key != null && key.startsWith("PET_");
    }
}
