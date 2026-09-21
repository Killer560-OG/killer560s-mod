package com.killer560.hub.itembrowser;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Builds a real, representative {@link ItemStack} for a {@link SkyblockItemEntry} so the item browser
 * panel can render the exact real Hypixel visual wherever possible:
 * <ul>
 *   <li>A real {@code skin} (player-head cosmetics - by far the most common real case, ~2800 of the
 *   real 5,651 items) gets a real {@link ResolvableProfile} built from the item's own real Mojang
 *   texture blob, set via the real {@code minecraft:profile} component - the exact same real mechanism
 *   a real signed custom head uses, so it renders with the item's own real actual skin.</li>
 *   <li>A real {@code item_model} (Hypixel's own modern per-item model override) is set via the real
 *   {@code minecraft:item_model} component - renders the exact real Hypixel model, using whatever real
 *   base item the entry's own material resolves to, as long as Hypixel's own resource pack is loaded
 *   (i.e. while actually connected to Hypixel, same as any other real Hypixel item icon).</li>
 *   <li>Otherwise, falls back to a real plain vanilla item resolved from the entry's own real
 *   {@code material} field (a legacy Bukkit-style id) via {@link #LEGACY_MATERIAL_REMAP} for the common
 *   real mismatches between that legacy naming and this version's real registry ids, or a direct
 *   lowercase lookup otherwise, or real {@code minecraft:paper} if nothing resolves.</li>
 * </ul>
 */
public final class SkyblockItemStackFactory {

    /** Real legacy Bukkit {@code Material} enum names (what Hypixel's own item resource still reports)
     *  that don't match this version's real registry id directly - not exhaustive, just the real names
     *  actually observed across the live catalog's most common materials. Anything not listed here is
     *  tried as a direct lowercase registry lookup first. */
    private static final Map<String, String> LEGACY_MATERIAL_REMAP = Map.ofEntries(
            Map.entry("GOLD_SWORD", "golden_sword"),
            Map.entry("GOLD_AXE", "golden_axe"),
            Map.entry("GOLD_PICKAXE", "golden_pickaxe"),
            Map.entry("GOLD_HOE", "golden_hoe"),
            Map.entry("GOLD_SPADE", "golden_shovel"),
            Map.entry("GOLD_BOOTS", "golden_boots"),
            Map.entry("GOLD_CHESTPLATE", "golden_chestplate"),
            Map.entry("GOLD_LEGGINGS", "golden_leggings"),
            Map.entry("GOLD_HELMET", "golden_helmet"),
            Map.entry("WOOD_SWORD", "wooden_sword"),
            Map.entry("WOOD_AXE", "wooden_axe"),
            Map.entry("WOOD_PICKAXE", "wooden_pickaxe"),
            Map.entry("WOOD_HOE", "wooden_hoe"),
            Map.entry("WOOD_SPADE", "wooden_shovel"),
            Map.entry("EMPTY_MAP", "map"),
            Map.entry("MAP", "filled_map"),
            Map.entry("RAW_FISH", "cod"),
            Map.entry("COOKED_FISH", "cooked_cod"),
            Map.entry("INK_SACK", "black_dye"),
            Map.entry("SULPHUR", "gunpowder"),
            Map.entry("WATCH", "clock"),
            Map.entry("BOAT", "oak_boat"),
            Map.entry("GRILLED_PORK", "cooked_porkchop"),
            Map.entry("PORK", "porkchop"),
            Map.entry("HUGE_MUSHROOM_1", "red_mushroom_block"),
            Map.entry("HUGE_MUSHROOM_2", "brown_mushroom_block"),
            Map.entry("STORAGE_MINECART", "chest_minecart"),
            Map.entry("EXPLOSIVE_MINECART", "tnt_minecart"),
            Map.entry("SNOW_BALL", "snowball"),
            Map.entry("GLASS_BOTTLE", "glass_bottle"),
            Map.entry("SPECKLED_MELON", "glistering_melon_slice"),
            Map.entry("MELON", "melon_slice"),
            Map.entry("MAGMA_CREAM", "magma_cream"),
            Map.entry("EYE_OF_ENDER", "ender_eye"),
            Map.entry("BREWING_STAND_ITEM", "brewing_stand"),
            Map.entry("CAULDRON_ITEM", "cauldron"),
            Map.entry("FIREWORK", "firework_rocket"),
            Map.entry("FIREWORK_CHARGE", "firework_star"),
            Map.entry("NETHER_STALK", "nether_wart"),
            Map.entry("SUGAR_CANE", "sugar_cane"),
            Map.entry("CARROT_STICK", "carrot_on_a_stick"),
            Map.entry("QUARTZ", "quartz"),
            Map.entry("IRON_FENCE", "iron_bars"),
            Map.entry("THIN_GLASS", "glass_pane"),
            Map.entry("WOOD_STEP", "oak_slab"),
            Map.entry("STEP", "stone_slab"),
            Map.entry("BURNING_FURNACE", "furnace")
    );

    private SkyblockItemStackFactory() {
    }

    public static ItemStack build(SkyblockItemEntry entry) {
        if (entry.skinValue() != null) {
            ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
            Multimap<String, Property> backing = HashMultimap.create();
            backing.put("textures", new Property("textures", entry.skinValue(), entry.skinSignature()));
            PropertyMap properties = new PropertyMap(backing);
            GameProfile profile = new GameProfile(UUID.randomUUID(), "SkyblockItem", properties);
            stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile));
            return stack;
        }

        Item base = resolveMaterialItem(entry.material());
        ItemStack stack = new ItemStack(base);

        if (entry.itemModel() != null) {
            Identifier modelId = Identifier.tryParse(entry.itemModel());
            if (modelId != null) {
                stack.set(DataComponents.ITEM_MODEL, modelId);
            }
        }
        return stack;
    }

    private static Item resolveMaterialItem(String material) {
        if ("SKULL_ITEM".equals(material) || "SKELETON_SKULL_ITEM".equals(material)) {
            return Items.PLAYER_HEAD;
        }
        String remapped = LEGACY_MATERIAL_REMAP.get(material);
        String path = remapped != null ? remapped : material.toLowerCase(Locale.ROOT);
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(path));
        return item != Items.AIR ? item : Items.PAPER;
    }

    /** Real, standard Hypixel Skyblock rarity color for a real {@code tier} value (shared by
     *  {@link ItemBrowserFeature}'s hover tooltip and {@link ItemCraftView}'s popup so both agree). */
    public static String tierColorCode(String tier) {
        if (tier == null) {
            return "§f";
        }
        return switch (tier.toUpperCase(Locale.ROOT)) {
            case "COMMON" -> "§f";
            case "UNCOMMON" -> "§a";
            case "RARE" -> "§9";
            case "EPIC" -> "§5";
            case "LEGENDARY" -> "§6";
            case "MYTHIC" -> "§d";
            case "DIVINE" -> "§b";
            case "SPECIAL", "VERY_SPECIAL" -> "§c";
            case "ADMIN" -> "§4";
            default -> "§f";
        };
    }

    /** {@code REFORGE_STONE} -> {@code Reforge Stone}. */
    public static String niceCategory(String category) {
        if (category == null || category.isBlank()) {
            return "";
        }
        String[] words = category.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}
