package com.killer560.hub.autoroutes;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Locale;
import java.util.Map;
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

    /**
     * Items a {@code USE_ITEM} node treats as the same item, id to family key.
     *
     * <p>killer560 (2026-09-28): "if I set up a used item node with a Hyperion it should work with any of the
     * other wither blade variant [...] whether they are starred or not starred [...] and if they're recombed or
     * not." Stars and reforges were already handled - neither changes the Skyblock id, and the {@code STARRED_}
     * prefix is stripped above. Recombobulating changes an item's RARITY, not its id and not its name text, so it
     * was never a factor. The FOUR WITHER BLADES were the real gap: they are four separate ids, so a node
     * recorded with a Hyperion simply never matched an Astraea.
     *
     * <p>Every id here was read from Hypixel's own item list (api.hypixel.net item resource, 2026-09-28) rather
     * than from memory. That check found {@code ClearNode}'s teleport list had been carrying "ASTREA" - one
     * letter short of {@code ASTRAEA} - so that entry had never matched anything.
     *
     * <p><b>What is deliberately NOT in here.</b> Aspect of the End and Aspect of the Void look like a family and
     * are not one: AOTE teleports 8 blocks and AOTV 12, so a route recorded with one lands short or long with the
     * other. Grouping them would turn "the item is missing" - which stops the route with a message - into a route
     * that runs and quietly walks off the path. Necron's Blade (Unrefined) is left out for the same kind of
     * reason: it is the base item and has no Wither Impact to use.
     */
    private static final Map<String, String> FAMILIES = Map.ofEntries(
            // The four wither blades: same ability, same teleport, interchangeable for a route.
            Map.entry("HYPERION", "WITHER_BLADE"),
            Map.entry("ASTRAEA", "WITHER_BLADE"),
            Map.entry("SCYLLA", "WITHER_BLADE"),
            Map.entry("VALKYRIE", "WITHER_BLADE"),
            // Infinileap is a Spirit Leap that is not consumed - same use, same effect.
            Map.entry("SPIRIT_LEAP", "SPIRIT_LEAP"),
            Map.entry("INFINITE_SPIRIT_LEAP", "SPIRIT_LEAP"),
            // Infinityboom TNT is the same for Superboom.
            Map.entry("SUPERBOOM_TNT", "SUPERBOOM"),
            Map.entry("INFINITE_SUPERBOOM_TNT", "SUPERBOOM"));

    private ItemIdentity() {
    }

    /**
     * The family key for an identity, or the identity itself when it is in no family.
     *
     * <p>Takes the same {@code STARRED_} strip {@link #of} does, because this is also handed identities that came
     * out of a SAVED ROUTE - a node recorded before that strip existed can still be holding {@code STARRED_}.
     */
    public static String family(String identity) {
        if (identity == null) {
            return null;
        }
        String key = identity.trim().toUpperCase(Locale.ROOT);
        if (key.startsWith("STARRED_")) {
            key = key.substring("STARRED_".length());
        }
        return FAMILIES.getOrDefault(key, key);
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

    /**
     * Whether a stack satisfies a node's recorded item.
     *
     * <p>The family widening lives HERE and not in {@link #of}, which looks like the tidier place and is the wrong
     * one. {@code of} is also what Auto Sell, the Inventory Sorter, Armour Dye and the mining profit tracker key
     * on, and collapsing the wither blades there would make "sell my Hyperion" sell an Astraea and make the
     * sorter treat two different swords as one. Only a route's USE_ITEM node wants the loose match, and
     * {@code matches} is only reached from {@link #findHotbarSlot}, so this is the whole blast radius.
     */
    public static boolean matches(ItemStack stack, String identity) {
        if (identity == null) {
            return false;
        }
        String id = of(stack);
        if (id == null) {
            return false;
        }
        if (id.equalsIgnoreCase(identity)) {
            return true;
        }
        String a = family(id);
        String b = family(identity);
        return a != null && a.equals(b);
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
