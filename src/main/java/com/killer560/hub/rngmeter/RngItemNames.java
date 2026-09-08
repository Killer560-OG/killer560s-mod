package com.killer560.hub.rngmeter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Display name -> Hypixel SkyBlock internal item ID, used to price items discovered by scanning
 * an RNG Meter menu live (see {@link RngMeterOverlay#scanMenu}) instead of relying on a hand-typed
 * per-floor table. Prices are keyed by internal ID, not display name, so this mapping is still
 * needed - but unlike a pity-value table it never goes stale (an item's ID doesn't change floor to
 * floor) and is far smaller.
 *
 * <p>Seeded from {@link RngItemData}'s existing id tags (already real SkyBlock IDs, confirmed
 * against live AH data - only the pity values in that table were ever guesses), deduplicated by
 * name. Any item not in this map yet just shows as unpriceable until its name/id pair is added -
 * a much smaller failure mode than an entire item being silently absent, as {@code RngItemData}
 * was before this.
 */
public final class RngItemNames {

    public static final Map<String, String> BY_NAME = build();

    /**
     * Items discovered by live-scanning real RNG Meter menus that were never in the old
     * per-floor table at all (not just wrong pity values - genuinely absent, e.g. Necromancer
     * Sword, Fel Skull, Giant Tooth, Sadan's Brooch never had ANY entry before this). IDs
     * confirmed against Hypixel's own `/v2/resources/skyblock/items` registry (real display
     * name -> real internal ID, not guessed) except where noted.
     */
    private static Map<String, String> extra() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("Auto Recombobulator", "AUTO_RECOMBOBULATOR");
        map.put("Dark Orb", "DARK_ORB");
        map.put("Fel Skull", "FEL_SKULL");
        map.put("Giant Tooth", "GIANT_TOOTH");
        map.put("Goldor the Fish", "GOLDOR_THE_FISH");
        map.put("Maxor the Fish", "MAXOR_THE_FISH");
        map.put("Storm the Fish", "STORM_THE_FISH");
        map.put("Implosion", "IMPLOSION_SCROLL");
        map.put("Shadow Warp", "SHADOW_WARP_SCROLL");
        map.put("Wither Shield", "WITHER_SHIELD_SCROLL");
        map.put("Last Breath", "LAST_BREATH");
        map.put("Necromancer Sword", "NECROMANCER_SWORD");
        map.put("Sadan's Brooch", "SADAN_BROOCH");
        map.put("Shadow Assassin Cloak", "SHADOW_ASSASSIN_CLOAK");
        map.put("Soulweaver Gloves", "SOULWEAVER_GLOVES");
        map.put("Spirit Bone", "SPIRIT_BONE");
        map.put("Spirit Mask", "SPIRIT_MASK");
        map.put("Spirit Shortbow", "ITEM_SPIRIT_BOW");
        map.put("Summoning Ring", "SUMMONING_RING");
        map.put("Warped Stone", "AOTE_STONE");
        map.put("Wither Blood", "WITHER_BLOOD");
        map.put("Wither Catalyst", "WITHER_CATALYST");
        map.put("Wither Cloak Sword", "WITHER_CLOAK");
        map.put("Master Skull - Tier 1", "MASTER_SKULL_TIER_1");
        map.put("Master Skull - Tier 2", "MASTER_SKULL_TIER_2");
        // Not confirmed against the items registry (it doesn't list dyes/enchant books at all -
        // Bazaar is the real source for both) - built from the SAME id convention already proven
        // correct elsewhere in RngItemData (DYE_NADESHIKO, ENCHANTMENT_<NAME>_<TIER>).
        map.put("Livid Dye", "DYE_LIVID");
        map.put("Looting V", "ENCHANTMENT_LOOTING_5");
        // A pet drop, not a normal item - AH auctions for every pet type share the generic id
        // "PET" with the actual type encoded in a separate "petInfo" NBT string, so it needs the
        // synthetic "PET_<TYPE>" id HypixelMarketPrices.extractItemId() builds for pet auctions.
        map.put("[Lvl 1] Spirit", "PET_SPIRIT");
        map.put("[Lvl 1] Guardian", "PET_GUARDIAN");
        // The Experimentation Table's live display is "A Beginner's Guide to Pesthunting" - the
        // old table's name was missing the leading "A " AND had the wrong id. Real id
        // (PESTHUNTING_GUIDE) confirmed 2026-09-01 by matching killer560's screenshot's exact Bazaar
        // buy price (50,934,763-style precision match) against a fresh Bazaar fetch - a technique
        // worth reusing: when a display name search turns up nothing, and a live price is known,
        // search the Bazaar dump for a product whose current buyPrice matches it almost exactly.
        map.put("A Beginner's Guide to Pesthunting", "PESTHUNTING_GUIDE");
        // Same price-matching technique found these enchants were quietly renamed at some point -
        // the RNG Meter still shows the old display name, but the real Bazaar product kept the
        // enchant's original internal name. Only the exact tier shown in the screenshot is
        // confirmed; other tiers of these same 4 enchants would need the same treatment if they
        // ever show up unmapped.
        map.put("Drain IV", "ENCHANTMENT_SYPHON_4");
        map.put("Drain V", "ENCHANTMENT_SYPHON_5");
        map.put("Woodsplitter VI", "ENCHANTMENT_ARCANE_6");
        map.put("Gravity VI", "ENCHANTMENT_DRAGON_HUNTER_6");

        // Crystal Nucleus, Frozen Corpses, and Slayer rewards (2026-09-01 sweep) - IDs confirmed
        // against the items registry except where noted. A few are genuinely non-obvious:
        // "Blue Goblin Egg" is GOBLIN_EGG_BLUE (word order swapped), "Grizzly Salmon" is the
        // unrelated-looking GRIZZLY_BAIT, "Helix Fossil" is just HELIX, "Pickonimbus 2000" drops
        // the "2000", "Shattered Locket" is SHATTERED_PENDANT, and "Shredded Sinew" is
        // SHARD_OF_THE_SHREDDED - none of these could have been guessed from the display name.
        map.put("Aquamarine Crystal", "AQUAMARINE_CRYSTAL");
        map.put("Beheaded Horror", "BEHEADED_HORROR");
        map.put("Blue Goblin Egg", "GOBLIN_EGG_BLUE");
        map.put("Caged Wisp", "CAGED_WISP");
        map.put("Citrine Crystal", "CITRINE_CRYSTAL");
        map.put("Claw Fossil", "CLAW_FOSSIL");
        map.put("Divan Fragment", "DIVAN_FRAGMENT");
        map.put("Dwarven O's Gemstone Grahams", "DWARVEN_OS_GEMSTONE_GRAHAMS");
        map.put("Dwarven O's Metallic Minis", "DWARVEN_OS_METALLIC_MINIS");
        map.put("Festering Maggot", "FESTERING_MAGGOT");
        map.put("Frozen Scute", "FROZEN_SCUTE");
        map.put("Furball", "FURBALL");
        map.put("Gemstone Mixture", "GEMSTONE_MIXTURE");
        map.put("Glacite Amalgamation", "GLACITE_AMALGAMATION");
        map.put("Grizzly Salmon", "GRIZZLY_BAIT");
        map.put("Hamster Wheel", "HAMSTER_WHEEL");
        map.put("Handy Blood Chalice", "HANDY_BLOOD_CHALICE");
        map.put("Hazmat Enderman", "HAZMAT_ENDERMAN");
        map.put("Helix Fossil", "HELIX");
        map.put("Jaderald", "JADERALD");
        map.put("Jasper Crystal", "JASPER_CRYSTAL");
        map.put("Mithril Plate", "MITHRIL_PLATE");
        map.put("Null Atom", "NULL_ATOM");
        map.put("Onyx Crystal", "ONYX_CRYSTAL");
        map.put("Opal Crystal", "OPAL_CRYSTAL");
        map.put("Peridot Crystal", "PERIDOT_CRYSTAL");
        map.put("Pickonimbus 2000", "PICKONIMBUS");
        map.put("Pocket Espresso Machine", "POCKET_ESPRESSO_MACHINE");
        map.put("Precious Pearl", "PRECIOUS_PEARL");
        map.put("Prehistoric Egg", "PREHISTORIC_EGG");
        map.put("Primordial Eye", "PRIMORDIAL_EYE");
        map.put("Quick Claw", "PET_ITEM_QUICK_CLAW");
        map.put("Recall Potion", "RECALL_POTION");
        map.put("Refined Tungsten", "REFINED_TUNGSTEN");
        map.put("Refined Umber", "REFINED_UMBER");
        map.put("Revenant Viscera", "REVENANT_VISCERA");
        map.put("Ruby Crystal", "RUBY_CRYSTAL");
        map.put("Shattered Locket", "SHATTERED_PENDANT");
        map.put("Shredded Sinew", "SHARD_OF_THE_SHREDDED");
        map.put("Shriveled Wasp", "SHRIVELED_WASP");
        map.put("Skeleton Key", "SKELETON_KEY");
        map.put("Summoning Eye", "SUMMONING_EYE");
        map.put("Tarantula Catalyst", "TARANTULA_CATALYST");
        map.put("Tarantula Silk", "TARANTULA_SILK");
        map.put("Transmission Tuner", "TRANSMISSION_TUNER");
        map.put("Tungsten Key", "TUNGSTEN_KEY");
        map.put("Tungsten Plate", "TUNGSTEN_PLATE");
        map.put("Twilight Arrow Poison", "TWILIGHT_ARROW_POISON");
        map.put("Umber Key", "UMBER_KEY");
        map.put("Umber Plate", "UMBER_PLATE");
        map.put("Vial of Venom", "VIAL_OF_VENOM");
        map.put("Flawless Onyx Gemstone", "FLAWLESS_ONYX_GEM");
        map.put("Flawless Aquamarine Gemstone", "FLAWLESS_AQUAMARINE_GEM");
        map.put("Flawless Peridot Gemstone", "FLAWLESS_PERIDOT_GEM");
        map.put("Flawless Citrine Gemstone", "FLAWLESS_CITRINE_GEM");

        // Dyes - confirmed via live AH search (they're not in the items registry or Bazaar, same
        // as Livid Dye before them), consistent DYE_<NAME> convention.
        map.put("Brick Red Dye", "DYE_BRICK_RED");
        map.put("Byzantium Dye", "DYE_BYZANTIUM");
        map.put("Frostbitten Dye", "DYE_FROSTBITTEN");
        map.put("Matcha Dye", "DYE_MATCHA");
        // Not directly confirmed (no current AH listing found either) - same DYE_<NAME> convention
        // as the 4 above, which held for every dye checked so far.
        map.put("Celeste Dye", "DYE_CELESTE");
        map.put("Jade Dye", "DYE_JADE");

        // Slayer "monster shard" drops - real Bazaar ids are SHARD_<NAME>, word order reversed
        // from the display name (confirmed directly: SHARD_PARAGON, SHARD_REVENANT,
        // SHARD_PRIMORDIAL are real Bazaar products - the earlier <NAME>_SHARD guess didn't
        // exist under any name).
        map.put("Paragon Shard", "SHARD_PARAGON");
        map.put("Revenant Shard", "SHARD_REVENANT");
        map.put("Primordial Shard", "SHARD_PRIMORDIAL");
        return map;
    }

    private RngItemNames() {
    }

    private static Map<String, String> build() {
        Map<String, String> map = new LinkedHashMap<>();
        for (var category : RngItemData.ALL_CATEGORIES) {
            for (RngItem item : category) {
                map.putIfAbsent(item.name(), item.id());
            }
        }
        map.putAll(extra());
        return map;
    }
}
