package com.killer560.hub.pricetooltip;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

/**
 * The price key of a Skyblock item: the id {@link PriceData}'s Bazaar and auction maps are keyed by. Mostly the item's
 * {@code id}; a few items share one id and are told apart by another field (same scheme as Skyblocker's API ids,
 * reimplemented here):
 * <ul>
 *   <li>an enchanted book with one enchant: {@code ENCHANTMENT_<NAME>_<LEVEL>} (a Bazaar product);</li>
 *   <li>a pet: {@code LVL_<1|100|200>_<TIER>_<TYPE>} from {@code petInfo}, level 100/200 read off the name;</li>
 *   <li>a rune: {@code <TYPE>_RUNE_<LEVEL>} from {@code runes};</li>
 *   <li>a party or balloon hat: {@code <ID>_<COLOR>} ({@code party_hat_emoji} for the sloth hat); a New Year cake:
 *       {@code NEW_YEAR_CAKE_<year>};</li>
 *   <li>a shiny item ({@code is_shiny}): {@code SHINY_<ID>} when that is listed, else the plain id.</li>
 * </ul>
 */
public final class ItemKeys {

    private ItemKeys() {
    }

    /** The plain Skyblock id, or null for an item that has none. */
    public static String id(CompoundTag tag) {
        if (tag == null) {
            return null;
        }
        String id = tag.getStringOr("id", "");
        return id.isEmpty() ? null : id;
    }

    /** The price key (see the class doc), or null for an item with no Skyblock id. */
    public static String priceKey(CompoundTag tag, String hoverName) {
        String id = id(tag);
        if (id == null) {
            return null;
        }
        switch (id) {
            case "ENCHANTED_BOOK" -> {
                CompoundTag ench = tag.getCompoundOrEmpty("enchantments");
                if (ench.size() != 1) {
                    return id;
                }
                String name = ench.keySet().iterator().next();
                return "ENCHANTMENT_" + name.toUpperCase(Locale.ROOT) + "_" + ench.getIntOr(name, 1);
            }
            case "PET" -> {
                JsonObject info = petInfo(tag);
                if (info == null) {
                    return id;
                }
                String type = str(info, "type");
                String tier = str(info, "tier");
                if (type == null || tier == null) {
                    return id;
                }
                int lvl = 1;
                if (hoverName != null) {
                    if (hoverName.startsWith("[Lvl 200]")) {
                        lvl = 200;
                    } else if (hoverName.startsWith("[Lvl 100]")) {
                        lvl = 100;
                    }
                }
                return "LVL_" + lvl + "_" + tier.toUpperCase(Locale.ROOT) + "_" + type.toUpperCase(Locale.ROOT);
            }
            case "RUNE", "UNIQUE_RUNE" -> {
                CompoundTag runes = tag.getCompoundOrEmpty("runes");
                if (runes.isEmpty()) {
                    return id;
                }
                String type = runes.keySet().iterator().next();
                return type.toUpperCase(Locale.ROOT) + "_RUNE_" + runes.getIntOr(type, 1);
            }
            case "NEW_YEAR_CAKE" -> {
                int year = tag.getIntOr("new_years_cake", -1);
                return year < 0 ? id : id + "_" + year;
            }
            default -> {
            }
        }
        if (id.startsWith("PARTY_HAT") || id.startsWith("BALLOON_HAT")) {
            String extra = id.equals("PARTY_HAT_SLOTH") ? tag.getStringOr("party_hat_emoji", "")
                    : tag.getStringOr("party_hat_color", "");
            if (!extra.isEmpty()) {
                return id + "_" + extra.toUpperCase(Locale.ROOT);
            }
        }
        if (tag.getBooleanOr("is_shiny", false) && PriceData.lowestBin("SHINY_" + id) != null) {
            return "SHINY_" + id;
        }
        return id;
    }

    /** The pet's {@code petInfo} JSON, or null. */
    public static JsonObject petInfo(CompoundTag tag) {
        String raw = tag.getStringOr("petInfo", "");
        if (raw.isEmpty()) {
            return null;
        }
        try {
            JsonElement el = JsonParser.parseString(raw);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
