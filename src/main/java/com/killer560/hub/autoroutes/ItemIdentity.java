package com.killer560.hub.autoroutes;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Locale;
import java.util.Set;

/**
 * Resolves a held item to a stable identity for {@code USE_ITEM} nodes - killer560: "match on the actual item, not
 * the slot and not a UUID, so a Heroic Spirit Sceptre and a plain non-starred non-fragged Spirit Sceptre both count
 * as the same item."
 * <p>
 * First choice is the Skyblock item id in the {@code ExtraAttributes} NBT ({@code DataComponents.CUSTOM_DATA} ->
 * {@code "id"}), read the same way {@code itemprotect/ItemProtect.skyblockId}, {@code CheatUtils.skyblockId} and
 * {@code EtherwarpHopper.hotbarItem} already read it. That id already ignores reforges ({@code modifier} tag) and
 * stars ({@code upgrade_level}); fragging is the {@code STARRED_} prefix, which is stripped. Fallback for an item
 * with no id: the display name with formatting codes, star/master-star glyphs and a leading reforge word removed.
 */
public final class ItemIdentity {

    /** Every reforge name that can prefix a Skyblock item's display name (weapons, armour, tools, accessories). */
    private static final Set<String> REFORGES = Set.of(
            "ambered", "ancient", "auspicious", "awkward", "bizarre", "blessed", "blooming", "bloody", "bountiful",
            "bulky", "bustling", "candied", "chomp", "clean", "coldfused", "cubic", "deadly", "demonic", "dirty",
            "double-bit", "empowered", "epic", "fabled", "fair", "fast", "festive", "fierce", "fine", "fleet",
            "forceful", "fortified", "fruitful", "gentle", "giant", "gilded", "glistening", "godly", "grand", "great",
            "greater", "hasty", "headstrong", "heated", "heavy", "heroic", "hurtful", "hyper", "itchy", "jaded",
            "keen", "legendary", "light", "loving", "lucky", "lumberjack", "lush", "magnetic", "mithraic", "moil",
            "mossy", "mythic", "neat", "necrotic", "odd", "ominous", "peasant", "perfect", "pitchin'", "pleasant",
            "precise", "pretty", "pure", "rapid", "refined", "reinforced", "renowned", "rich", "ridiculous", "robust",
            "rooted", "rugged", "salty", "scraped", "shaded", "sharp", "shiny", "silky", "simple", "smart", "spicy",
            "spiked", "spiritual", "stellar", "stiff", "strange", "strengthened", "strong", "submerged", "superior",
            "suspicious", "sweet", "titanic", "toil", "treacherous", "unpleasant", "unreal", "vivid", "warped",
            "waxed", "wise", "withered", "zealous", "zooming");

    private ItemIdentity() {
    }

    /** @return the identity string, or null for an empty stack. */
    public static String of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String id = skyblockId(stack);
        if (id != null && !id.isBlank()) {
            id = id.trim().toUpperCase(Locale.ROOT);
            if (id.startsWith("STARRED_")) {
                id = id.substring("STARRED_".length());
            }
            return id;
        }
        return fromName(stack.getHoverName().getString());
    }

    /** Display-name fallback: strips formatting, stars, master-star pips and one leading reforge word. */
    static String fromName(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replaceAll("§.", "");
        s = s.replace("✪", "");                       // ✪ dungeon stars
        s = s.replaceAll("[➊-➎]", "");            // ➊..➎ master-star pips (RevertMasterStarsFeature)
        s = s.trim().replaceAll("\\s+", " ");
        if (s.isEmpty()) {
            return null;
        }
        int space = s.indexOf(' ');
        if (space > 0 && REFORGES.contains(s.substring(0, space).toLowerCase(Locale.ROOT))) {
            s = s.substring(space + 1).trim();
        }
        return s.isEmpty() ? null : s.toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    public static boolean matches(ItemStack stack, String identity) {
        if (identity == null) {
            return false;
        }
        String id = of(stack);
        return id != null && id.equalsIgnoreCase(identity);
    }

    /** Hotbar slot (0-8) holding an item with this identity, or -1. */
    public static int findHotbarSlot(LocalPlayer player, String identity) {
        if (player == null || identity == null) {
            return -1;
        }
        for (int slot = 0; slot <= 8; slot++) {
            if (matches(player.getInventory().getItem(slot), identity)) {
                return slot;
            }
        }
        return -1;
    }

    /** Raw Skyblock id ({@code ExtraAttributes.id}), or null - same read as {@code ItemProtect.skyblockId}. */
    public static String skyblockId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        return tag.getStringOr("id", null);
    }

    /** True for an etherwarp-capable item (AOTV/AOTE with the {@code ethermerge} tag, or an Etherwarp Conduit) -
     *  the same test {@code EtherwarpHopper.hotbarItem} makes, on one stack. */
    public static boolean isEtherwarpItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return false;
        }
        CompoundTag tag = data.copyTag();
        return tag.getIntOr("ethermerge", 0) == 1 || "ETHERWARP_CONDUIT".equals(tag.getStringOr("id", null));
    }

    /** Hotbar slot of the first etherwarp-capable item, or -1. */
    public static int findEtherwarpSlot(LocalPlayer player) {
        if (player == null) {
            return -1;
        }
        for (int slot = 0; slot <= 8; slot++) {
            if (isEtherwarpItem(player.getInventory().getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    /** Hotbar slot holding any of these Skyblock ids (QUOI {@code SwapManager.swapById} order), or -1. */
    public static int findHotbarSlotById(LocalPlayer player, String... ids) {
        if (player == null) {
            return -1;
        }
        for (String want : ids) {
            for (int slot = 0; slot <= 8; slot++) {
                String id = skyblockId(player.getInventory().getItem(slot));
                if (id != null && id.equalsIgnoreCase(want)) {
                    return slot;
                }
            }
        }
        return -1;
    }
}
