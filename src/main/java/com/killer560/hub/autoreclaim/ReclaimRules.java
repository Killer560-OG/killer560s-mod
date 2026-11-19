package com.killer560.hub.autoreclaim;

import com.killer560.hub.itemrarity.ItemRarityFeature;
import com.killer560.hub.util.ItemNbt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * What Hypixel's /reclaim (SkyBlock 0.27.3) gives something back for, from the patch text only: a recombobulated
 * SPECIAL or VERY SPECIAL accessory (Recombobulator), an item with the old Divine Gift enchantment, an item with the old
 * Chance or Luck enchantment (books included). The patch names no item types for the enchantments, so any item matches.
 * The answer is read once per stack (weak identity cache, render thread only) and dropped on world change.
 */
public final class ReclaimRules {

    private static final Map<ItemStack, String> CACHE = new WeakHashMap<>();

    private ReclaimRules() {
    }

    /** What /reclaim returns for {@code stack} ("Recombobulator", "Divine Gift", "Chance", "Luck", joined by ", "), or null. */
    public static String reason(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String r = CACHE.computeIfAbsent(stack, ReclaimRules::compute);
        return r.isEmpty() ? null : r;
    }

    public static void clear() {
        CACHE.clear();
    }

    private static String compute(ItemStack stack) {
        CompoundTag tag = ItemNbt.view(stack);
        if (tag == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (tag.getIntOr("rarity_upgrades", 0) > 0) {
            String[] line = ItemRarityFeature.loreRarityLine(stack);
            if (line != null && (line[0].equals("SPECIAL") || line[0].equals("VERY SPECIAL"))
                    && line[1].contains("CESSORY")) { // ACCESSORY and HATCCESSORY
                sb.append("Recombobulator");
            }
        }
        CompoundTag ench = tag.getCompoundOrEmpty("enchantments");
        add(sb, ench, "divine_gift", "Divine Gift");
        add(sb, ench, "chance", "Chance");
        add(sb, ench, "luck", "Luck");
        return sb.toString();
    }

    private static void add(StringBuilder sb, CompoundTag ench, String id, String label) {
        if (ench.contains(id)) {
            sb.append(sb.length() > 0 ? ", " : "").append(label);
        }
    }
}
