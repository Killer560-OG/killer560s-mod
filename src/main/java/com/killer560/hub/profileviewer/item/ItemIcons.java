package com.killer560.hub.profileviewer.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.fixes.ItemStackTheFlatteningFix;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Best-effort SkyBlock item id (Bukkit material names like {@code INK_SACK:3}, {@code LOG_2}, plus SkyBlock
 * ids like {@code ENCHANTED_DIAMOND}) -> a vanilla icon. Bukkit names are translated to their 1.8 item ids
 * and pushed through vanilla's own flattening fix; SkyBlock-only materials get a look-alike. Unknown ids
 * render as paper. Icons only - never used for anything but display.
 */
public final class ItemIcons {

    private static final Map<String, String> BUKKIT_TO_LEGACY = Map.ofEntries(
            Map.entry("INK_SACK", "dye"), Map.entry("RAW_FISH", "fish"), Map.entry("COOKED_FISH", "cooked_fish"),
            Map.entry("LOG_2", "log2"), Map.entry("CARROT_ITEM", "carrot"), Map.entry("POTATO_ITEM", "potato"),
            Map.entry("NETHER_STALK", "nether_wart"), Map.entry("MYCEL", "mycelium"), Map.entry("ENDER_STONE", "end_stone"),
            Map.entry("SULPHUR", "gunpowder"), Map.entry("PORK", "porkchop"), Map.entry("WATER_LILY", "waterlily"),
            Map.entry("SEEDS", "wheat_seeds"), Map.entry("SUGAR_CANE", "reeds"), Map.entry("RAW_CHICKEN", "chicken"),
            Map.entry("RAW_BEEF", "beef"), Map.entry("WOOD", "planks"), Map.entry("SNOW_BALL", "snowball"),
            Map.entry("RED_ROSE", "red_flower"), Map.entry("EXP_BOTTLE", "experience_bottle"), Map.entry("SKULL_ITEM", "skull"),
            Map.entry("NETHER_BRICK_ITEM", "netherbrick"), Map.entry("HUGE_MUSHROOM_1", "brown_mushroom_block"),
            Map.entry("HUGE_MUSHROOM_2", "red_mushroom_block"), Map.entry("WATCH", "clock"), Map.entry("FIREWORK", "fireworks"),
            Map.entry("EYE_OF_ENDER", "ender_eye"), Map.entry("SNOW_BLOCK", "snow"), Map.entry("STAINED_CLAY", "stained_hardened_clay"),
            Map.entry("HARD_CLAY", "hardened_clay"), Map.entry("SMOOTH_BRICK", "stonebrick"), Map.entry("MUSHROOM_SOUP", "mushroom_stew"),
            Map.entry("QUARTZ", "quartz"), Map.entry("DOUBLE_PLANT", "double_plant"), Map.entry("LEAVES_2", "leaves2"),
            Map.entry("SAPLING", "sapling"), Map.entry("CLAY_BALL", "clay_ball"), Map.entry("GOLD_RECORD", "record_13"),
            Map.entry("CARROT_STICK", "carrot_on_a_stick"), Map.entry("SULPHUR_ORE", "glowstone_dust"));

    private static final Map<String, Item> SKYBLOCK = Map.ofEntries(
            Map.entry("MUSHROOM_COLLECTION", Items.RED_MUSHROOM), Map.entry("GEMSTONE_COLLECTION", Items.AMETHYST_SHARD),
            Map.entry("MITHRIL_ORE", Items.PRISMARINE_CRYSTALS), Map.entry("HARD_STONE", Items.STONE),
            Map.entry("UMBER", Items.BROWN_TERRACOTTA), Map.entry("TUNGSTEN", Items.CLAY), Map.entry("GLACITE", Items.PACKED_ICE),
            Map.entry("CHILI_PEPPER", Items.RED_DYE), Map.entry("MOONFLOWER", Items.BLUE_ORCHID), Map.entry("WILD_ROSE", Items.ROSE_BUSH),
            Map.entry("SEA_LUMIES", Items.SEA_PICKLE), Map.entry("RUBY_VEILSHROOM", Items.CRIMSON_FUNGUS), Map.entry("VINESAP", Items.VINE),
            Map.entry("LUSHLILAC", Items.LILAC), Map.entry("HELIX_LOG", Items.WARPED_STEM), Map.entry("FIG_LOG", Items.STRIPPED_JUNGLE_LOG),
            Map.entry("TENDER_WOOD", Items.OAK_PLANKS), Map.entry("LOTUS", Items.PINK_PETALS), Map.entry("MAGMA_FISH", Items.COD),
            Map.entry("WILTED_BERBERIS", Items.DEAD_BUSH), Map.entry("METAL_HEART", Items.HEART_OF_THE_SEA),
            Map.entry("CADUCOUS_STEM", Items.TWISTING_VINES), Map.entry("AGARICUS_CAP", Items.RED_MUSHROOM_BLOCK),
            Map.entry("HEMOVIBE", Items.REDSTONE), Map.entry("HALF_EATEN_CARROT", Items.CARROT), Map.entry("TIMITE", Items.PURPLE_DYE),
            Map.entry("SULPHUR_ORE", Items.GLOWSTONE_DUST), Map.entry("GEMSTONE_POWDER", Items.PINK_DYE));

    private static final Map<String, ItemStack> CACHE = new ConcurrentHashMap<>();

    private ItemIcons() {
    }

    public static ItemStack forId(String id) {
        if (id == null || id.isEmpty()) {
            return new ItemStack(Items.PAPER);
        }
        if (CACHE.size() > 2048) {
            CACHE.clear();
        }
        return CACHE.computeIfAbsent(id, ItemIcons::resolve);
    }

    public static ItemStack forItem(String vanillaId) {
        Item item = lookup(vanillaId);
        return new ItemStack(item == null ? Items.PAPER : item);
    }

    private static ItemStack resolve(String rawId) {
        try {
            String id = rawId.toUpperCase(Locale.ROOT);
            boolean enchanted = false;
            if (id.startsWith("ENCHANTED_") && !SKYBLOCK.containsKey(id)) {
                id = id.substring("ENCHANTED_".length());
                enchanted = true;
                if (id.endsWith("_BLOCK") && lookup(id.toLowerCase(Locale.ROOT)) == null) {
                    id = id.substring(0, id.length() - "_BLOCK".length());
                }
            }
            Item item = SKYBLOCK.get(id);
            if (item == null) {
                String base = id;
                int damage = 0;
                int colon = id.indexOf(':');
                if (colon > 0) {
                    base = id.substring(0, colon);
                    try {
                        damage = Integer.parseInt(id.substring(colon + 1));
                    } catch (NumberFormatException ignored) {
                    }
                }
                String legacy = "minecraft:" + BUKKIT_TO_LEGACY.getOrDefault(base, base.toLowerCase(Locale.ROOT));
                String flat = ItemStackTheFlatteningFix.updateItem(legacy, damage);
                item = lookup(flat != null ? flat : legacy);
                if (item == null) {
                    item = lookup(base.toLowerCase(Locale.ROOT));
                }
            }
            ItemStack stack = new ItemStack(item == null ? Items.PAPER : item);
            if (enchanted) {
                stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            }
            return stack;
        } catch (Exception e) {
            return new ItemStack(Items.PAPER);
        }
    }

    private static Item lookup(String name) {
        if (name == null) {
            return null;
        }
        Identifier id = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
        if (id == null) {
            return null;
        }
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(id);
        return item.isPresent() && item.get() != Items.AIR ? item.get() : null;
    }
}
