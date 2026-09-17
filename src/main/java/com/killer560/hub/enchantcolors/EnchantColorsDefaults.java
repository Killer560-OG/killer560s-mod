package com.killer560.hub.enchantcolors;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The out-of-the-box enchantment colours - killer560 asked for "sensible defaults out of the box", the point
 * being that a maxed item's wall of enchant text becomes scannable at a glance instead of 30 identical blue
 * lines.
 * <p>
 * SkyHanni derives its tiers from a repository JSON ({@code constants/Enchants.json} in SkyHanni-REPO, which
 * carries a {@code goodLevel}/{@code maxLevel} per enchant) and colours by "is this at max level". This mod
 * deliberately does NOT download a constants file, so instead the tiers below are by WHICH ENCHANT IT IS -
 * the thing that actually decides whether a line is worth reading on a dungeon item. Keys are the
 * lore-normalised name (see {@link EnchantColorsFeature#normalize}); every one of them is editable, and the
 * whole table is just the seed for a brand-new config file, never re-applied over the user's own edits.
 * <p>
 * These tier assignments are a judgement call and are the first thing killer560 should expect to change.
 */
public final class EnchantColorsDefaults {

    /** Ultimate enchants. Also the fallback colour for anything the NBT says is an {@code ultimate_*} id. */
    public static final int ULTIMATE = 0xFFFF55FF;
    /** The damage/profit multipliers you actually check an item for. */
    public static final int TOP = 0xFFFFAA00;
    /** Worth having, not worth staring at. */
    public static final int GOOD = 0xFF55FFFF;
    /** Filler that only exists to fill enchant slots - pushed into the background on purpose. */
    public static final int FILLER = 0xFF555555;
    /** Anything not listed, when "Only Configured Enchantments" is off. Hypixel's own lore blue. */
    public static final int UNLISTED = 0xFF5555FF;

    private EnchantColorsDefaults() {
    }

    public static Map<String, Integer> build() {
        Map<String, Integer> d = new LinkedHashMap<>();

        // ---- Ultimates. Listed by LORE name because several ultimate NBT ids don't contain their lore name
        // ("ultimate_reiterate" shows as "Duplex", "ultimate_one_for_all" as "One For All"), so name-based
        // matching has to stand on its own rather than relying on the ultimate_* auto-detection.
        for (String s : new String[]{
                "one for all", "soul eater", "chimera", "legion", "wisdom", "combo", "duplex", "fatal tempo",
                "inferno", "swarm", "last stand", "no pain no gain", "ultimate wise", "ultimate jerry",
                "bank", "rend", "habanero tactics", "bobbin' time", "flash", "the one"}) {
            d.put(s, ULTIMATE);
        }

        // ---- The ones that decide whether a weapon/armour piece is good.
        for (String s : new String[]{
                "sharpness", "critical", "first strike", "giant killer", "execute", "ender slayer",
                "prosecute", "dragon hunter", "power", "overload", "growth", "protection",
                "true protection", "mana vampire", "ferocious mana", "syphon", "lethality", "champion",
                "cleave", "thunderlord", "vicious", "smoldering", "strong mana", "big brain"}) {
            d.put(s, TOP);
        }

        // ---- Useful, but not what you scan for.
        for (String s : new String[]{
                "luck", "looting", "scavenger", "infinite quiver", "snipe", "dragon tracer", "life steal",
                "rejuvenate", "sugar rush", "respiration", "depth strider", "feather falling",
                "aqua affinity", "blessing", "counter-strike", "venomous", "triple-strike", "titan killer",
                "flame", "punch", "aiming", "piercing", "thorns", "experience", "telekinesis",
                "vampirism", "fire aspect", "harvesting", "cultivating", "compact", "expertise",
                "hecatomb", "champion's"}) {
            d.put(s, GOOD);
        }

        // ---- Slot filler.
        for (String s : new String[]{
                "cubism", "impaling", "smite", "bane of arthropods", "knockback", "efficiency",
                "silk touch", "fortune", "blast protection", "projectile protection", "fire protection",
                "sunder", "frail", "lure", "angler", "caster", "magnet", "spiked hook", "blessing of",
                "sea creature chance", "pristine", "turbo-cane", "turbo-cactus", "turbo-carrot",
                "turbo-potato", "turbo-warts", "turbo-wheat", "turbo-melon", "turbo-mushrooms",
                "replenish", "dedication", "delicate", "rainbow"}) {
            d.put(s, FILLER);
        }

        return d;
    }
}
