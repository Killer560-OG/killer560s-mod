package com.killer560.hub.enchantcolors;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Ground truth for "colour by tier" - killer560, 2026-09-20: "They should follow skyhanni where the color is
 * based off of tier not enchant." Replaces the old per-enchant colour table entirely.
 * <p>
 * {@link #TIERS} (each enchant's {@code goodLevel}/{@code maxLevel}) and {@link #ULTIMATES} are copied
 * straight out of SkyHanni's own repo constants file - not guessed - read from a live SkyHanni install's
 * cache at {@code C:\Users\...\config\skyhanni\repo\constants\Enchants.json} (SkyHanni-7.48.0-mc26.1.jar,
 * 2026-09-20), keyed by its {@code loreName} (lower-cased) so every key here is exactly what Hypixel prints,
 * NOT a guess from the NBT id. That distinction matters: the old table kept its per-enchant colours under
 * id-derived names like "dragon hunter" (from the {@code dragon_hunter} enchant id), but Hypixel actually
 * prints that enchant as "Gravity" - so it could never match and never got recoloured. See
 * {@link EnchantColorsFeature#ID_TO_LORE_NAME} for the full list of ids whose id and printed name diverge
 * like this (also "Drain"/syphon, "Pyroclasm"/magmarizer, "Woodsplitter"/arcane, and the four mana-vitality
 * enchants).
 * <p>
 * The four-tier split and the "ultimates ignore level entirely" rule are decompiled (javap) from SkyHanni's
 * own {@code Enchant.getStyle}/{@code Enchant$Ultimate.getStyle}
 * ({@code features/misc/items/enchants/Enchant.class}): {@code level >= maxLevel} -&gt; Perfect;
 * {@code goodLevel < level < maxLevel} -&gt; Great; {@code level == goodLevel} -&gt; Good;
 * {@code level < goodLevel} -&gt; Poor. An {@code Enchant$Ultimate} never reads goodLevel/maxLevel at all -
 * it is always the ultimate colour, which is why ultimates aren't in {@link #TIERS}.
 * <p>
 * Stacking enchants (Absorb, Compact, Cultivating, Expertise, Hecatomb, Champion, Toxophilite) are folded
 * into the same table using their own goodLevel/maxLevel (all 0/10) - the tier maths is identical, this mod
 * doesn't render their separate stacking-progress footer either way, and leaving them out would mean a
 * fully-stacked farming tool never lights up "Perfect" for a genuinely maxed enchant.
 */
public final class EnchantColorsDefaults {

    public static final int POOR = 0xFF808080;
    public static final int GOOD = 0xFFFFFF55;
    public static final int GREAT = 0xFF55FF55;
    public static final int PERFECT = 0xFF55FFFF;
    /** Also the fallback colour for anything the NBT says is an {@code ultimate_*} id. */
    public static final int ULTIMATE = 0xFFFF55FF;
    /** Anything not in {@link #TIERS}, when "Only Known Enchantments" is off. Hypixel's own lore blue. */
    public static final int UNKNOWN = 0xFF5555FF;

    /** Lore-normalised enchant name -&gt; {goodLevel, maxLevel}. Never mutated - copy before editing. */
    public static final Map<String, int[]> TIERS = Collections.unmodifiableMap(buildTiers());

    /** Lore-normalised names of every known ultimate enchant, for the "New" 2026-09 additions
     *  (First Impression, Flowstate, Refrigerate, Sunset, Crop Fever) the old hardcoded list shipped
     *  without. Ultimate detection itself still comes from the {@code ultimate_} NBT id prefix in
     *  {@link EnchantColorsFeature}; this set only backs the lore-name fallback for the handful of ids
     *  Hypixel renamed (see {@code ULTIMATE_ID_ALIASES}). */
    public static final Set<String> ULTIMATES = Collections.unmodifiableSet(buildUltimates());

    private EnchantColorsDefaults() {
    }

    private static Map<String, int[]> buildTiers() {
        Map<String, int[]> t = new LinkedHashMap<>();
        // ---- Normal
        t.put("angler", new int[]{5, 6});
        t.put("aqua affinity", new int[]{1, 1});
        t.put("bane of arthropods", new int[]{5, 7});
        t.put("big brain", new int[]{2, 5});
        t.put("blast protection", new int[]{5, 7});
        t.put("blessing", new int[]{5, 6});
        t.put("bug blender", new int[]{0, 5});
        t.put("caster", new int[]{5, 6});
        t.put("cayenne", new int[]{3, 5});
        t.put("chance", new int[]{3, 5});
        t.put("charm", new int[]{0, 6});
        t.put("cleave", new int[]{5, 6});
        t.put("corruption", new int[]{0, 5});
        t.put("counter-strike", new int[]{2, 5});
        t.put("critical", new int[]{5, 7});
        t.put("cubism", new int[]{5, 6});
        t.put("dedication", new int[]{0, 4});
        t.put("delicate", new int[]{4, 5});
        t.put("depth strider", new int[]{3, 3});
        t.put("divine gift", new int[]{0, 3});
        t.put("dragon tracer", new int[]{5, 5});
        t.put("drain", new int[]{3, 5});
        t.put("efficiency", new int[]{5, 10});
        t.put("ender slayer", new int[]{5, 7});
        t.put("execute", new int[]{5, 6});
        t.put("experience", new int[]{3, 5});
        t.put("feast", new int[]{0, 5});
        t.put("feather falling", new int[]{5, 10});
        t.put("fire aspect", new int[]{2, 3});
        t.put("fire protection", new int[]{5, 7});
        t.put("first strike", new int[]{4, 5});
        t.put("flame", new int[]{2, 2});
        t.put("forest pledge", new int[]{2, 6});
        t.put("fortune", new int[]{3, 4});
        t.put("frail", new int[]{5, 7});
        t.put("frost walker", new int[]{2, 2});
        t.put("giant killer", new int[]{5, 7});
        t.put("gravity", new int[]{5, 6});
        t.put("great spook", new int[]{0, 1});
        t.put("green thumb", new int[]{0, 5});
        t.put("growth", new int[]{5, 7});
        t.put("hardened vitality", new int[]{0, 10});
        t.put("harvesting", new int[]{5, 6});
        t.put("ice cold", new int[]{0, 5});
        t.put("impaling", new int[]{5, 5});
        t.put("infinite quiver", new int[]{5, 10});
        t.put("karma", new int[]{0, 6});
        t.put("knockback", new int[]{2, 2});
        t.put("lapidary", new int[]{0, 5});
        t.put("lethality", new int[]{5, 6});
        t.put("life steal", new int[]{3, 5});
        t.put("looting", new int[]{3, 5});
        t.put("luck", new int[]{5, 7});
        t.put("luck of the sea", new int[]{5, 7});
        t.put("lure", new int[]{5, 6});
        t.put("magnet", new int[]{5, 6});
        t.put("mana steal", new int[]{0, 3});
        t.put("overload", new int[]{0, 5});
        t.put("paleontologist", new int[]{0, 5});
        t.put("pesterminator", new int[]{0, 6});
        t.put("petalfall", new int[]{0, 5});
        t.put("piercing", new int[]{1, 1});
        t.put("piscary", new int[]{5, 7});
        t.put("power", new int[]{5, 7});
        t.put("prismatic", new int[]{0, 5});
        t.put("projectile protection", new int[]{5, 7});
        t.put("prosecute", new int[]{5, 6});
        t.put("prosperity", new int[]{0, 5});
        t.put("protection", new int[]{5, 7});
        t.put("punch", new int[]{2, 2});
        t.put("pyroclasm", new int[]{5, 6});
        t.put("quantum", new int[]{2, 5});
        t.put("quick bite", new int[]{0, 5});
        t.put("rainbow", new int[]{1, 3});
        t.put("reflection", new int[]{0, 5});
        t.put("rejuvenate", new int[]{0, 5});
        t.put("replenish", new int[]{0, 1});
        t.put("respiration", new int[]{3, 4});
        t.put("respite", new int[]{0, 5});
        t.put("scavenger", new int[]{3, 6});
        t.put("scuba", new int[]{0, 6});
        t.put("sharpness", new int[]{5, 7});
        t.put("silk touch", new int[]{1, 1});
        t.put("small brain", new int[]{2, 5});
        t.put("smarty pants", new int[]{0, 5});
        t.put("smelting touch", new int[]{1, 1});
        t.put("smite", new int[]{5, 7});
        t.put("smoldering", new int[]{0, 5});
        t.put("snipe", new int[]{3, 4});
        t.put("spiked hook", new int[]{5, 7});
        t.put("stealth", new int[]{0, 6});
        t.put("strong vitality", new int[]{0, 10});
        t.put("sugar rush", new int[]{0, 3});
        t.put("sunder", new int[]{0, 6});
        t.put("tabasco", new int[]{1, 3});
        t.put("thorns", new int[]{3, 4});
        t.put("thunderbolt", new int[]{5, 7});
        t.put("thunderlord", new int[]{5, 7});
        t.put("tidal", new int[]{0, 3});
        t.put("titan killer", new int[]{5, 7});
        t.put("transylvanian", new int[]{3, 5});
        t.put("triple-strike", new int[]{4, 5});
        t.put("true protection", new int[]{0, 1});
        t.put("turbo-cacti", new int[]{0, 7});
        t.put("turbo-cane", new int[]{0, 7});
        t.put("turbo-carrot", new int[]{0, 7});
        t.put("turbo-cocoa", new int[]{0, 7});
        t.put("turbo-melon", new int[]{0, 7});
        t.put("turbo-moonflower", new int[]{0, 7});
        t.put("turbo-mushrooms", new int[]{0, 7});
        t.put("turbo-potato", new int[]{0, 7});
        t.put("turbo-pumpkin", new int[]{0, 7});
        t.put("turbo-rose", new int[]{0, 7});
        t.put("turbo-sunflower", new int[]{0, 7});
        t.put("turbo-warts", new int[]{0, 7});
        t.put("turbo-wheat", new int[]{0, 7});
        t.put("vampiric vitality", new int[]{0, 10});
        t.put("vampirism", new int[]{5, 6});
        t.put("venomous", new int[]{5, 7});
        t.put("vicious", new int[]{0, 5});
        t.put("vivacious vitality", new int[]{0, 10});
        t.put("woodsplitter", new int[]{5, 6});
        // ---- Stacking (same tier maths; this mod doesn't render their progress footer)
        t.put("absorb", new int[]{0, 10});
        t.put("champion", new int[]{0, 10});
        t.put("compact", new int[]{0, 10});
        t.put("cultivating", new int[]{0, 10});
        t.put("expertise", new int[]{0, 10});
        t.put("hecatomb", new int[]{0, 10});
        t.put("toxophilite", new int[]{0, 10});
        return t;
    }

    private static Set<String> buildUltimates() {
        Set<String> s = new LinkedHashSet<>();
        s.add("bank");
        s.add("bobbin' time");
        s.add("chimera");
        s.add("combo");
        s.add("crop fever");
        s.add("duplex");
        s.add("fatal tempo");
        s.add("first impression");
        s.add("flash");
        s.add("flowstate");
        s.add("habanero tactics");
        s.add("inferno");
        s.add("last stand");
        s.add("legion");
        s.add("missile");
        s.add("no pain no gain");
        s.add("one for all");
        s.add("refrigerate");
        s.add("rend");
        s.add("soul eater");
        s.add("sunset");
        s.add("swarm");
        s.add("the one");
        s.add("ultimate jerry");
        s.add("ultimate wise");
        s.add("wisdom");
        return s;
    }
}
