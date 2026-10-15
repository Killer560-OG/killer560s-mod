package com.killer560.hub.pricetooltip;

import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Est. Item Value: the item's own price plus what was applied to it, each part priced from {@link PriceData}.
 *
 * <p>The parts and field names follow SkyHanni's EstimatedItemValueCalculator and the SkyHelper networth constants
 * (research 2026-10-15; reimplemented, not copied), with every field name checked against real auction NBT that day:
 * base price, Recombobulator ({@code rarity_upgrades}), Hot/Fuming Potato Books ({@code hot_potato_count}, the first 10
 * hot), enchantments (the Bazaar's {@code ENCHANTMENT_<NAME>_<LEVEL>}), dungeon stars ({@code upgrade_level} or the
 * older {@code dungeon_item_level}, essence and items per star from the items resource's {@code upgrade_costs}) and
 * master stars above five, gemstones ({@code gems}: {@code <QUALITY>_<TYPE>_GEM}, the type of a COMBAT/UNIVERSAL...
 * slot in {@code <slot>_gem}) and the cost of each unlocked slot, the Art of War/Peace, Wood Singularity, Farming
 * for Dummies, Polarvoid, Jalapeno, Mana Disintegrator, Transmission Tuner, Wet Book, Book of Stats, Etherwarp
 * merge, ability scrolls, power scroll, enrichment, boosters, drill and rod parts, dye, helmet skin, rune, pet held item
 * and pet skin. Reforges are NOT counted: nothing keyless maps a reforge to its stone.
 *
 * <p>{@link PriceTooltipConfig.ValueMode#NETWORTH} weighs each part by SkyHelper's application worth (what Skyblocker's
 * "Est. Item Value" shows); {@link PriceTooltipConfig.ValueMode#FULL} counts everything at full price (SkyHanni).
 */
public final class ItemValueCalc {

    private ItemValueCalc() {
    }

    /** One line of the breakdown. */
    public record Part(String label, double value) {
    }

    public record Result(double total, List<Part> parts) {
    }

    private static final String[] MASTER_STARS = {"FIRST_MASTER_STAR", "SECOND_MASTER_STAR", "THIRD_MASTER_STAR",
            "FOURTH_MASTER_STAR", "FIFTH_MASTER_STAR"};

    /** Slot names whose gem type is stored separately in {@code <slot>_gem}. */
    private static final Set<String> GENERIC_SLOTS = Set.of("COMBAT", "OFFENSIVE", "DEFENSIVE", "UNIVERSAL", "MINING",
            "CHISEL");

    /** SkyHelper's per-enchant weight on top of the enchantment weight. */
    private static final Map<String, Double> ENCHANT_WEIGHT = Map.of(
            "COUNTER_STRIKE", 0.2, "BIG_BRAIN", 0.35, "ULTIMATE_INFERNO", 0.35, "OVERLOAD", 0.35,
            "ULTIMATE_SOUL_EATER", 0.35, "ULTIMATE_FATAL_TEMPO", 0.65);

    /** Counted items: NBT key, item id, SkyHelper weight. */
    private record Counted(String key, String itemId, double weight, String label) {
    }

    private static final List<Counted> COUNTED = List.of(
            new Counted("art_of_war_count", "THE_ART_OF_WAR", 0.6, "Art of War"),
            new Counted("wood_singularity_count", "WOOD_SINGULARITY", 0.5, "Wood Singularity"),
            new Counted("farming_for_dummies_count", "FARMING_FOR_DUMMIES", 0.5, "Farming for Dummies"),
            new Counted("polarvoid", "POLARVOID_BOOK", 1.0, "Polarvoid Books"),
            new Counted("jalapeno_count", "JALAPENO_BOOK", 0.8, "Jalapeno Book"),
            new Counted("mana_disintegrator_count", "MANA_DISINTEGRATOR", 0.8, "Mana Disintegrators"),
            new Counted("tuned_transmission", "TRANSMISSION_TUNER", 0.7, "Transmission Tuners"),
            new Counted("wet_book_count", "WET_BOOK", 1.0, "Wet Books"));

    public static Result compute(CompoundTag tag, String priceKey, PriceTooltipConfig.ValueMode mode) {
        boolean nw = mode == PriceTooltipConfig.ValueMode.NETWORTH;
        List<Part> parts = new ArrayList<>();
        String id = ItemKeys.id(tag);
        if (id == null) {
            return new Result(0, parts);
        }

        double base = PriceData.unitPrice(priceKey);
        if (base <= 0 && !priceKey.equals(id)) {
            base = PriceData.unitPrice(id);
        }
        if (base > 0) {
            parts.add(new Part("Base", base));
        }
        if ("ENCHANTED_BOOK".equals(id)) {
            return finish(parts);
        }

        if (tag.getIntOr("rarity_upgrades", 0) > 0) {
            add(parts, "Recombobulator", PriceData.unitPrice("RECOMBOBULATOR_3000") * (nw ? 0.8 : 1));
        }

        int hpb = tag.getIntOr("hot_potato_count", 0);
        if (hpb > 0) {
            int hot = Math.min(10, hpb);
            int fuming = Math.max(0, hpb - 10);
            add(parts, "Hot Potato Books (" + hot + ")", hot * PriceData.unitPrice("HOT_POTATO_BOOK"));
            if (fuming > 0) {
                add(parts, "Fuming Potato Books (" + fuming + ")",
                        fuming * PriceData.unitPrice("FUMING_POTATO_BOOK") * (nw ? 0.6 : 1));
            }
        }

        CompoundTag ench = tag.getCompoundOrEmpty("enchantments");
        if (!ench.isEmpty()) {
            double sum = 0;
            for (String name : ench.keySet()) {
                String upper = name.toUpperCase(Locale.ROOT);
                if (upper.equals("EFFICIENCY")) {
                    continue; // Applied with Silex/innate on tools, never a book worth counting.
                }
                double p = PriceData.unitPrice("ENCHANTMENT_" + upper + "_" + ench.getIntOr(name, 1));
                if (nw) {
                    p *= 0.85 * ENCHANT_WEIGHT.getOrDefault(upper, 1.0);
                }
                sum += p;
            }
            add(parts, "Enchantments (" + ench.size() + ")", sum);
        }

        stars(tag, id, nw, parts);
        gems(tag, id, nw, parts);

        for (Counted c : COUNTED) {
            int n = tag.getIntOr(c.key(), 0);
            if (n > 0) {
                add(parts, c.label() + (n > 1 ? " (" + n + ")" : ""), n * PriceData.unitPrice(c.itemId()) * (nw ? c.weight() : 1));
            }
        }
        if (tag.getIntOr("artOfPeaceApplied", 0) > 0) {
            add(parts, "Art of Peace", PriceData.unitPrice("THE_ART_OF_PEACE") * (nw ? 0.8 : 1));
        }
        if (tag.contains("stats_book")) {
            add(parts, "Book of Stats", PriceData.unitPrice("BOOK_OF_STATS"));
        }
        if (tag.getIntOr("ethermerge", 0) > 0) {
            add(parts, "Etherwarp", PriceData.unitPrice("ETHERWARP_CONDUIT") + PriceData.unitPrice("ETHERWARP_MERGER"));
        }
        if (tag.getIntOr("divan_powder_coating", 0) > 0) {
            add(parts, "Divan Powder Coating", PriceData.unitPrice("DIVAN_POWDER_COATING") * (nw ? 0.8 : 1));
        }

        ListTag scrolls = tag.getListOrEmpty("ability_scroll");
        if (scrolls.size() > 0) {
            double sum = 0;
            for (int i = 0; i < scrolls.size(); i++) {
                sum += PriceData.unitPrice(scrolls.getStringOr(i, ""));
            }
            add(parts, "Ability Scrolls (" + scrolls.size() + ")", sum);
        }
        String power = tag.getStringOr("power_ability_scroll", "");
        if (!power.isEmpty()) {
            add(parts, "Power Scroll", PriceData.unitPrice(power) * (nw ? 0.5 : 1));
        }
        String enrichment = tag.getStringOr("talisman_enrichment", "");
        if (!enrichment.isEmpty()) {
            add(parts, "Enrichment", PriceData.unitPrice("TALISMAN_ENRICHMENT_" + enrichment.toUpperCase(Locale.ROOT)) * (nw ? 0.5 : 1));
        }
        ListTag boosters = tag.getListOrEmpty("boosters");
        if (boosters.size() > 0) {
            double sum = 0;
            for (int i = 0; i < boosters.size(); i++) {
                sum += PriceData.unitPrice(boosters.getStringOr(i, "").toUpperCase(Locale.ROOT) + "_BOOSTER");
            }
            add(parts, "Boosters", sum * (nw ? 0.8 : 1));
        }

        double partsSum = 0;
        for (String key : new String[]{"drill_part_engine", "drill_part_fuel_tank", "drill_part_upgrade_module"}) {
            String part = tag.getStringOr(key, "");
            if (!part.isEmpty()) {
                partsSum += PriceData.unitPrice(part.toUpperCase(Locale.ROOT));
            }
        }
        for (String key : new String[]{"line", "hook", "sinker"}) {
            String part = tag.getCompoundOrEmpty(key).getStringOr("part", "");
            if (!part.isEmpty()) {
                partsSum += PriceData.unitPrice(part.toUpperCase(Locale.ROOT));
            }
        }
        add(parts, "Parts", partsSum);

        String dye = tag.getStringOr("dye_item", "");
        if (!dye.isEmpty()) {
            add(parts, "Dye", PriceData.unitPrice(dye) * (nw ? 0.9 : 1));
        }
        String skin = tag.getStringOr("skin", "");
        if (!skin.isEmpty() && !"PET".equals(id)) {
            add(parts, "Skin", PriceData.unitPrice(skin));
        }
        CompoundTag runes = tag.getCompoundOrEmpty("runes");
        if (!runes.isEmpty() && !"RUNE".equals(id) && !"UNIQUE_RUNE".equals(id)) {
            String type = runes.keySet().iterator().next();
            add(parts, "Rune", PriceData.unitPrice(type.toUpperCase(Locale.ROOT) + "_RUNE_" + runes.getIntOr(type, 1)) * (nw ? 0.6 : 1));
        }

        if ("PET".equals(id)) {
            JsonObject info = ItemKeys.petInfo(tag);
            if (info != null) {
                String held = ItemKeys.str(info, "heldItem");
                if (held != null) {
                    add(parts, "Pet Item", PriceData.unitPrice(held));
                }
                String petSkin = ItemKeys.str(info, "skin");
                if (petSkin != null) {
                    add(parts, "Pet Skin", PriceData.unitPrice("PET_SKIN_" + petSkin));
                }
            }
        }
        return finish(parts);
    }

    private static void stars(CompoundTag tag, String id, boolean nw, List<Part> parts) {
        int level = tag.getIntOr("upgrade_level", 0);
        if (level <= 0) {
            level = tag.getIntOr("dungeon_item_level", 0);
        }
        if (level <= 0) {
            return;
        }
        PriceData.ItemInfo info = PriceData.item(id);
        if (info == null && id.startsWith("STARRED_")) {
            info = PriceData.item(id.substring("STARRED_".length()));
        }
        List<List<PriceData.Cost>> costs = info == null ? List.of() : info.upgradeCosts();
        Map<String, Integer> essence = new LinkedHashMap<>();
        double itemCost = 0;
        for (int i = 0; i < Math.min(level, costs.size()); i++) {
            for (PriceData.Cost c : costs.get(i)) {
                if (c.itemId() == null) {
                    itemCost += c.coins();
                } else if (c.itemId().startsWith("ESSENCE_")) {
                    essence.merge(c.itemId(), c.amount(), Integer::sum);
                } else {
                    itemCost += c.amount() * PriceData.unitPrice(c.itemId());
                }
            }
        }
        for (Map.Entry<String, Integer> e : essence.entrySet()) {
            String name = title(e.getKey().substring("ESSENCE_".length())) + " Essence (" + e.getValue() + ")";
            add(parts, name, e.getValue() * PriceData.unitPrice(e.getKey()) * (nw ? 0.75 : 1));
        }
        add(parts, "Star Items", itemCost);
        int masters = Math.max(0, Math.min(5, level - Math.max(5, costs.size())));
        if (masters > 0) {
            double sum = 0;
            for (int i = 0; i < masters; i++) {
                sum += PriceData.unitPrice(MASTER_STARS[i]);
            }
            add(parts, "Master Stars (" + masters + ")", sum);
        }
    }

    private static void gems(CompoundTag tag, String id, boolean nw, List<Part> parts) {
        CompoundTag gems = tag.getCompoundOrEmpty("gems");
        if (gems.isEmpty()) {
            return;
        }
        double gemSum = 0;
        int gemCount = 0;
        for (String slot : gems.keySet()) {
            if (slot.endsWith("_gem") || slot.equals("unlocked_slots") || slot.equals("formatted")) {
                continue;
            }
            Tag v = gems.get(slot);
            String quality = null;
            if (v instanceof StringTag s) {
                quality = s.value();
            } else if (v instanceof CompoundTag c) {
                quality = c.getStringOr("quality", null);
            }
            if (quality == null || quality.isEmpty()) {
                continue;
            }
            int us = slot.lastIndexOf('_');
            String slotType = us > 0 ? slot.substring(0, us) : slot;
            String gemType = GENERIC_SLOTS.contains(slotType) ? gems.getStringOr(slot + "_gem", "") : slotType;
            if (gemType.isEmpty()) {
                continue;
            }
            gemSum += PriceData.unitPrice(quality.toUpperCase(Locale.ROOT) + "_" + gemType.toUpperCase(Locale.ROOT) + "_GEM");
            gemCount++;
        }
        add(parts, "Gemstones (" + gemCount + ")", gemSum);

        ListTag unlocked = gems.getListOrEmpty("unlocked_slots");
        PriceData.ItemInfo info = PriceData.item(id);
        if (unlocked.size() == 0 || info == null || info.gemSlots().isEmpty()) {
            return;
        }
        double unlockSum = 0;
        for (int i = 0; i < unlocked.size(); i++) {
            String slot = unlocked.getStringOr(i, "");
            int us = slot.lastIndexOf('_');
            if (us <= 0) {
                continue;
            }
            String type = slot.substring(0, us);
            int index;
            try {
                index = Integer.parseInt(slot.substring(us + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            int seen = 0;
            for (PriceData.GemSlot gs : info.gemSlots()) {
                if (type.equals(gs.type())) {
                    if (seen++ == index) {
                        for (PriceData.Cost c : gs.costs()) {
                            unlockSum += c.itemId() == null ? c.coins() : c.amount() * PriceData.unitPrice(c.itemId());
                        }
                        break;
                    }
                }
            }
        }
        add(parts, "Gemstone Slots (" + unlocked.size() + ")", unlockSum * (nw ? 0.6 : 1));
    }

    private static void add(List<Part> parts, String label, double value) {
        if (value > 0) {
            parts.add(new Part(label, value));
        }
    }

    private static Result finish(List<Part> parts) {
        double total = 0;
        for (Part p : parts) {
            total += p.value();
        }
        return new Result(total, List.copyOf(parts));
    }

    private static String title(String upper) {
        String s = upper.toLowerCase(Locale.ROOT);
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
