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
 * <b>2026-09-27 correction, killer560: "make the enchant colors the exact same as skyhanni's for the actual
 * colors."</b> The 2026-09-20 pass got the goodLevel/maxLevel tier MATHS right but the actual default COLOUR
 * VALUES wrong (they read like a leftover from an earlier legacy-16-colour guess, not SkyHanni's real
 * defaults). Re-verified from two concrete sources, both dated 2026-09-27:
 * <ol>
 *   <li>{@code javap -c -p -constants} against the actually-installed {@code SkyHanni-7.22.0-mc1.21.11.jar}
 *       (previous doc cited a "SkyHanni-7.48.0-mc26.1.jar" that isn't the jar on this machine - that citation
 *       was wrong). {@code EnchantParsingConfig}'s constructor bytecode shows the real default {@code
 *       Property} values: {@code poorEnchantColor = LorenzColor.GRAY}, {@code goodEnchantColor =
 *       LorenzColor.BLUE}, {@code greatEnchantColor = LorenzColor.GOLD}, {@code perfectEnchantColor =
 *       LorenzColor.CHROMA}, {@code ultimateEnchantColor = LorenzColor.LIGHT_PURPLE}, {@code
 *       boldPerfectEnchant = false}. {@code Enchant.getStyle}'s bytecode confirms the tier comparison
 *       ({@code level >= maxLevel} -&gt; Perfect, etc. - see below) reads exactly these four properties, in
 *       that order, so there's no advanced-colour override or hidden per-enchant table involved.</li>
 *   <li>{@code LorenzColor}'s own {@code <clinit>} (same jar) gives each name's literal {@code
 *       java.awt.Color(r,g,b)} - they are exactly Minecraft's 16 legacy chat colours: GRAY = (170,170,170),
 *       BLUE = (85,85,255), GOLD = (255,170,0), LIGHT_PURPLE = (255,85,255). CHROMA is NOT a static colour -
 *       its constructor uses alpha 0 and its {@code toChromaColor}/{@code ChromaManager} path animates a
 *       cycling rainbow. This mod has no chroma-cycling renderer, so {@link
 *       com.killer560.hub.enchantcolors.EnchantColorsConfig#isPerfectChroma()} reimplements just the "Perfect
 *       is an animated rainbow by default" behaviour (see that class and {@code EnchantColorsFeature}), and
 *       {@link #PERFECT} below is only the STATIC fallback used while that toggle is off - not what SkyHanni
 *       actually ships.</li>
 * </ol>
 * {@link #TIERS} (each enchant's {@code goodLevel}/{@code maxLevel}) and {@link #ULTIMATES} are copied
 * straight out of SkyHanni's own repo constants file - not guessed - read from a live SkyHanni install's
 * cache at {@code C:\Users\...\config\skyhanni\repo\constants\Enchants.json} (re-checked 2026-09-27 against
 * the same file), keyed by its {@code loreName} (lower-cased) so every key here is exactly what Hypixel
 * prints, NOT a guess from the NBT id. That distinction matters: an id-derived guess like "dragon hunter"
 * (from the {@code dragon_hunter} enchant id) never appears in real lore - Hypixel prints that enchant as
 * "Gravity" - so it would never match and never get recoloured. See
 * {@link EnchantColorsFeature#ID_TO_LORE_NAME} for the full list of ids whose id and printed name diverge
 * like this (also "Drain"/syphon, "Pyroclasm"/magmarizer, "Woodsplitter"/arcane).
 * <p>
 * <b>2026-09-27 diff against the live {@code Enchants.json}</b> turned up more bugs from the 2026-09-20 pass,
 * fixed here: {@code forest pledge} was {@code goodLevel 2, maxLevel 6}, the file says {@code 2, 5};
 * {@code stealth} was {@code 0, 6}, the file says {@code 0, 1}; {@code thorns} was {@code 3, 4}, the file
 * says {@code 3, 3}. The four mana-vitality-style enchants were keyed under invented names ("hardened
 * vitality", "strong vitality", "vampiric vitality", "vivacious vitality") that never appear in real lore and
 * so could never match - renamed to their actual {@code loreName}s ("hardened mana", "strong mana", "mana
 * vampire", "ferocious mana"; see {@code EnchantColorsFeature#buildIdAliases} - they need no id alias at all,
 * since {@code hardened_mana} etc. already normalise straight to those names). Two entries with no match
 * anywhere in the live repo file at all - {@code karma} and {@code petalfall} - are removed rather than kept
 * on a guessed maximum: per killer560's own rule, "a wrong colour table applied to every enchant is worse
 * than leaving it," so an unconfirmed enchant is left uncoloured (falls through to Unknown/untouched) instead
 * of carrying forward a number nothing here can verify.
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
 * <p>
 * <b>2026-09-27, killer560: "For the enchant color you can refrence skyhanni they have some way of detecting
 * something like gk5 being a t7."</b> The {@code maxLevel} half of every pair below is already the enchant's
 * real ceiling, not the level you'd get from an enchanting table or a plain anvil combine - e.g. {@code
 * giant killer}'s {@code maxLevel} is 7, even though the enchanting table and ordinary book-combining only
 * ever reach V. Spot-checked against hypixelskyblock.minecraft.wiki (2026-09-27, not wiki.hypixel.net or
 * Fandom per killer560's standing instruction) rather than trusted on the SkyHanni decompile alone:
 * <ul>
 *   <li>Giant Killer - table/combine max V, true max VII via the Dark Auction (Scorpius' Darker Auctions
 *       perk) or Experiments; VI and VII are <i>not</i> reachable by combining books at all.</li>
 *   <li>Sharpness - table/combine max V, true max VII (VI from Tomioka/Dark Auction/Experiments, VII an
 *       ultra-rare Experiments/Darker-Auction drop).</li>
 *   <li>Ender Slayer - book combination alone reaches VI (hence {@code goodLevel} 5 here is the enchanting
 *       table's own cap, not the combine cap - combining is what gets you from Good to Great), VII only via
 *       applying an End Stone Idol (Voidgloom Seraph T4 drop, or Experiments) to an Ender Slayer VI item.</li>
 *   <li>Growth - table/combine max V, true max VII via the Dark Auction or Experiments (VI likewise
 *       Dark-Auction/Experiments-only).</li>
 * </ul>
 * All four already matched this table's existing {@code maxLevel} of 7 (or, for Ender Slayer, the existing
 * split between table-cap 5 and combine-reachable 6), so nothing needed correcting - this was verification,
 * not a fix. <b>Left deliberately unverified</b>: the other ~25 enchants sharing the same {@code 5, 7} shape
 * almost certainly follow the identical Experiments/Dark-Auction pattern (that mechanic isn't per-enchant,
 * it's a Skyblock-wide endgame system), but "almost certainly" isn't a wiki citation, so they're not
 * individually confirmed here - if one of them turns out wrong, fix that one entry rather than distrust the
 * whole table. Ultimate enchants are NOT part of this at all despite killer560's phrasing grouping them in:
 * an item can only carry one Ultimate enchant, applying a second overwrites rather than stacks, and the
 * highest tier of any of them is V - there is no "shown level above the normal max" case for an ultimate to
 * detect, which is exactly why {@link #ULTIMATES} still bypasses goodLevel/maxLevel entirely (see
 * {@code EnchantColorsFeature}) instead of getting its own maxLevel entry here.
 * <p>
 * {@link com.killer560.hub.enchantcolors.EnchantColorsConfig#isTrueMaxDetection()} is the toggle that gates
 * this - see its own doc for why it defaults ON and what turning it off falls back to.
 */
public final class EnchantColorsDefaults {

    /** SkyHanni default: {@code LorenzColor.GRAY} = legacy §7, (170,170,170). */
    public static final int POOR = 0xFFAAAAAA;
    /** SkyHanni default: {@code LorenzColor.BLUE} = legacy §9, (85,85,255). */
    public static final int GOOD = 0xFF5555FF;
    /** SkyHanni default: {@code LorenzColor.GOLD} = legacy §6, (255,170,0). */
    public static final int GREAT = 0xFFFFAA00;
    /** Static fallback only, used while {@code EnchantColorsConfig#isPerfectChroma()} is OFF. SkyHanni's real
     *  default for Perfect is an animated rainbow (LorenzColor.CHROMA), not a fixed colour - see this class's
     *  own doc. White was picked as a neutral fallback, not because it's SkyHanni's default - it isn't. */
    public static final int PERFECT = 0xFFFFFFFF;
    /** SkyHanni default: {@code LorenzColor.LIGHT_PURPLE} = legacy §d, (255,85,255). Also the fallback colour
     *  for anything the NBT says is an {@code ultimate_*} id. */
    public static final int ULTIMATE = 0xFFFF55FF;
    /** Anything not in {@link #TIERS}, when "Only Known Enchantments" is off. Hypixel's own lore blue - not
     *  a SkyHanni value, since SkyHanni has no equivalent "recognised but untiered" state. */
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
        t.put("forest pledge", new int[]{2, 5});
        t.put("fortune", new int[]{3, 4});
        t.put("frail", new int[]{5, 7});
        t.put("frost walker", new int[]{2, 2});
        t.put("giant killer", new int[]{5, 7});
        t.put("gravity", new int[]{5, 6});
        t.put("great spook", new int[]{0, 1});
        t.put("green thumb", new int[]{0, 5});
        t.put("growth", new int[]{5, 7});
        t.put("hardened mana", new int[]{0, 10});
        t.put("harvesting", new int[]{5, 6});
        t.put("ice cold", new int[]{0, 5});
        t.put("impaling", new int[]{5, 5});
        t.put("infinite quiver", new int[]{5, 10});
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
        t.put("mana vampire", new int[]{0, 10});
        t.put("overload", new int[]{0, 5});
        t.put("paleontologist", new int[]{0, 5});
        t.put("pesterminator", new int[]{0, 6});
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
        t.put("stealth", new int[]{0, 1});
        t.put("strong mana", new int[]{0, 10});
        t.put("sugar rush", new int[]{0, 3});
        t.put("sunder", new int[]{0, 6});
        t.put("tabasco", new int[]{1, 3});
        t.put("thorns", new int[]{3, 3});
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
        t.put("vampirism", new int[]{5, 6});
        t.put("venomous", new int[]{5, 7});
        t.put("vicious", new int[]{0, 5});
        t.put("ferocious mana", new int[]{0, 10});
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
