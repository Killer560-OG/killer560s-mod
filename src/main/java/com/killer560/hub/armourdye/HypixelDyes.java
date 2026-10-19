package com.killer560.hub.armourdye;

import java.util.List;

/**
 * Every Hypixel Skyblock armor dye and its color, for the Dye list of the Custom Color page.
 *
 * <p>Hypixel's public item list ({@code /v2/resources/skyblock/items}, which the item browser caches) has no
 * {@code DYE_*} items at all, and its {@code color} field is only on leather armor, so the colors cannot come from
 * there (checked 2026-10-19: the cached list has 5,680 items, none with a {@code DYE_} id). Each color below is the
 * hex Hypixel prints in the dye's own lore - "Changes the color of an armor piece to #RRGGBB!", or for an animated
 * dye "Animates the color of an armor piece between #A and #B!" - as copied into the NotEnoughUpdates item repo,
 * {@code github.com/NotEnoughUpdates/NotEnoughUpdates-REPO}, commit {@code b19b032b79527c1999e278535160f411ad95bece},
 * file {@code items/<ID>.json}, {@code lore} lines 3-4. Every {@code DYE_*} file there plus {@code TENTACLE_DYE};
 * {@code BUCKET_OF_DYE} is an accessory, not a dye, and is left out. Nothing here is a guess.
 *
 * <p>An animated dye has two colors; {@code to} is -1 for a dye with one.
 */
public final class HypixelDyes {

    private HypixelDyes() {
    }

    /** {@code from} and {@code to} are packed {@code 0xRRGGBB}; {@code to} is -1 unless the dye animates. */
    public record Dye(String id, String name, int from, int to) {
        public boolean animated() {
            return to >= 0;
        }
    }

    private static final List<Dye> ALL = List.of(
            new Dye("DYE_AQUAMARINE", "Aquamarine Dye", 0x7FFFD4, -1),
            new Dye("DYE_ARCHFIEND", "Archfiend Dye", 0xB80036, -1),
            new Dye("DYE_AURORA", "Aurora Dye", 0x048F95, 0x088D97),
            new Dye("DYE_BEACH", "Beach Dye", 0x43ECC6, 0x57ECC7),
            new Dye("DYE_BINGO_BLUE", "Bingo Blue Dye", 0x002FA7, -1),
            new Dye("DYE_BLACK_ICE", "Black Ice Dye", 0x0B2031, 0x091B2C),
            new Dye("DYE_BLACK_OPAL", "Black Opal Dye", 0x0B1C5D, 0x0E1B5C),
            new Dye("DYE_BONE", "Bone Dye", 0xE3DAC9, -1),
            new Dye("DYE_BRICK_RED", "Brick Red Dye", 0xCB4154, -1),
            new Dye("DYE_BYZANTIUM", "Byzantium Dye", 0x702963, -1),
            new Dye("DYE_CARMINE", "Carmine Dye", 0x960018, -1),
            new Dye("DYE_CELADON", "Celadon Dye", 0xACE1AF, -1),
            new Dye("DYE_CELESTE", "Celeste Dye", 0xB2FFFF, -1),
            new Dye("DYE_CHARCOAL", "Charcoal Dye", 0x21262A, 0x202429),
            new Dye("DYE_CHOCOLATE", "Chocolate Dye", 0x7B3F00, -1),
            new Dye("DYE_COPPER", "Copper Dye", 0xB87333, -1),
            new Dye("DYE_CYCLAMEN", "Cyclamen Dye", 0xF56FA1, -1),
            new Dye("DYE_DARK_PURPLE", "Dark Purple Dye", 0x301934, -1),
            new Dye("DYE_DUNG", "Dung Dye", 0x4F2A2A, -1),
            new Dye("DYE_DUSK", "Dusk Dye", 0x4F238A, 0x542594),
            new Dye("DYE_EMERALD", "Emerald Dye", 0x50C878, -1),
            new Dye("DYE_FLAME", "Flame Dye", 0xE25822, -1),
            new Dye("DYE_FOREST", "Forest Dye", 0x1D3451, 0x1E3953),
            new Dye("DYE_FOSSIL", "Fossil Dye", 0x866F12, -1),
            new Dye("DYE_FROG", "Frog Dye", 0x64D400, 0x65D400),
            new Dye("DYE_FROSTBITTEN", "Frostbitten Dye", 0x09D8EB, -1),
            new Dye("DYE_HELLEBORE", "Hellebore Dye", 0xE20F99, 0xE3179D),
            new Dye("DYE_HOLLY", "Holly Dye", 0x3C6746, -1),
            new Dye("DYE_ICEBERG", "Iceberg Dye", 0x71A6D2, -1),
            new Dye("DYE_JADE", "Jade Dye", 0x00A86B, -1),
            new Dye("DYE_JERRY", "Jerry Dye", 0x6F5753, 0x715854),
            new Dye("DYE_KINGFISHER", "Kingfisher Dye", 0x004C57, 0x014F59),
            new Dye("DYE_LAVA", "Lava Dye", 0xCA3B00, 0xC63900),
            new Dye("DYE_LIVID", "Livid Dye", 0xCEB7AA, -1),
            new Dye("DYE_LUCKY", "Lucky Dye", 0x87FF37, 0x006944),
            new Dye("DYE_MANGO", "Mango Dye", 0xFDBE02, -1),
            new Dye("DYE_MARINE", "Marine Dye", 0x162875, 0x162876),
            new Dye("DYE_MATCHA", "Matcha Dye", 0x74A12E, -1),
            new Dye("DYE_MIDNIGHT", "Midnight Dye", 0x50216C, -1),
            new Dye("DYE_MOCHA", "Mocha Dye", 0x967969, -1),
            new Dye("DYE_MYTHOLOGICAL", "Mythological Dye", 0x6F6F0C, -1),
            new Dye("DYE_NADESHIKO", "Nadeshiko Dye", 0xF6ADC6, -1),
            new Dye("DYE_NECRON", "Necron Dye", 0xE7413C, -1),
            new Dye("DYE_NYANZA", "Nyanza Dye", 0xE9FFDB, -1),
            new Dye("DYE_OASIS", "Oasis Dye", 0xBAFF81, 0xB7FF84),
            new Dye("DYE_OCEAN", "Ocean Dye", 0x064DC3, 0x0554C9),
            new Dye("DYE_PASTEL_SKY", "Pastel Sky Dye", 0xFB87FF, 0xED87FF),
            new Dye("DYE_PEARLESCENT", "Pearlescent Dye", 0x115555, -1),
            new Dye("DYE_PELT", "Pelt Dye", 0x50414C, -1),
            new Dye("DYE_PERIWINKLE", "Periwinkle Dye", 0xCCCCFF, -1),
            new Dye("DYE_PORTAL", "Portal Dye", 0x5909C1, 0xD412F0),
            new Dye("DYE_PURE_BLACK", "Pure Black Dye", 0x000000, -1),
            new Dye("DYE_PURE_BLUE", "Pure Blue Dye", 0x0013FF, -1),
            new Dye("DYE_PURE_WHITE", "Pure White Dye", 0xFFFFFF, -1),
            new Dye("DYE_PURE_YELLOW", "Pure Yellow Dye", 0xFFF700, -1),
            new Dye("DYE_RED_TULIP", "Red Tulip Dye", 0x8C0014, 0x8D0015),
            new Dye("DYE_ROSE", "Rose Dye", 0xF06766, 0x310417),
            new Dye("DYE_SANGRIA", "Sangria Dye", 0xD40808, -1),
            new Dye("DYE_SECRET", "Secret Dye", 0x7D7D7D, -1),
            new Dye("DYE_SLUDGE", "Sludge Dye", 0x72D159, 0x6FCD58),
            new Dye("DYE_SNOWFLAKE", "Snowflake Dye", 0xACE5FF, 0xB2EAFE),
            new Dye("DYE_SPIRIT", "Spirit Dye", 0x00AFB9, 0x00A9B4),
            new Dye("DYE_SPOOKY", "Spooky Dye", 0x5118A5, 0x5219A8),
            new Dye("DYE_SUNFLOWER", "Sunflower Dye", 0xFFE507, 0xFFE107),
            new Dye("DYE_SUNSET", "Sunset Dye", 0xFFCC00, 0xFFC910),
            new Dye("DYE_TREASURE", "Treasure Dye", 0xFCD12A, -1),
            new Dye("DYE_WARDEN", "Warden Dye", 0x034150, 0x009295),
            new Dye("DYE_WILD_STRAWBERRY", "Wild Strawberry Dye", 0xFF43A4, -1),
            new Dye("TENTACLE_DYE", "Tentacle Dye", 0x324D6C, -1)
    );

    /** All 69 dyes, sorted by name. Every dye colors every armor piece, so there is nothing to filter by. */
    public static List<Dye> all() {
        return ALL;
    }
}
