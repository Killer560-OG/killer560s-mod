package com.killer560.hub.itemrarity;

/** Hypixel Skyblock item rarities with their standard chat colors (RGB, no alpha). Order and colors match
 *  Skyblocker's {@code SkyblockItemRarity} (upstream master) and NoammAddons' {@code ItemRarity}
 *  (origin/26.1.2). {@code SUPREME} (the old name, still seen on some legacy/NEU data) is folded into
 *  {@link #ULTIMATE} the same way Skyblocker's {@code toNeuRarity()} maps ULTIMATE&lt;-&gt;SUPREME. */
public enum ItemRarity {
    COMMON("COMMON", 0xFFFFFF),
    UNCOMMON("UNCOMMON", 0x55FF55),
    RARE("RARE", 0x5555FF),
    EPIC("EPIC", 0xAA00AA),
    LEGENDARY("LEGENDARY", 0xFFAA00),
    MYTHIC("MYTHIC", 0xFF55FF),
    DIVINE("DIVINE", 0x55FFFF),
    SPECIAL("SPECIAL", 0xFF5555),
    VERY_SPECIAL("VERY SPECIAL", 0xFF5555),
    ULTIMATE("ULTIMATE", 0xAA0000),
    ADMIN("ADMIN", 0xAA0000);

    private static final ItemRarity[] VALUES = values();

    /** How the rarity is spelled in item lore ("VERY SPECIAL", not "VERY_SPECIAL"). */
    public final String loreName;
    public final int rgb;

    ItemRarity(String loreName, int rgb) {
        this.loreName = loreName;
        this.rgb = rgb;
    }

    /** Exact (upper-case) lore spelling -&gt; rarity. Also accepts the enum name and the legacy "SUPREME". */
    public static ItemRarity byName(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim().toUpperCase(java.util.Locale.ROOT);
        if (n.equals("SUPREME")) {
            return ULTIMATE;
        }
        for (ItemRarity r : VALUES) {
            if (r.loreName.equals(n) || r.name().equals(n)) {
                return r;
            }
        }
        return null;
    }

    /** First rarity whose chat color matches (used for pet names, where only the color carries the tier). */
    public static ItemRarity byColor(int rgb) {
        int c = rgb & 0xFFFFFF;
        for (ItemRarity r : VALUES) {
            if (r.rgb == c) {
                return r;
            }
        }
        return null;
    }

    /** One tier up - the Tier Boost pet item, as Skyblocker's {@code PetInfo} handling does. */
    public ItemRarity next() {
        return switch (this) {
            case COMMON -> UNCOMMON;
            case UNCOMMON -> RARE;
            case RARE -> EPIC;
            case EPIC -> LEGENDARY;
            case LEGENDARY -> MYTHIC;
            default -> this;
        };
    }
}
