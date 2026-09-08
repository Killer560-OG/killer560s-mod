package com.killer560.hub.rngmeter;

import java.util.List;

import static com.killer560.hub.rngmeter.RngSource.AH;
import static com.killer560.hub.rngmeter.RngSource.BAZAAR;

/**
 * RNG-meter-eligible item data: which items can be pitied at each floor/area, and how much meter
 * "fuel" each one needs. Ported from the standalone "RNG Meter" spreadsheet tool
 * (OneDrive\Desktop\RNG Meter\code\data\rng_items.js) - sourced from community math threads and
 * in-game data, not guessed. See that tool's own header comment for the confirmed vs. estimated
 * pity value provenance (F7/M7 Necron's Handle and F7 Scrolls are confirmed via math thread;
 * everything else is a best community estimate).
 */
public final class RngItemData {

    private RngItemData() {
    }

    public static final List<RngItem> DUNGEONS = List.of(
            // F1 (Bonzo, ~75 score/S+ run)
            new RngItem("F1", "Bonzo's Staff", "BONZO_STAFF", AH, 22_500, false),
            new RngItem("F1", "Bonzo's Mask", "BONZO_MASK", AH, 15_000, false),
            new RngItem("F1", "Red Nose", "RED_NOSE", AH, 10_000, false),
            new RngItem("F1", "Balloon Snake", "BALLOON_SNAKE", AH, 8_000, false),
            new RngItem("F1", "Necromancer's Brooch", "NECROMANCER_BROOCH", AH, 7_000, false),
            new RngItem("F1", "Recombobulator 3000", "RECOMBOBULATOR_3000", BAZAAR, 20_000, false),
            new RngItem("F1", "Hot Potato Book", "HOT_POTATO_BOOK", BAZAAR, 3_000, false),
            new RngItem("F1", "Fuming Potato Book", "FUMING_POTATO_BOOK", BAZAAR, 10_000, false),
            new RngItem("F1", "Rejuvenate I", "ENCHANTMENT_REJUVENATE_1", BAZAAR, 3_000, false),
            new RngItem("F1", "Combo I", "ENCHANTMENT_COMBO_1", BAZAAR, 3_000, false),
            new RngItem("F1", "No Pain No Gain I", "ENCHANTMENT_NO_PAIN_NO_GAIN_1", BAZAAR, 5_000, false),
            new RngItem("F1", "Infinite Quiver VI", "ENCHANTMENT_INFINITE_QUIVER_6", BAZAAR, 15_000, false),
            new RngItem("F1", "Feather Falling VI", "ENCHANTMENT_FEATHER_FALLING_6", BAZAAR, 15_000, false),
            new RngItem("F1", "Ultimate Jerry I", "ENCHANTMENT_ULTIMATE_JERRY_1", AH, 30_000, false),

            // F2 (Scarf, ~100 score/S+ run)
            new RngItem("F2", "Scarf's Studies", "SCARF_STUDIES", AH, 25_500, false),
            new RngItem("F2", "Adaptive Blade", "STONE_BLADE", AH, 27_000, false),
            new RngItem("F2", "Red Scarf", "RED_SCARF", AH, 10_000, false),
            new RngItem("F2", "Adaptive Belt", "ADAPTIVE_BELT", AH, 12_000, false),
            new RngItem("F2", "Necromancer's Brooch", "NECROMANCER_BROOCH", AH, 9_000, false),
            new RngItem("F2", "Recombobulator 3000", "RECOMBOBULATOR_3000", BAZAAR, 21_000, false),
            new RngItem("F2", "Hot Potato Book", "HOT_POTATO_BOOK", BAZAAR, 4_000, false),
            new RngItem("F2", "Fuming Potato Book", "FUMING_POTATO_BOOK", BAZAAR, 13_000, false),
            new RngItem("F2", "Rejuvenate I", "ENCHANTMENT_REJUVENATE_1", BAZAAR, 4_000, false),
            new RngItem("F2", "Combo I", "ENCHANTMENT_COMBO_1", BAZAAR, 4_000, false),
            new RngItem("F2", "No Pain No Gain I", "ENCHANTMENT_NO_PAIN_NO_GAIN_1", BAZAAR, 6_000, false),
            new RngItem("F2", "Ultimate Wise I", "ENCHANTMENT_ULTIMATE_WISE_1", AH, 60_000, false),

            // F3 (The Professor, ~150 score/S+ run)
            new RngItem("F3", "Adaptive Helmet", "ADAPTIVE_HELMET", AH, 18_900, false),
            new RngItem("F3", "Adaptive Chestplate", "ADAPTIVE_CHESTPLATE", AH, 18_900, false),
            new RngItem("F3", "Adaptive Leggings", "ADAPTIVE_LEGGINGS", AH, 18_900, false),
            new RngItem("F3", "Adaptive Boots", "ADAPTIVE_BOOTS", AH, 18_900, false),
            new RngItem("F3", "Suspicious Vial", "SUSPICIOUS_VIAL", AH, 12_000, false),
            new RngItem("F3", "Necromancer's Brooch", "NECROMANCER_BROOCH", AH, 9_000, false),
            new RngItem("F3", "Recombobulator 3000", "RECOMBOBULATOR_3000", BAZAAR, 15_000, false),
            new RngItem("F3", "Hot Potato Book", "HOT_POTATO_BOOK", BAZAAR, 5_000, false),
            new RngItem("F3", "Fuming Potato Book", "FUMING_POTATO_BOOK", BAZAAR, 13_000, false),
            new RngItem("F3", "Last Stand I", "ENCHANTMENT_LAST_STAND_1", BAZAAR, 7_500, false),
            new RngItem("F3", "Rejuvenate II", "ENCHANTMENT_REJUVENATE_2", BAZAAR, 7_500, false),
            new RngItem("F3", "Bank II", "ENCHANTMENT_BANK_2", BAZAAR, 7_500, false),
            new RngItem("F3", "Wisdom I", "ENCHANTMENT_WISDOM_1", BAZAAR, 7_500, false),
            new RngItem("F3", "Combo I", "ENCHANTMENT_COMBO_1", BAZAAR, 5_000, false),
            new RngItem("F3", "No Pain No Gain I", "ENCHANTMENT_NO_PAIN_NO_GAIN_1", BAZAAR, 7_500, false),
            new RngItem("F3", "Ultimate Wise I", "ENCHANTMENT_ULTIMATE_WISE_1", AH, 90_000, false),
            new RngItem("F3", "Ultimate Jerry II", "ENCHANTMENT_ULTIMATE_JERRY_2", AH, 60_000, false),

            // F4 (Thorn, ~200 score/S+ run)
            new RngItem("F4", "Spirit Sword", "SPIRIT_SWORD", AH, 15_600, false),
            new RngItem("F4", "Spirit Bow", "SPIRIT_BOW", AH, 15_600, false),
            new RngItem("F4", "Spirit Wing", "SPIRIT_WING", AH, 12_450, false),
            new RngItem("F4", "Spirit Sceptre", "SPIRIT_SCEPTRE", AH, 20_000, false),
            new RngItem("F4", "Spirit Boots", "THORNS_BOOTS", AH, 20_000, false),
            new RngItem("F4", "Necromancer's Brooch", "NECROMANCER_BROOCH", AH, 12_000, false),
            new RngItem("F4", "Recombobulator 3000", "RECOMBOBULATOR_3000", BAZAAR, 12_000, false),
            new RngItem("F4", "Hot Potato Book", "HOT_POTATO_BOOK", BAZAAR, 7_000, false),
            new RngItem("F4", "Fuming Potato Book", "FUMING_POTATO_BOOK", BAZAAR, 15_000, false),

            // F5 (Livid, ~250 score/S+ run)
            new RngItem("F5", "Shadow Fury", "SHADOW_FURY", AH, 114_300, false),
            new RngItem("F5", "Livid Dagger", "LIVID_DAGGER", AH, 80_000, false),
            new RngItem("F5", "Shadow Assassin Helmet", "SHADOW_ASSASSIN_HELMET", AH, 30_000, false),
            new RngItem("F5", "Shadow Assassin Chestplate", "SHADOW_ASSASSIN_CHESTPLATE", AH, 30_000, false),
            new RngItem("F5", "Shadow Assassin Leggings", "SHADOW_ASSASSIN_LEGGINGS", AH, 30_000, false),
            new RngItem("F5", "Shadow Assassin Boots", "SHADOW_ASSASSIN_BOOTS", AH, 30_000, false),
            new RngItem("F5", "Recombobulator 3000", "RECOMBOBULATOR_3000", BAZAAR, 10_000, false),
            new RngItem("F5", "Hot Potato Book", "HOT_POTATO_BOOK", BAZAAR, 8_000, false),
            new RngItem("F5", "Fuming Potato Book", "FUMING_POTATO_BOOK", BAZAAR, 18_000, false),

            // F6 (Sadan, ~280 score/S+ run)
            new RngItem("F6", "Giant's Sword", "GIANTS_SWORD", AH, 160_000, false),
            new RngItem("F6", "Necromancer Lord Helmet", "NECROMANCER_LORD_HELMET", AH, 70_000, false),
            new RngItem("F6", "Necromancer Lord Chestplate", "NECROMANCER_LORD_CHESTPLATE", AH, 70_000, false),
            new RngItem("F6", "Necromancer Lord Leggings", "NECROMANCER_LORD_LEGGINGS", AH, 70_000, false),
            new RngItem("F6", "Necromancer Lord Boots", "NECROMANCER_LORD_BOOTS", AH, 70_000, false),
            new RngItem("F6", "Ancient Rose", "ANCIENT_ROSE", AH, 80_000, false),
            new RngItem("F6", "Precursor Eye", "PRECURSOR_EYE", AH, 280_000, false),
            new RngItem("F6", "Recombobulator 3000", "RECOMBOBULATOR_3000", BAZAAR, 10_000, false),
            new RngItem("F6", "Hot Potato Book", "HOT_POTATO_BOOK", BAZAAR, 9_000, false),
            new RngItem("F6", "Fuming Potato Book", "FUMING_POTATO_BOOK", BAZAAR, 20_000, false),

            // F7 (Necron, ~300 score/S+ run) - confirmed: T=13705, Handle w=15 -> 274100; Scrolls w=20 -> 205575
            new RngItem("F7", "Necron's Handle", "NECRON_HANDLE", AH, 274_100, false),
            new RngItem("F7", "Scroll of Implosion", "IMPLOSION_SCROLL", BAZAAR, 205_575, false),
            new RngItem("F7", "Scroll of Shadow Warp", "SHADOW_WARP_SCROLL", BAZAAR, 205_575, false),
            new RngItem("F7", "Scroll of Wither Shield", "WITHER_SHIELD_SCROLL", BAZAAR, 205_575, false),
            new RngItem("F7", "One For All", "ONE_FOR_ALL", AH, 205_575, false),
            new RngItem("F7", "Fifth Master Star", "FIFTH_MASTER_STAR", AH, 100_000, false),
            new RngItem("F7", "Master Skull - Tier 5", "MASTER_SKULL_TIER_5", AH, 50_000, false),
            new RngItem("F7", "Wither Helmet", "WITHER_HELMET", AH, 41_115, false),
            new RngItem("F7", "Wither Chestplate", "WITHER_CHESTPLATE", AH, 41_115, false),
            new RngItem("F7", "Wither Leggings", "WITHER_LEGGINGS", AH, 41_115, false),
            new RngItem("F7", "Wither Boots", "WITHER_BOOTS", AH, 41_115, false),
            new RngItem("F7", "Precursor Gear", "PRECURSOR_GEAR", AH, 100_000, false),
            new RngItem("F7", "Soul Eater I", "ENCHANTMENT_SOUL_EATER_1", BAZAAR, 150_000, false),
            new RngItem("F7", "Recombobulator 3000", "RECOMBOBULATOR_3000", BAZAAR, 20_000, false),
            new RngItem("F7", "Hot Potato Book", "HOT_POTATO_BOOK", BAZAAR, 10_000, false),
            new RngItem("F7", "Fuming Potato Book", "FUMING_POTATO_BOOK", BAZAAR, 25_000, false)
    );

    public static final List<RngItem> MASTER_MODE = List.of(
            // M1 (Bonzo, ~300 score/S+ run)
            new RngItem("M1", "Bonzo's Staff", "BONZO_STAFF", AH, 45_000, false),
            new RngItem("M1", "Bonzo's Mask", "BONZO_MASK", AH, 30_000, false),
            new RngItem("M1", "Recombobulator 3000", "RECOMBOBULATOR_3000", BAZAAR, 40_000, false),
            new RngItem("M1", "Hot Potato Book", "HOT_POTATO_BOOK", BAZAAR, 6_000, false),
            new RngItem("M1", "Fuming Potato Book", "FUMING_POTATO_BOOK", BAZAAR, 20_000, false),

            // M2 (Scarf)
            new RngItem("M2", "Scarf's Studies", "SCARF_STUDIES", AH, 55_000, false),
            new RngItem("M2", "Adaptive Blade", "STONE_BLADE", AH, 58_000, false),
            new RngItem("M2", "Red Scarf", "RED_SCARF", AH, 20_000, false),
            new RngItem("M2", "Adaptive Belt", "ADAPTIVE_BELT", AH, 24_000, false),
            new RngItem("M2", "Recombobulator 3000", "RECOMBOBULATOR_3000", BAZAAR, 45_000, false),
            new RngItem("M2", "Hot Potato Book", "HOT_POTATO_BOOK", BAZAAR, 8_000, false),
            new RngItem("M2", "Fuming Potato Book", "FUMING_POTATO_BOOK", BAZAAR, 26_000, false),

            // M3 (The Professor)
            new RngItem("M3", "First Master Star", "FIRST_MASTER_STAR", AH, 90_000, false),
            new RngItem("M3", "Master Skull - Tier 3", "MASTER_SKULL_TIER_3", AH, 60_000, false),
            new RngItem("M3", "Adaptive Helmet", "ADAPTIVE_HELMET", AH, 40_000, false),
            new RngItem("M3", "Adaptive Chestplate", "ADAPTIVE_CHESTPLATE", AH, 40_000, false),
            new RngItem("M3", "Adaptive Leggings", "ADAPTIVE_LEGGINGS", AH, 40_000, false),
            new RngItem("M3", "Adaptive Boots", "ADAPTIVE_BOOTS", AH, 40_000, false),
            new RngItem("M3", "Recombobulator 3000", "RECOMBOBULATOR_3000", BAZAAR, 30_000, false),
            new RngItem("M3", "Hot Potato Book", "HOT_POTATO_BOOK", BAZAAR, 12_000, false),
            new RngItem("M3", "Fuming Potato Book", "FUMING_POTATO_BOOK", BAZAAR, 36_000, false),

            // M4 (Thorn)
            new RngItem("M4", "Second Master Star", "SECOND_MASTER_STAR", AH, 160_000, false),
            new RngItem("M4", "Master Skull - Tier 4", "MASTER_SKULL_TIER_4", AH, 100_000, false),
            new RngItem("M4", "Spirit Sword", "SPIRIT_SWORD", AH, 35_000, false),
            new RngItem("M4", "Spirit Bow", "SPIRIT_BOW", AH, 35_000, false),
            new RngItem("M4", "Spirit Wing", "SPIRIT_WING", AH, 28_000, false),
            new RngItem("M4", "Spirit Sceptre", "SPIRIT_SCEPTRE", AH, 45_000, false),
            new RngItem("M4", "Spirit Boots", "THORNS_BOOTS", AH, 45_000, false),
            new RngItem("M4", "Recombobulator 3000", "RECOMBOBULATOR_3000", BAZAAR, 28_000, false),
            new RngItem("M4", "Hot Potato Book", "HOT_POTATO_BOOK", BAZAAR, 16_000, false),
            new RngItem("M4", "Fuming Potato Book", "FUMING_POTATO_BOOK", BAZAAR, 48_000, false),

            // M5 (Livid)
            new RngItem("M5", "Third Master Star", "THIRD_MASTER_STAR", AH, 150_000, false),
            new RngItem("M5", "Master Skull - Tier 4", "MASTER_SKULL_TIER_4", AH, 100_000, false),
            new RngItem("M5", "Shadow Fury", "SHADOW_FURY", AH, 228_600, false),
            new RngItem("M5", "Livid Dagger", "LIVID_DAGGER", AH, 160_000, false),
            new RngItem("M5", "Shadow Assassin Helmet", "SHADOW_ASSASSIN_HELMET", AH, 65_000, false),
            new RngItem("M5", "Shadow Assassin Chestplate", "SHADOW_ASSASSIN_CHESTPLATE", AH, 65_000, false),
            new RngItem("M5", "Shadow Assassin Leggings", "SHADOW_ASSASSIN_LEGGINGS", AH, 65_000, false),
            new RngItem("M5", "Shadow Assassin Boots", "SHADOW_ASSASSIN_BOOTS", AH, 65_000, false),
            new RngItem("M5", "Recombobulator 3000", "RECOMBOBULATOR_3000", BAZAAR, 25_000, false),
            new RngItem("M5", "Hot Potato Book", "HOT_POTATO_BOOK", BAZAAR, 20_000, false),
            new RngItem("M5", "Fuming Potato Book", "FUMING_POTATO_BOOK", BAZAAR, 60_000, false),

            // M6 (Sadan)
            new RngItem("M6", "Fourth Master Star", "FOURTH_MASTER_STAR", BAZAAR, 300_000, false),
            new RngItem("M6", "Master Skull - Tier 5", "MASTER_SKULL_TIER_5", AH, 150_000, false),
            new RngItem("M6", "Giant's Sword", "GIANTS_SWORD", AH, 320_000, false),
            new RngItem("M6", "Necromancer Lord Helmet", "NECROMANCER_LORD_HELMET", AH, 145_000, false),
            new RngItem("M6", "Necromancer Lord Chestplate", "NECROMANCER_LORD_CHESTPLATE", AH, 145_000, false),
            new RngItem("M6", "Necromancer Lord Leggings", "NECROMANCER_LORD_LEGGINGS", AH, 145_000, false),
            new RngItem("M6", "Necromancer Lord Boots", "NECROMANCER_LORD_BOOTS", AH, 145_000, false),
            new RngItem("M6", "Ancient Rose", "ANCIENT_ROSE", AH, 160_000, false),
            new RngItem("M6", "Precursor Eye", "PRECURSOR_EYE", AH, 560_000, false),
            new RngItem("M6", "Recombobulator 3000", "RECOMBOBULATOR_3000", BAZAAR, 22_000, false),
            new RngItem("M6", "Hot Potato Book", "HOT_POTATO_BOOK", BAZAAR, 24_000, false),
            new RngItem("M6", "Fuming Potato Book", "FUMING_POTATO_BOOK", BAZAAR, 72_000, false),

            // M7 (Necron) - confirmed pity 231,583 (Math thread, T_M7 ~ 11,579)
            new RngItem("M7", "Necron's Handle", "NECRON_HANDLE", AH, 231_583, false),
            new RngItem("M7", "Scroll of Implosion", "IMPLOSION_SCROLL", BAZAAR, 173_685, false),
            new RngItem("M7", "Scroll of Shadow Warp", "SHADOW_WARP_SCROLL", BAZAAR, 173_685, false),
            new RngItem("M7", "Scroll of Wither Shield", "WITHER_SHIELD_SCROLL", BAZAAR, 173_685, false),
            new RngItem("M7", "One For All", "ONE_FOR_ALL", AH, 173_685, false),
            new RngItem("M7", "Fifth Master Star", "FIFTH_MASTER_STAR", AH, 500_000, false),
            new RngItem("M7", "Master Skull - Tier 5", "MASTER_SKULL_TIER_5", AH, 200_000, false),
            new RngItem("M7", "Necron's Spine", "NECRON_SPINE", AH, 300_000, false),
            new RngItem("M7", "Wither Helmet", "WITHER_HELMET", AH, 86_868, false),
            new RngItem("M7", "Wither Chestplate", "WITHER_CHESTPLATE", AH, 86_868, false),
            new RngItem("M7", "Wither Leggings", "WITHER_LEGGINGS", AH, 86_868, false),
            new RngItem("M7", "Wither Boots", "WITHER_BOOTS", AH, 86_868, false),
            new RngItem("M7", "Dark Claymore", "DARK_CLAYMORE", AH, 100_000, false),
            new RngItem("M7", "Precursor Gear", "PRECURSOR_GEAR", AH, 200_000, false),
            new RngItem("M7", "Soul Eater I", "ENCHANTMENT_SOUL_EATER_1", BAZAAR, 300_000, false),
            new RngItem("M7", "Recombobulator 3000", "RECOMBOBULATOR_3000", BAZAAR, 45_000, false),
            new RngItem("M7", "Hot Potato Book", "HOT_POTATO_BOOK", BAZAAR, 30_000, false),
            new RngItem("M7", "Fuming Potato Book", "FUMING_POTATO_BOOK", BAZAAR, 90_000, false)
    );

    public static final List<RngItem> SLAYERS = List.of(
            // Zombie (Revenant Horror)
            new RngItem("Zombie T3", "Pestilence Rune", "PESTILENCE_RUNE", AH, 40_000, false),
            new RngItem("Zombie T3", "Snake Rune", "SNAKE_RUNE", AH, 40_000, false),
            new RngItem("Zombie T4", "Revenant Catalyst", "REVENANT_CATALYST", BAZAAR, 60_000, false),
            new RngItem("Zombie T4", "Undead Catalyst", "UNDEAD_CATALYST", BAZAAR, 60_000, false),
            new RngItem("Zombie T5", "Scythe Blade", "SCYTHE_BLADE", BAZAAR, 2_000_000, false),
            new RngItem("Zombie T5", "Foul Flesh", "FOUL_FLESH", BAZAAR, 100_000, false),
            new RngItem("Zombie T5", "Warden Heart", "WARDEN_HEART", AH, 3_631_748, false),

            // Spider (Tarantula Broodfather)
            new RngItem("Spider T3", "Spider Catalyst", "SPIDER_CATALYST", BAZAAR, 50_000, false),
            new RngItem("Spider T3", "Digested Mosquito", "DIGESTED_MOSQUITO", AH, 286_000, false),
            new RngItem("Spider T4", "Tarantula Talisman", "TARANTULA_TALISMAN", AH, 100_000, false),
            new RngItem("Spider T4", "Fly Swatter", "FLY_SWATTER", AH, 100_000, false),
            new RngItem("Spider T4", "Toxic Arrow Poison", "TOXIC_ARROW_POISON", BAZAAR, 120_000, false),

            // Wolf (Sven Packmaster)
            new RngItem("Wolf T3", "Spirit Stone", "SPIRIT_STONE", BAZAAR, 50_000, false),
            new RngItem("Wolf T4", "Grizzly Bait", "GRIZZLY_BAIT", AH, 288_000, false),
            new RngItem("Wolf T4", "Overflux Capacitor", "OVERFLUX_CAPACITOR", AH, 404_000, false),
            new RngItem("Wolf T4", "Red Claw Egg", "RED_CLAW_EGG", AH, 200_000, false),

            // Enderman (Voidgloom Seraph)
            new RngItem("Enderman T3", "Null Sphere", "NULL_SPHERE", AH, 80_000, false),
            new RngItem("Enderman T4", "Judgement Core", "JUDGEMENT_CORE", AH, 289_000, false),
            new RngItem("Enderman T4", "Ender Artifact Upgrader", "EXCEEDINGLY_RARE_ENDER_ARTIFACT_UPGRADE", AH, 577_000, false),
            new RngItem("Enderman T4", "Ender Slayer VII", "ENCHANTMENT_ENDER_SLAYER_7", BAZAAR, 1_150_000, false),
            new RngItem("Enderman T4", "Enderman Cortex Rewriter", "ENDERMAN_CORTEX_REWRITER", AH, 300_000, false),

            // Blaze (Inferno Demonlord)
            new RngItem("Blaze T3", "Blaze Catalyst", "BLAZE_CATALYST", BAZAAR, 80_000, false),
            new RngItem("Blaze T4", "Mana Flux Power Orb", "MANA_FLUX_POWER_ORB", AH, 300_000, false),
            new RngItem("Blaze T4", "Scorched Power Crystal", "SCORCHED_POWER_CRYSTAL", AH, 250_000, false),
            new RngItem("Blaze T4", "High-Class Archfiend Dice", "HIGH_CLASS_ARCHFIEND_DICE", AH, 500_000, false),

            // Vampire (Riftstalker Bloodfiend)
            new RngItem("Vampire T3", "Vampire Fang", "VAMPIRE_FANG", BAZAAR, 50_000, false),
            new RngItem("Vampire T3", "Blood Ichor", "BLOOD_ICHOR", BAZAAR, 50_000, false),
            new RngItem("Vampire T4", "Rift Prism", "RIFT_PRISM", AH, 300_000, false),
            new RngItem("Vampire T5", "Antique Remedies", "ANTIQUE_REMEDIES", AH, 1_000_000, false),
            new RngItem("Vampire T5", "Wand of Atonement", "WAND_OF_ATONEMENT", AH, 1_500_000, false),
            new RngItem("Vampire T5", "Rift Essence", "RIFT_ESSENCE", BAZAAR, 200_000, false)
    );

    /** +1000 Nucleus XP per completed Crystal Nucleus run. */
    public static final List<RngItem> CRYSTAL_NUCLEUS = List.of(
            new RngItem("Crystal Nucleus", "Divan's Alloy", "DIVAN_ALLOY", AH, 1_000_000, false)
    );

    /** Fills at 0.1% of enchanting XP gained per Superpairs game; pity values are raw enchanting XP. */
    public static final List<RngItem> EXPERIMENTATION_TABLE = List.of(
            // Tier 1
            new RngItem("Superpairs", "Titanic Experience Bottle", "TITANIC_EXP_BOTTLE", AH, 15_000, false),
            // Tier 2
            new RngItem("Superpairs", "Experiment the Fish", "EXPERIMENT_THE_FISH", AH, 50_000, false),
            new RngItem("Superpairs", "Metaphysical Serum", "METAPHYSICAL_SERUM", AH, 50_000, false),
            // Tier 3 - enchanted books + Guardian pet
            new RngItem("Superpairs", "Guardian Pet (Legendary)", "GUARDIAN", AH, 150_000, false),
            new RngItem("Superpairs", "Scavenger V", "ENCHANTMENT_SCAVENGER_5", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Sharpness VI", "ENCHANTMENT_SHARPNESS_6", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Sharpness VII", "ENCHANTMENT_SHARPNESS_7", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Life Steal IV", "ENCHANTMENT_LIFE_STEAL_4", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Life Steal V", "ENCHANTMENT_LIFE_STEAL_5", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Power VI", "ENCHANTMENT_POWER_6", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Power VII", "ENCHANTMENT_POWER_7", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Ender Slayer VI", "ENCHANTMENT_ENDER_SLAYER_6", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Thunderbolt VI", "ENCHANTMENT_THUNDERBOLT_6", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Thunderbolt VII", "ENCHANTMENT_THUNDERBOLT_7", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Growth VI", "ENCHANTMENT_GROWTH_6", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Growth VII", "ENCHANTMENT_GROWTH_7", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Chance IV", "ENCHANTMENT_CHANCE_4", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Chance V", "ENCHANTMENT_CHANCE_5", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Blast Protection VI", "ENCHANTMENT_BLAST_PROTECTION_6", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Blast Protection VII", "ENCHANTMENT_BLAST_PROTECTION_7", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Respite III", "ENCHANTMENT_RESPITE_3", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Venomous VI", "ENCHANTMENT_VENOMOUS_6", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Projectile Protection VI", "ENCHANTMENT_PROJECTILE_PROTECTION_6", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Projectile Protection VII", "ENCHANTMENT_PROJECTILE_PROTECTION_7", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Fire Protection VI", "ENCHANTMENT_FIRE_PROTECTION_6", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Fire Protection VII", "ENCHANTMENT_FIRE_PROTECTION_7", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Woodsplitter VI", "ENCHANTMENT_WOODSPLITTER_6", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Giant Killer VI", "ENCHANTMENT_GIANT_KILLER_6", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Giant Killer VII", "ENCHANTMENT_GIANT_KILLER_7", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Drain IV", "ENCHANTMENT_DRAIN_4", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Drain V", "ENCHANTMENT_DRAIN_5", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Protection VI", "ENCHANTMENT_PROTECTION_6", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Protection VII", "ENCHANTMENT_PROTECTION_7", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Titan Killer VI", "ENCHANTMENT_TITAN_KILLER_6", BAZAAR, 150_000, false),
            new RngItem("Superpairs", "Titan Killer VII", "ENCHANTMENT_TITAN_KILLER_7", BAZAAR, 150_000, false),
            // Tier 4 - rare drops
            new RngItem("Superpairs", "Beginner's Guide to Pesthunting", "PESTHUNTING_GUIDE", AH, 500_000, false),
            new RngItem("Superpairs", "Severed Pincer", "SEVERED_PINCER", AH, 500_000, false),
            new RngItem("Superpairs", "Ensnared Snail", "ENSNARED_SNAIL", AH, 500_000, false),
            new RngItem("Superpairs", "Golden Bounty", "GOLDEN_BOUNTY", AH, 500_000, false),
            new RngItem("Superpairs", "Severed Hand", "SEVERED_HAND", AH, 500_000, false),
            new RngItem("Superpairs", "Vibrant Coral", "VIBRANT_CORAL", AH, 500_000, false),
            new RngItem("Superpairs", "Gold Bottle Cap", "GOLD_BOTTLE_CAP", AH, 500_000, false),
            new RngItem("Superpairs", "Chain of the End Times", "CHAIN_END_TIMES", AH, 500_000, false),
            new RngItem("Superpairs", "Fateful Stinger", "FATEFUL_STINGER", AH, 500_000, false),
            new RngItem("Superpairs", "Octopus Tendril", "OCTOPUS_TENDRIL", AH, 500_000, false),
            new RngItem("Superpairs", "End Stone Idol", "ENDSTONE_IDOL", AH, 500_000, false),
            new RngItem("Superpairs", "Troubled Bubble", "TROUBLED_BUBBLE", AH, 500_000, false),
            // Tier 5 - mega rare
            new RngItem("Superpairs", "Nadeshiko Dye", "DYE_NADESHIKO", BAZAAR, 2_500_000, false)
    );

    /** XP per corpse: Lapis +500, Umber/Tungsten +2500, Vanguard +25000. */
    public static final List<RngItem> FROZEN_CORPSES = List.of(
            new RngItem("Lapis Corpse", "Lapis Armor Helmet", "LAPIS_ARMOR_HELMET", AH, 2_500, false),
            new RngItem("Lapis Corpse", "Lapis Armor Chestplate", "LAPIS_ARMOR_CHESTPLATE", AH, 2_500, false),
            new RngItem("Lapis Corpse", "Lapis Armor Leggings", "LAPIS_ARMOR_LEGGINGS", AH, 2_500, false),
            new RngItem("Lapis Corpse", "Lapis Armor Boots", "LAPIS_ARMOR_BOOTS", AH, 2_500, false),
            new RngItem("Umber Corpse", "Umber Helmet", "UMBER_HELMET", AH, 15_000, false),
            new RngItem("Umber Corpse", "Umber Chestplate", "UMBER_CHESTPLATE", AH, 15_000, false),
            new RngItem("Umber Corpse", "Umber Leggings", "UMBER_LEGGINGS", AH, 15_000, false),
            new RngItem("Umber Corpse", "Umber Boots", "UMBER_BOOTS", AH, 15_000, false),
            new RngItem("Tungsten Corpse", "Tungsten Helmet", "TUNGSTEN_HELMET", AH, 20_000, false),
            new RngItem("Tungsten Corpse", "Tungsten Chestplate", "TUNGSTEN_CHESTPLATE", AH, 20_000, false),
            new RngItem("Tungsten Corpse", "Tungsten Leggings", "TUNGSTEN_LEGGINGS", AH, 20_000, false),
            new RngItem("Tungsten Corpse", "Tungsten Boots", "TUNGSTEN_BOOTS", AH, 20_000, false),
            new RngItem("Vanguard Corpse", "Vanguard Helmet", "VANGUARD_HELMET", AH, 250_000, false),
            new RngItem("Vanguard Corpse", "Vanguard Chestplate", "VANGUARD_CHESTPLATE", AH, 250_000, false),
            new RngItem("Vanguard Corpse", "Vanguard Leggings", "VANGUARD_LEGGINGS", AH, 250_000, false),
            new RngItem("Vanguard Corpse", "Vanguard Boots", "VANGUARD_BOOTS", AH, 250_000, false),
            new RngItem("Divan Corpse", "Divan's Helmet", "DIVAN_HELMET", AH, 375_000, false),
            new RngItem("Divan Corpse", "Divan's Chestplate", "DIVAN_CHESTPLATE", AH, 375_000, false),
            new RngItem("Divan Corpse", "Divan's Leggings", "DIVAN_LEGGINGS", AH, 375_000, false),
            new RngItem("Divan Corpse", "Divan's Boots", "DIVAN_BOOTS", AH, 375_000, false),
            new RngItem("Divan Corpse", "Divan's Drill", "DIVAN_DRILL", AH, 750_000, false)
    );

    public static final List<List<RngItem>> ALL_CATEGORIES = List.of(
            DUNGEONS, MASTER_MODE, SLAYERS, CRYSTAL_NUCLEUS, EXPERIMENTATION_TABLE, FROZEN_CORPSES
    );

    public static final List<String> CATEGORY_NAMES = List.of(
            "Dungeons", "Master Mode", "Slayers", "Crystal Nucleus", "Experimentation Table", "Frozen Corpses"
    );
}
