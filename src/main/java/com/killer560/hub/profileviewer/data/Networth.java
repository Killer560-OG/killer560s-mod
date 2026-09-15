package com.killer560.hub.profileviewer.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.profileviewer.item.LegacyItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Networth estimate, same category split as skyblock-pv's {@code NetworthCategory}. Every item is valued
 * at its base price only (no enchant/reforge/star/gemstone upgrades): Bazaar price from the mod's shared
 * {@code RngMeterEngine.PRICES} cache (the "Price refresh finished" loop), else the lowest BIN from
 * {@link ExtraTables#lowestBins()}. Pets use the NEU-format {@code TYPE;rarity} key.
 */
public final class Networth {

    public record Category(String name, long total, List<Map.Entry<String, Long>> top) {
    }

    public record Result(long total, List<Category> categories, int bazaarPrices, int binPrices, boolean museumIncluded) {
    }

    private Networth() {
    }

    public static Result calculate(SbProfile p, SbProfile.Inventories inv, ProfileExtras extras, Map<String, Double> lbin,
                                   List<ItemStack> museumItems) {
        Map<String, Double> bins = lbin == null ? Map.of() : lbin;
        List<Category> cats = new ArrayList<>();
        Map<String, Long> currency = new LinkedHashMap<>();
        currency.put("Purse", (long) p.purse);
        if (p.bank > 0) {
            currency.put("Profile Bank", (long) p.bank);
        }
        if (p.personalBank > 0) {
            currency.put("Solo Bank", (long) p.personalBank);
        }
        cats.add(category("Purse / Bank", currency));
        if (inv != null) {
            cats.add(items("Inventory", inv.inventory(), bins));
            List<ItemStack> armor = new ArrayList<>(inv.armor());
            cats.add(items("Armor", armor, bins));
            cats.add(items("Equipment", inv.equipment(), bins));
            cats.add(items("Wardrobe", flatten(inv.wardrobe()), bins));
            cats.add(items("Ender Chest", flatten(inv.enderChest()), bins));
            List<ItemStack> bp = new ArrayList<>();
            for (SbProfile.Backpack b : inv.backpacks()) {
                bp.addAll(b.items());
            }
            cats.add(items("Backpacks", bp, bins));
            cats.add(items("Accessory Bag", flatten(inv.accessories()), bins));
            cats.add(items("Personal Vault", inv.personalVault(), bins));
            List<ItemStack> bags = new ArrayList<>(inv.fishingBag());
            bags.addAll(inv.quiver());
            bags.addAll(inv.potionBag());
            cats.add(items("Bags", bags, bins));
        }
        if (extras != null) {
            Map<String, Long> sacks = new LinkedHashMap<>();
            for (Map.Entry<String, Long> e : extras.misc.sacks().entrySet()) {
                double unit = price(e.getKey(), bins);
                if (unit > 0) {
                    sacks.merge(SbProfile.titleCase(e.getKey()), (long) (unit * e.getValue()), Long::sum);
                }
            }
            cats.add(category("Sacks", sacks));
            Map<String, Long> essence = new LinkedHashMap<>();
            for (Map.Entry<String, Long> e : extras.misc.essence().entrySet()) {
                double unit = price("ESSENCE_" + e.getKey().toUpperCase(Locale.ROOT), bins);
                if (unit > 0) {
                    essence.put(SbProfile.titleCase(e.getKey()) + " Essence", (long) (unit * e.getValue()));
                }
            }
            cats.add(category("Essence", essence));
        }
        Map<String, Long> pets = new LinkedHashMap<>();
        for (SbProfile.Pet pet : p.pets) {
            double v = petPrice(pet.type, pet.tier, bins);
            if (pet.heldItem != null) {
                v += price(pet.heldItem, bins);
            }
            if (pet.skin != null) {
                v += price("PET_SKIN_" + pet.skin, bins);
            }
            if (v > 0) {
                pets.merge(pet.displayName() + " (" + SbProfile.titleCase(pet.tier) + ")", (long) v, Long::sum);
            }
        }
        cats.add(category("Pets", pets));
        if (museumItems != null) {
            cats.add(items("Museum", museumItems, bins));
        }
        long total = 0;
        for (Category c : cats) {
            total += c.total();
        }
        cats.sort((a, b) -> Long.compare(b.total(), a.total()));
        return new Result(total, cats, bazaarCount(), bins.size(), museumItems != null);
    }

    private static List<ItemStack> flatten(List<List<ItemStack>> pages) {
        List<ItemStack> out = new ArrayList<>();
        for (List<ItemStack> page : pages) {
            out.addAll(page);
        }
        return out;
    }

    private static Category items(String name, List<ItemStack> stacks, Map<String, Double> bins) {
        Map<String, Long> values = new LinkedHashMap<>();
        for (ItemStack s : stacks) {
            if (s == null || s.isEmpty()) {
                continue;
            }
            double v = stackValue(s, bins);
            if (v > 0) {
                values.merge(s.getHoverName().getString(), (long) v, Long::sum);
            }
        }
        return category(name, values);
    }

    private static Category category(String name, Map<String, Long> values) {
        long total = 0;
        List<Map.Entry<String, Long>> list = new ArrayList<>(values.entrySet());
        for (Map.Entry<String, Long> e : list) {
            total += e.getValue();
        }
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return new Category(name, total, list.size() > 8 ? new ArrayList<>(list.subList(0, 8)) : list);
    }

    private static double stackValue(ItemStack s, Map<String, Double> bins) {
        String id = LegacyItems.skyblockId(s);
        if (id.isEmpty()) {
            return 0;
        }
        if ("PET".equals(id)) {
            CustomData data = s.get(DataComponents.CUSTOM_DATA);
            String info = data == null ? "" : data.copyTag().getStringOr("petInfo", "");
            try {
                JsonObject o = JsonParser.parseString(info).getAsJsonObject();
                return petPrice(SbProfile.str(o, "type", ""), SbProfile.str(o, "tier", "COMMON"), bins);
            } catch (Exception e) {
                return 0;
            }
        }
        return price(id, bins) * Math.max(1, s.getCount());
    }

    private static double petPrice(String type, String tier, Map<String, Double> bins) {
        if (type == null || type.isEmpty()) {
            return 0;
        }
        Double v = bins.get(type.toUpperCase(Locale.ROOT) + ";" + LevelTables.rarityIndex(tier));
        return v == null ? 0 : v;
    }

    /** Bazaar first (shared RNG-meter price cache), then lowest BIN. */
    public static double price(String id, Map<String, Double> bins) {
        if (id == null || id.isEmpty()) {
            return 0;
        }
        String key = id.toUpperCase(Locale.ROOT);
        try {
            Long bz = com.killer560.hub.rngmeter.RngMeterEngine.PRICES.getPrice(
                    new com.killer560.hub.rngmeter.RngItem("", "", key, com.killer560.hub.rngmeter.RngSource.BAZAAR, 0, false));
            if (bz != null && bz > 0) {
                return bz;
            }
        } catch (Throwable ignored) {
        }
        Double v = bins.get(key);
        return v == null ? 0 : v;
    }

    private static int bazaarCount() {
        try {
            return com.killer560.hub.rngmeter.RngMeterEngine.PRICES.getBazaarPriceCount();
        } catch (Throwable t) {
            return 0;
        }
    }
}
