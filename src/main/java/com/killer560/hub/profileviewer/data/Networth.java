package com.killer560.hub.profileviewer.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.profileviewer.item.LegacyItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.nbt.CompoundTag;
import com.killer560.hub.pricetooltip.ItemKeys;
import com.killer560.hub.pricetooltip.ItemValueCalc;
import com.killer560.hub.pricetooltip.PriceData;
import com.killer560.hub.pricetooltip.PriceTooltipConfig;
import com.killer560.hub.util.ItemNbt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Networth estimate, same category split as skyblock-pv's {@code NetworthCategory}. Every item stack is valued by the
 * Price Tooltip's Est. Item Value calculator ({@link ItemValueCalc} in the Price Tooltip's Value Mode, so both show one number: base price plus enchants,
 * recombs, potato books, stars, gemstones, scrolls, parts, dye, skin, rune...) times the stack count (killer560,
 * 2026-11-04). An item the calculator has no base price for falls back to the old lookup: Bazaar price from the
 * mod's shared {@code RngMeterEngine.PRICES} cache, else the lowest BIN from {@link ExtraTables#lowestBins()}.
 * Pets of the API's pet list use the NEU-format {@code TYPE;rarity} key. A second, non-cosmetic total leaves out
 * cosmetic value (see {@link #isCosmeticItem}); both totals come from one pass.
 */
public final class Networth {

    public record Category(String name, long total, List<Map.Entry<String, Long>> top) {
    }

    /**
     * {@code total}/{@code categories} count everything; the {@code nonCosmetic} ones leave out cosmetic value.
     * {@code calcReady} is false when the Price Tooltip's price table was still empty (so items fell back to base prices).
     */
    public record Result(long total, List<Category> categories, int bazaarPrices, int binPrices, boolean museumIncluded,
                         long totalNonCosmetic, List<Category> categoriesNonCosmetic, boolean calcReady) {
        public long total(boolean nonCosmetic) { return nonCosmetic ? totalNonCosmetic : total; }

        public List<Category> categories(boolean nonCosmetic) { return nonCosmetic ? categoriesNonCosmetic : categories; }
    }

    /** One stack's value: {@code total} for the whole stack, {@code cosmetic} the part of it that is cosmetic. */
    public record ItemValue(double total, double cosmetic) { static final ItemValue ZERO = new ItemValue(0, 0); }

    /** True when a finished result should be calculated again: Bazaar prices or the item price table arrived since. */
    public static boolean stale(Result r) {
        return r.bazaarPrices() == 0 && bazaarCount() > 0 || !r.calcReady() && PriceData.lowestBinCount() > 0;
    }

    /** Accumulates one category's per-item values twice: all of it, and without cosmetics. */
    private static final class Acc {
        final String name;
        final Map<String, Long> all = new LinkedHashMap<>();
        final Map<String, Long> non = new LinkedHashMap<>();

        Acc(String name) {
            this.name = name;
        }

        void add(String label, double total, double cosmetic) {
            if (total > 0) {
                all.merge(label, (long) total, Long::sum);
            }
            if (total - cosmetic > 0) {
                non.merge(label, (long) (total - cosmetic), Long::sum);
            }
        }
    }

    private Networth() {
    }

    public static Result calculate(SbProfile p, SbProfile.Inventories inv, ProfileExtras extras, Map<String, Double> lbin,
                                   List<ItemStack> museumItems) {
        Map<String, Double> bins = lbin == null ? Map.of() : lbin;
        PriceData.touchFromOtherFeature();
        boolean ready = PriceData.lowestBinCount() > 0;
        List<Acc> cats = new ArrayList<>();
        Acc currency = new Acc("Purse / Bank");
        currency.add("Purse", p.purse, 0);
        if (p.bank > 0) {
            currency.add("Profile Bank", p.bank, 0);
        }
        if (p.personalBank > 0) {
            currency.add("Solo Bank", p.personalBank, 0);
        }
        cats.add(currency);
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
            Acc sacks = new Acc("Sacks");
            for (Map.Entry<String, Long> e : extras.misc.sacks().entrySet()) {
                double unit = price(e.getKey(), bins);
                if (unit > 0) {
                    double v = unit * e.getValue();
                    sacks.add(SbProfile.titleCase(e.getKey()), v, isCosmeticItem(e.getKey()) ? v : 0);
                }
            }
            cats.add(sacks);
            Acc essence = new Acc("Essence");
            for (Map.Entry<String, Long> e : extras.misc.essence().entrySet()) {
                double unit = price("ESSENCE_" + e.getKey().toUpperCase(Locale.ROOT), bins);
                if (unit > 0) {
                    essence.add(SbProfile.titleCase(e.getKey()) + " Essence", unit * e.getValue(), 0);
                }
            }
            cats.add(essence);
        }
        Acc pets = new Acc("Pets");
        for (SbProfile.Pet pet : p.pets) {
            // The Price Tooltip's pet value (by level, listing first, candy in Networth) so both always agree; the old
            // level-blind NEU price only while that table has no price for the pet.
            ItemValueCalc.Result r = ItemValueCalc.pet(pet.info, PriceTooltipConfig.getInstance().getValueMode());
            double v = r.total();
            double skin = 0;
            for (ItemValueCalc.Part part : r.parts()) {
                if (part.label().equals("Pet Skin")) skin += part.value();
            }
            if (v <= 0) {
                v = petPrice(pet.type, pet.tier, bins);
                if (pet.heldItem != null) {
                    v += price(pet.heldItem, bins);
                }
                if (pet.skin != null) {
                    skin = price("PET_SKIN_" + pet.skin, bins);
                    v += skin;
                }
            }
            pets.add(pet.displayName() + " (" + SbProfile.titleCase(pet.tier) + ")", v, skin);
        }
        cats.add(pets);
        if (museumItems != null) {
            cats.add(items("Museum", museumItems, bins));
        }
        List<Category> all = new ArrayList<>();
        List<Category> non = new ArrayList<>();
        long total = 0;
        long totalNon = 0;
        for (Acc c : cats) {
            Category a = category(c.name, c.all);
            Category n = category(c.name, c.non);
            all.add(a);
            non.add(n);
            total += a.total();
            totalNon += n.total();
        }
        all.sort((x, y) -> Long.compare(y.total(), x.total()));
        non.sort((x, y) -> Long.compare(y.total(), x.total()));
        return new Result(total, all, bazaarCount(), bins.size(), museumItems != null, totalNon, non, ready);
    }

    private static List<ItemStack> flatten(List<List<ItemStack>> pages) {
        List<ItemStack> out = new ArrayList<>();
        for (List<ItemStack> page : pages) {
            out.addAll(page);
        }
        return out;
    }

    private static Acc items(String name, List<ItemStack> stacks, Map<String, Double> bins) {
        Acc acc = new Acc(name);
        for (ItemStack s : stacks) {
            if (s == null || s.isEmpty()) continue;
            ItemValue v = itemValue(s, bins);
            acc.add(s.getHoverName().getString(), v.total(), v.cosmetic());
        }
        return acc;
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

    /** Part labels of {@link ItemValueCalc} that are cosmetic: dye, armor/helmet skin, rune, and a pet's skin. */
    private static final java.util.Set<String> COSMETIC_PARTS = java.util.Set.of("Dye", "Skin", "Rune", "Pet Skin");

    /**
     * Items that are only cosmetic, valued entirely as cosmetic: pet skins, dyes, runes, and armor/minion/other skins.
     * (SkyHelper-Networth's nonCosmetic flag drops the same groups - skins, dyes, runes. This list is ours, written
     * from the item ids; SkyHelper's own constants were not reachable.)
     */
    public static boolean isCosmeticItem(String id) {
        if (id == null || id.isEmpty()) return false;
        String u = id.toUpperCase(Locale.ROOT);
        return u.startsWith("PET_SKIN_") || u.startsWith("DYE_") || u.equals("RUNE") || u.equals("UNIQUE_RUNE")
                || u.startsWith("RUNE_") || u.contains("_RUNE") || u.startsWith("SKIN_") || u.contains("_SKIN");
    }

    /**
     * One stack's value: Est. Item Value (the Price Tooltip's Value Mode) per unit times the count (a pet counts once). Falls back to
     * the plain base-price lookup for whatever the calculator has no base price for.
     */
    public static ItemValue itemValue(ItemStack s, Map<String, Double> bins) {
        String id = LegacyItems.skyblockId(s);
        if (id.isEmpty()) return ItemValue.ZERO;
        boolean pet = "PET".equals(id);
        int count = pet ? 1 : Math.max(1, s.getCount());
        double unit = 0;
        double cosmetic = 0;
        boolean hasBase = false;
        try {
            CompoundTag tag = ItemNbt.view(s);
            if (tag != null && tag.contains("id")) {
                String key = ItemKeys.priceKey(tag, s.getHoverName().getString());
                if (key != null) {
                    ItemValueCalc.Result r = ItemValueCalc.compute(tag, key, PriceTooltipConfig.getInstance().getValueMode());
                    for (ItemValueCalc.Part part : r.parts()) {
                        if (part.label().startsWith("Base")) { // a pet's is "Base (Lvl N)"
                            hasBase = true;
                        } else if (COSMETIC_PARTS.contains(part.label())) {
                            cosmetic += part.value();
                        }
                    }
                    unit = r.total();
                }
            }
        } catch (Throwable ignored) {
            unit = 0;
            cosmetic = 0;
            hasBase = false;
        }
        if (!hasBase) {
            double base;
            if (pet) {
                CustomData data = s.get(DataComponents.CUSTOM_DATA);
                String info = data == null ? "" : data.copyTag().getStringOr("petInfo", "");
                try {
                    JsonObject o = JsonParser.parseString(info).getAsJsonObject();
                    base = petPrice(SbProfile.str(o, "type", ""), SbProfile.str(o, "tier", "COMMON"), bins);
                } catch (Exception e) {
                    base = 0;
                }
            } else {
                base = price(id, bins);
            }
            unit += base;
        }
        double total = unit * count;
        if (isCosmeticItem(id)) return new ItemValue(total, total);
        return new ItemValue(total, Math.min(total, cosmetic * count));
    }

    private static double petPrice(String type, String tier, Map<String, Double> bins) {
        if (type == null || type.isEmpty()) return 0;
        Double v = bins.get(type.toUpperCase(Locale.ROOT) + ";" + LevelTables.rarityIndex(tier));
        return v == null ? 0 : v;
    }

    /** Bazaar first (shared RNG-meter price cache), then lowest BIN. */
    public static double price(String id, Map<String, Double> bins) {
        if (id == null || id.isEmpty()) return 0;
        String key = id.toUpperCase(Locale.ROOT);
        try {
            Long bz = com.killer560.hub.rngmeter.RngMeterEngine.PRICES.getPrice(
                    new com.killer560.hub.rngmeter.RngItem("", "", key, com.killer560.hub.rngmeter.RngSource.BAZAAR, 0, false));
            if (bz != null && bz > 0) return bz;
        } catch (Throwable ignored) {
        }
        Double v = bins.get(key);
        return v == null ? 0 : v;
    }

    public static int bazaarCount() {
        try {
            return com.killer560.hub.rngmeter.RngMeterEngine.PRICES.getBazaarPriceCount();
        } catch (Throwable t) {
            return 0;
        }
    }
}
