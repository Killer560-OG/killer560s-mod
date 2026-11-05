package com.killer560.hub.reforge;

import com.killer560.hub.itemrarity.ItemRarityFeature;
import com.killer560.hub.util.ItemNbt;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Set;

/**
 * The Blacksmith's reforge menu as SkyHanni's {@code ReforgeHelper} reads it: a chest titled "Reforge Item" with the item
 * in slot 13 and the Reforge button in slot 22. Not checked in game (none of his logs has the menu, and the wikis were
 * unreachable on 2026-11-04). An item's reforge is its {@code modifier} in Hypixel's custom data, or the first word of
 * its name when that is missing (a reforged item is named "Fabled Livid Dagger").
 */
public final class ReforgeMenu {

    public static final String TITLE = "Reforge Item";
    public static final int ITEM_SLOT = 13;
    public static final int BUTTON_SLOT = 22;

    private ReforgeMenu() {
    }

    public static boolean isTitle(String title) {
        return TITLE.equals(title);
    }

    /** The item being reforged, or an empty stack. */
    public static ItemStack item(AbstractContainerMenu menu) {
        return menu.slots.size() > ITEM_SLOT ? menu.slots.get(ITEM_SLOT).getItem() : ItemStack.EMPTY;
    }

    /** The item's reforge in lower case ("fabled"), or "" when it has none that can be read. */
    public static String reforge(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        CompoundTag tag = ItemNbt.view(stack);
        if (tag != null) {
            String m = tag.getStringOr("modifier", "");
            if (!m.isEmpty()) {
                return m.toLowerCase(Locale.ROOT);
            }
        }
        String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
        if (name == null) {
            return "";
        }
        name = name.trim();
        // A fragged item's name starts with a star mark ("⚚ Fabled Livid Dagger").
        while (!name.isEmpty() && !Character.isLetter(name.charAt(0))) {
            name = name.substring(1).trim();
        }
        int space = name.indexOf(' ');
        return space <= 0 ? "" : name.substring(0, space).toLowerCase(Locale.ROOT);
    }

    /**
     * {rarity, item type} from the item's rarity line: "a LEGENDARY DUNGEON SWORD a" -> {"LEGENDARY", "SWORD"}, "MYTHIC
     * FISHING ROD" -> {"MYTHIC", "FISHING_ROD"}; null when there is no rarity line.
     */
    static String[] rarityAndType(ItemStack stack) {
        String[] line = ItemRarityFeature.loreRarityLine(stack);
        if (line == null) {
            return null;
        }
        StringBuilder type = new StringBuilder();
        for (String w : line[1].split(" ")) {
            if (w.length() > 1 && !w.equals("DUNGEON")) { // one-letter words are the recombobulator's obfuscated marks
                type.append(type.isEmpty() ? "" : "_").append(w);
            }
        }
        String rarity = line[0].replace(' ', '_');
        return new String[]{rarity.equals("SUPREME") ? "ULTIMATE" : rarity, type.toString()};
    }

    /** Whether {@code stack} already has one of {@code wanted} (keys from {@link ReforgeHelperConfig#wantedKeys}). */
    public static boolean hasWanted(ItemStack stack, Set<String> wanted) {
        String r = reforge(stack);
        if (r.isEmpty() || wanted.isEmpty()) {
            return false;
        }
        if (wanted.contains(r)) {
            return true;
        }
        ReforgeTable.Reforge known = ReforgeTable.byKey(r);
        return known != null && (wanted.contains(known.modifier()) || wanted.contains(ReforgeHelperConfig.key(known.name())));
    }
}
