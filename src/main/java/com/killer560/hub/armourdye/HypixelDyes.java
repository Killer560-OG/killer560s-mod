package com.killer560.hub.armourdye;

/**
 * The leather colours Hypixel gives its armour, read from the public item catalog (the "color" field: 449 items,
 * 176 distinct values, read 2026-10-18). The catalog has no dye names, so each colour is labelled with the first item
 * (by id) that wears it. Sorted by hue. Values are Hypixel's, copied from the catalog, not picked by hand.
 */
public final class HypixelDyes {

    private HypixelDyes() {
    }

    /** Packed 0xRRGGBB, one per colour, in the same order as {@link #NAMES}. */
    public static final int[] COLORS = {
        0x000000, // Bat Person Boots
        0x191919, // Elegant Tuxedo Oxfords
        0x1B1B1B, // Reaper Boots
        0x332A2A, // Fancy Tuxedo Oxfords
        0x383838, // Cheap Tuxedo Oxfords
        0x450101, // Crypt Witherlord Boots
        0x545454, // Squire Boots
        0x606060, // Spooky Boots
        0x808080, // Ghostly Boots
        0x828282, // Heavy Chestplate
        0x8B0000, // Arachne's Boots
        0xB3B3B3, // Heat Boots
        0xBFBFBF, // Spirit Boots
        0xC7C7C7, // Cheap Tuxedo Pants
        0xCE2C2C, // Moogma Leggings
        0xD4D4D4, // Fancy Tuxedo Pants
        0xD9D9D9, // Metal Chestplate
        0xE6E6E6, // Super Heavy Boots
        0xFF0000, // Chestplate of the Pack
        0xFF0A0A, // Minos Hunter Chestplate
        0xFFFFFF, // Heavy Boots
        0xE7413C, // Necron's Chestplate
        0x6F0F08, // Magma Lord Boots
        0x990D00, // Emperor Leggings
        0xF04729, // Burning Fervor Chestplate
        0xDF2E06, // Rampart Boots
        0xE75C3C, // Necron's Leggings
        0xC83200, // Yog Boots
        0xC13C0F, // Salmon Boots
        0xFF4600, // Orange Chestplate
        0xE76E3C, // Necron's Boots
        0xF25D18, // Superior Dragon Boots
        0x94451F, // Cropie Leggings
        0xAC3900, // Sprout Boots
        0xE25A16, // Helix Boots
        0x7A2900, // Cropie Chestplate
        0xBB6535, // Cropie Boots
        0xE65300, // Burning Crimson Boots
        0xED6612, // Flaming Chestplate
        0xFF6B0B, // Skeleton Master Boots
        0xFF6F0C, // Burning Crimson Chestplate
        0xE66105, // Burning Crimson Leggings
        0xFF700A, // Thermodynamic Boots
        0xCC5500, // Farmer Boots
        0x331900, // Canopy Sandals
        0x1D1105, // Werewolf Boots
        0x88837E, // Goldor's Boots
        0xFEFDFC, // Elegant Tuxedo Pants
        0xFFA33B, // Berserker Chestplate
        0xFF8E09, // Sprout Leggings
        0xFFA73F, // Sprout Chestplate
        0x65605A, // Goldor's Leggings
        0x9A5500, // Tater Boots
        0x45413C, // Goldor's Chestplate
        0xFF9300, // Armor of Magma Boots
        0xD07F00, // Zombie Soldier Boots
        0xE09419, // Strong Dragon Leggings
        0xF8D086, // Tater Chestplate
        0xC8973A, // Tater Leggings
        0xFFB727, // Berserker Leggings
        0xFFAC00, // Biohazard Boots
        0xFFF9EB, // Nutcracker Leggings
        0x9E7003, // Rotten Boots
        0xFFBC0B, // Skeleton Soldier Boots
        0x918F89, // Skeletor Boots
        0xBFBCB2, // Adaptive Boots
        0xFFCB0D, // Burning Hollow Chestplate
        0xFFDC51, // Sponge Boots
        0xFFD427, // Berserker Boots
        0xDEBC15, // Leggings of the Rising Sun
        0x9F8609, // Boots of the Rising Sun
        0xFFD700, // Eleanor's Cap
        0xF0D124, // Strong Dragon Boots
        0xF7DA33, // Blaze Boots
        0xF6DE51, // Charlie's Trousers
        0x99978B, // Protector Dragon Boots
        0xF0E6AA, // Old Dragon Boots
        0xFDE862, // Farmhand Helmet
        0xFFE501, // Helianthus Leggings
        0xFFF6A3, // Burning Hollow Leggings
        0xF2DF11, // Superior Dragon Chestplate
        0xFFF200, // Chicken Leggs
        0x7A7964, // Prospecting Boots
        0xE1EB34, // Skeleton Grunt Boots
        0x83B03B, // Fermento Boots
        0x6A9C1B, // Fermento Leggings
        0x58890C, // Fermento Chestplate
        0xADFF2F, // Bouncy Boots
        0x7AE82C, // Creeper Pants
        0x48FF00, // Femurgrowth Leggings
        0x268105, // Skeleton Lord Boots
        0x276114, // Slug Boots
        0x586158, // Fallen Star Boots
        0x006600, // Canopy Pants
        0x00BE00, // Boots of Growth
        0x4DCC4D, // Leaflet Sandals
        0x47D147, // Holy Dragon Boots
        0x00FF00, // Cactus Boots
        0x35B73B, // Wyld Leggings
        0x154918, // Wyld Boots
        0x37B042, // Goblin Boots
        0x0C4A16, // Squash Leggings
        0x13561E, // Squash Boots
        0x03430E, // Squash Chestplate
        0x017D31, // Backwater Boots
        0x1C9759, // Fig Striders
        0x006633, // Canopy Shirt
        0x2A5B48, // Challenger's Boots
        0x17BF89, // Burning Fervor Leggings
        0x07A674, // Burning Fervor Boots
        0xE0FCF7, // Mercenary Boots
        0xE3FFFA, // Burning Hollow Boots
        0x29F0E9, // Wise Dragon Boots
        0x03FCF8, // Glacite Boots
        0x5A6464, // Super Heavy Chestplate
        0x04CFD3, // Stereo Pants
        0x24DDE5, // Thunder Boots
        0x0E666D, // Abyssal Boots
        0x1CD4E4, // Storm's Boots
        0x304B4E, // Minos Hunter Boots
        0x10616E, // Sea Walker Boots
        0x17A8C4, // Storm's Leggings
        0x117391, // Guardian Chestplate
        0xA0DAEF, // Frozen Blaze Boots
        0x1793C4, // Storm's Chestplate
        0xCCE5FF, // Mineral Boots
        0xCBD2DB, // Rabbit Boots
        0x3588FF, // Vanguard Boots
        0xDDE4F0, // Young Dragon Boots
        0x002CA6, // Shark Scale Boots
        0x6184FC, // Aurora Boots
        0x0E1736, // Primordial Boots
        0x2841F1, // Aurora Chestplate
        0x3F56FB, // Aurora Leggings
        0x101555, // Water Hydra Boots
        0x0C0C96, // Burned Pants
        0x0000FF, // Lapis Armor Boots
        0x3333FF, // Ugly Boots
        0x4A4884, // Farmhand Leggings
        0x0B004F, // Angler Boots
        0x07031B, // Shimmering Light Slippers
        0x4A14B7, // Maxor's Chestplate
        0x5D2FB9, // Maxor's Leggings
        0x5D23D1, // Burning Terror Leggings
        0x7C44EC, // Burning Terror Boots
        0x3E05AF, // Burning Terror Chestplate
        0x8969C8, // Maxor's Boots
        0x1A004C, // Snake-in-a-Boot
        0x4F2886, // Gunther's Sneakers
        0x5B0DAE, // Glossy Mineral Boots
        0x0A0011, // Final Destination Boots
        0xD48EF2, // Celeste Helmet
        0xFCF3FF, // Crystal Boots
        0xB212E3, // Unstable Dragon Boots
        0x370147, // Necromancer Lord Leggings
        0x400352, // Necromancer Lord Boots
        0x9B01C1, // Zombie Lord Boots
        0xD400FF, // Starlight Boots
        0x8D3592, // Melody's Shoes
        0x993399, // Great Spook Boots
        0xFF75FF, // Final Destination Leggings
        0xFF8EDE, // Celeste Chestplate
        0x380024, // Exceedingly Comfy Sneakers
        0xA82B76, // Salmon Chestplate
        0xFF8ECA, // Celeste Leggings
        0xFF3399, // Fairy's Fedora
        0xFF99CC, // Fairy's Trousers
        0xFF66B2, // Fairy's Polo
        0xFFCCE5, // Fairy's Galoshes
        0x7C3756, // Mythos Boots
        0xFF8EB6, // Celeste Boots
        0x46343A, // Nutcracker Boots
        0xD91E41, // Strong Dragon Chestplate
        0xD51230, // Zombie Commander Boots
        0xEC041F, // Farmhand Chestplate
        0xFC2F3C, // Nutcracker Chestplate
    };

    /** The item that wears each colour, for the picker's label. */
    public static final String[] NAMES = {
        "Bat Person Boots",
        "Elegant Tuxedo Oxfords",
        "Reaper Boots",
        "Fancy Tuxedo Oxfords",
        "Cheap Tuxedo Oxfords",
        "Crypt Witherlord Boots",
        "Squire Boots",
        "Spooky Boots",
        "Ghostly Boots",
        "Heavy Chestplate",
        "Arachne's Boots",
        "Heat Boots",
        "Spirit Boots",
        "Cheap Tuxedo Pants",
        "Moogma Leggings",
        "Fancy Tuxedo Pants",
        "Metal Chestplate",
        "Super Heavy Boots",
        "Chestplate of the Pack",
        "Minos Hunter Chestplate",
        "Heavy Boots",
        "Necron's Chestplate",
        "Magma Lord Boots",
        "Emperor Leggings",
        "Burning Fervor Chestplate",
        "Rampart Boots",
        "Necron's Leggings",
        "Yog Boots",
        "Salmon Boots",
        "Orange Chestplate",
        "Necron's Boots",
        "Superior Dragon Boots",
        "Cropie Leggings",
        "Sprout Boots",
        "Helix Boots",
        "Cropie Chestplate",
        "Cropie Boots",
        "Burning Crimson Boots",
        "Flaming Chestplate",
        "Skeleton Master Boots",
        "Burning Crimson Chestplate",
        "Burning Crimson Leggings",
        "Thermodynamic Boots",
        "Farmer Boots",
        "Canopy Sandals",
        "Werewolf Boots",
        "Goldor's Boots",
        "Elegant Tuxedo Pants",
        "Berserker Chestplate",
        "Sprout Leggings",
        "Sprout Chestplate",
        "Goldor's Leggings",
        "Tater Boots",
        "Goldor's Chestplate",
        "Armor of Magma Boots",
        "Zombie Soldier Boots",
        "Strong Dragon Leggings",
        "Tater Chestplate",
        "Tater Leggings",
        "Berserker Leggings",
        "Biohazard Boots",
        "Nutcracker Leggings",
        "Rotten Boots",
        "Skeleton Soldier Boots",
        "Skeletor Boots",
        "Adaptive Boots",
        "Burning Hollow Chestplate",
        "Sponge Boots",
        "Berserker Boots",
        "Leggings of the Rising Sun",
        "Boots of the Rising Sun",
        "Eleanor's Cap",
        "Strong Dragon Boots",
        "Blaze Boots",
        "Charlie's Trousers",
        "Protector Dragon Boots",
        "Old Dragon Boots",
        "Farmhand Helmet",
        "Helianthus Leggings",
        "Burning Hollow Leggings",
        "Superior Dragon Chestplate",
        "Chicken Leggs",
        "Prospecting Boots",
        "Skeleton Grunt Boots",
        "Fermento Boots",
        "Fermento Leggings",
        "Fermento Chestplate",
        "Bouncy Boots",
        "Creeper Pants",
        "Femurgrowth Leggings",
        "Skeleton Lord Boots",
        "Slug Boots",
        "Fallen Star Boots",
        "Canopy Pants",
        "Boots of Growth",
        "Leaflet Sandals",
        "Holy Dragon Boots",
        "Cactus Boots",
        "Wyld Leggings",
        "Wyld Boots",
        "Goblin Boots",
        "Squash Leggings",
        "Squash Boots",
        "Squash Chestplate",
        "Backwater Boots",
        "Fig Striders",
        "Canopy Shirt",
        "Challenger's Boots",
        "Burning Fervor Leggings",
        "Burning Fervor Boots",
        "Mercenary Boots",
        "Burning Hollow Boots",
        "Wise Dragon Boots",
        "Glacite Boots",
        "Super Heavy Chestplate",
        "Stereo Pants",
        "Thunder Boots",
        "Abyssal Boots",
        "Storm's Boots",
        "Minos Hunter Boots",
        "Sea Walker Boots",
        "Storm's Leggings",
        "Guardian Chestplate",
        "Frozen Blaze Boots",
        "Storm's Chestplate",
        "Mineral Boots",
        "Rabbit Boots",
        "Vanguard Boots",
        "Young Dragon Boots",
        "Shark Scale Boots",
        "Aurora Boots",
        "Primordial Boots",
        "Aurora Chestplate",
        "Aurora Leggings",
        "Water Hydra Boots",
        "Burned Pants",
        "Lapis Armor Boots",
        "Ugly Boots",
        "Farmhand Leggings",
        "Angler Boots",
        "Shimmering Light Slippers",
        "Maxor's Chestplate",
        "Maxor's Leggings",
        "Burning Terror Leggings",
        "Burning Terror Boots",
        "Burning Terror Chestplate",
        "Maxor's Boots",
        "Snake-in-a-Boot",
        "Gunther's Sneakers",
        "Glossy Mineral Boots",
        "Final Destination Boots",
        "Celeste Helmet",
        "Crystal Boots",
        "Unstable Dragon Boots",
        "Necromancer Lord Leggings",
        "Necromancer Lord Boots",
        "Zombie Lord Boots",
        "Starlight Boots",
        "Melody's Shoes",
        "Great Spook Boots",
        "Final Destination Leggings",
        "Celeste Chestplate",
        "Exceedingly Comfy Sneakers",
        "Salmon Chestplate",
        "Celeste Leggings",
        "Fairy's Fedora",
        "Fairy's Trousers",
        "Fairy's Polo",
        "Fairy's Galoshes",
        "Mythos Boots",
        "Celeste Boots",
        "Nutcracker Boots",
        "Strong Dragon Chestplate",
        "Zombie Commander Boots",
        "Farmhand Chestplate",
        "Nutcracker Chestplate",
    };
}
