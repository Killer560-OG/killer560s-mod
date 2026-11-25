package com.killer560.hub.profileviewer.screen;

import com.google.gson.JsonObject;
import com.killer560.hub.itemrarity.ItemRarityFeature;
import com.killer560.hub.profileviewer.data.ExtraTables;
import com.killer560.hub.profileviewer.data.LevelTables;
import com.killer560.hub.profileviewer.data.SbProfile;
import com.killer560.hub.profileviewer.item.LegacyItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * SkyBlockPV's "Gear" label (PvWidgets.armorAndEquipment + tools, FishingScreen's trophy armor column), chosen from every
 * item the profile holds (its getAllItems: inventory, armor, equipment, ender chest, backpacks, potion bag, accessories,
 * quiver, vault, loadout sets) by the item ids in the bundled gear table and the original's item score. Built once per
 * profile off the render thread.
 */
final class SbpvGear {

    /** Four stacks each (EMPTY where nothing matched); {@code side} is the trophy armor column or null. */
    record Gear(List<ItemStack> side, List<ItemStack> armor, List<ItemStack> equip, List<ItemStack> tools) {
    }

    private SbpvGear() {
    }

    static Gear build(SbProfile.Inventories inv, Collection<List<ItemStack>> loadoutSets, String kind, String toolKey, String sideKey) {
        return build(inv, loadoutSets, kind, toolKey, sideKey, SbpvGear::score);
    }

    /** {@code scorer} ranks the armor and equipment (FarmingScreen passes its own equipment score). */
    static Gear build(SbProfile.Inventories inv, Collection<List<ItemStack>> loadoutSets, String kind, String toolKey, String sideKey,
                      ToIntFunction<ItemStack> scorer) {
        JsonObject t = ExtraTables.bundled().getAsJsonObject("sbpv_gear");
        JsonObject g = t == null ? null : t.getAsJsonObject(kind);
        List<ItemStack> all = all(inv, loadoutSets);
        Set<String> armorIds = ids(g, "armor"), equipIds = new HashSet<>();
        String[] equipKeys = {"necklaces", "cloaks", "belts", "gloves"};
        for (String k : equipKeys) equipIds.addAll(ids(g, k));
        List<ItemStack> worn = new ArrayList<>();
        for (ItemStack st : all) {
            String id = LegacyItems.skyblockId(st);
            if (armorIds.contains(id) || equipIds.contains(id)) worn.add(st);
        }
        // sortedBy(score).reversed(): a stable ascending sort, then the whole list turned around.
        worn.sort(Comparator.comparingInt(scorer));
        Collections.reverse(worn);
        List<ItemStack> equip = new ArrayList<>(4);
        for (String k : equipKeys) equip.add(first(worn, ids(g, k)));
        List<ItemStack> top = best(all, ids(g, toolKey), 4, SbpvGear::score);
        List<ItemStack> side = null;
        if (sideKey != null) {
            Set<String> sideIds = ids(g, sideKey);
            List<ItemStack> matched = new ArrayList<>();
            for (ItemStack st : all) if (sideIds.contains(LegacyItems.skyblockId(st))) matched.add(st);
            side = armorColumn(matched);
        }
        return new Gear(side, armorColumn(worn), equip, top);
    }

    /** PvWidgets.tools: the {@code n} best-scored stacks whose id is in {@code ids}, EMPTY-padded to {@code n}. */
    private static List<ItemStack> best(List<ItemStack> all, Set<String> ids, int n, ToIntFunction<ItemStack> scorer) {
        List<ItemStack> tools = new ArrayList<>();
        for (ItemStack st : all) if (ids.contains(LegacyItems.skyblockId(st))) tools.add(st);
        tools.sort(Comparator.comparingInt(scorer).reversed());
        List<ItemStack> top = new ArrayList<>(tools.subList(0, Math.min(n, tools.size())));
        while (top.size() < n) top.add(ItemStack.EMPTY);
        return top;
    }

    /** {@code n} best stacks of the gear table's {@code kind}.{@code key} ids from everything the profile holds. */
    static List<ItemStack> best(SbProfile.Inventories inv, Collection<List<ItemStack>> loadoutSets, String kind, String key, int n) {
        return best(inv, loadoutSets, kind, key, n, SbpvGear::score);
    }

    static List<ItemStack> best(SbProfile.Inventories inv, Collection<List<ItemStack>> loadoutSets, String kind, String key, int n,
                                ToIntFunction<ItemStack> scorer) {
        JsonObject t = ExtraTables.bundled().getAsJsonObject("sbpv_gear");
        return best(all(inv, loadoutSets), ids(t == null ? null : t.getAsJsonObject(kind), key), n, scorer);
    }

    /** The best stack (by {@code scorer}) whose id is in {@code ids}, from everything the profile holds; EMPTY when none. */
    static ItemStack best(SbProfile.Inventories inv, Collection<List<ItemStack>> loadoutSets, Collection<String> ids, ToIntFunction<ItemStack> scorer) {
        return best(all(inv, loadoutSets), new HashSet<>(ids), 1, scorer).get(0);
    }

    /** The stack's rarity ordinal (COMMON 0), -1 without a rarity line. */
    static int rarity(ItemStack st) {
        String[] r = ItemRarityFeature.loreRarityLine(st);
        return r == null ? -1 : LevelTables.RARITIES.indexOf(r[0].replace(' ', '_'));
    }

    /** FarmingScreen.calculateEquipmentScore: rarity, every enchantment level, a recombobulator and a reforge. */
    static int equipScore(ItemStack st) {
        CompoundTag tag = tag(st);
        int score = Math.max(0, rarity(st));
        CompoundTag ench = tag.getCompoundOrEmpty("enchantments");
        for (String k : ench.keySet()) score += ench.getIntOr(k, 0);
        if (tag.getIntOr("rarity_upgrades", 0) > 0) score++;
        if (tag.contains("modifier")) score++;
        return score;
    }

    /** CropScreen.evaluateToolScore: the tool's level, else three per rarity step. */
    static int toolScore(ItemStack st) {
        CompoundTag tag = tag(st);
        return tag.contains("levelable_lvl") ? tag.getIntOr("levelable_lvl", 0) : rarity(st) * 3;
    }

    private static CompoundTag tag(ItemStack st) {
        CustomData data = st.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    /** How many of item {@code id} the profile holds in its inventories (sacks are added by the caller). */
    static int count(SbProfile.Inventories inv, Collection<List<ItemStack>> loadoutSets, String id) {
        int n = 0;
        for (ItemStack st : all(inv, loadoutSets)) if (id.equals(LegacyItems.skyblockId(st))) n += st.getCount();
        return n;
    }

    /** armorDisplay: the first stack whose id contains HELMET, CHESTPLATE, LEGGINGS, BOOTS. */
    private static List<ItemStack> armorColumn(List<ItemStack> list) {
        List<ItemStack> out = new ArrayList<>(4);
        for (String type : new String[] {"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"}) {
            ItemStack hit = ItemStack.EMPTY;
            for (ItemStack st : list) {
                if (LegacyItems.skyblockId(st).contains(type)) {
                    hit = st;
                    break;
                }
            }
            out.add(hit);
        }
        return out;
    }

    private static ItemStack first(List<ItemStack> list, Set<String> ids) {
        for (ItemStack st : list) if (ids.contains(LegacyItems.skyblockId(st))) return st;
        return ItemStack.EMPTY;
    }

    private static Set<String> ids(JsonObject g, String key) {
        return g == null || !g.has(key) ? Set.of() : new HashSet<>(ExtraTables.strings(g.get(key)));
    }

    private static List<ItemStack> all(SbProfile.Inventories inv, Collection<List<ItemStack>> loadoutSets) {
        List<ItemStack> out = new ArrayList<>();
        if (inv != null) {
            out.addAll(inv.inventory());
            out.addAll(inv.armor());
            out.addAll(inv.equipment());
            for (List<ItemStack> page : inv.enderChest()) out.addAll(page);
            for (SbProfile.Backpack b : inv.backpacks()) out.addAll(b.items());
            out.addAll(inv.potionBag());
            for (List<ItemStack> page : inv.accessories()) out.addAll(page);
            out.addAll(inv.quiver());
            out.addAll(inv.personalVault());
        }
        for (List<ItemStack> set : loadoutSets) out.addAll(set);
        out.removeIf(ItemStack::isEmpty);
        return out;
    }

    /** FishingScreen.calculateItemScore (MiningScreen and FarmingScreen use the same rules). */
    static int score(ItemStack st) {
        CompoundTag tag = tag(st);
        int score = tag.getIntOr("rarity_upgrades", 0) > 0 ? 1 : 0;
        CompoundTag ench = tag.getCompoundOrEmpty("enchantments");
        for (String k : ench.keySet()) {
            if (k.startsWith("ultimate")) {
                score += ench.getIntOr(k, 0);
                break;
            }
        }
        for (String k : ench.keySet()) score += Math.max(0, ench.getIntOr(k, 0) - 4);
        if (tag.contains("modifier")) score++;
        String[] rarity = ItemRarityFeature.loreRarityLine(st);
        if (rarity != null) {
            int idx = LevelTables.RARITIES.indexOf(rarity[0].replace(' ', '_'));
            score += Math.max(0, Math.min(3, (idx < 0 ? 9 : idx) - 2));
        }
        for (String part : new String[] {"hook", "line", "sinker"}) if (tag.contains(part)) score++;
        return score;
    }
}
