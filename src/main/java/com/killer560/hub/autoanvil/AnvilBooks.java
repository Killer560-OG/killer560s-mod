package com.killer560.hub.autoanvil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The book rules behind Auto Anvil, kept free of Minecraft types so they can be read (and reasoned about) on their own.
 * <p>
 * <b>killer560's hard rule (2026-10-07): "make sure it only combines the same tier of the exact same book."</b> A pair
 * is two books whose whole SkyBlock identity is equal - see {@link Book#sameBookAs}: same single enchantment id, same
 * level, and every other attribute the item carries equal too (only the per-item {@code uuid}/{@code timestamp}/
 * {@code originTag} are allowed to differ, because no two real books share those). Display names are never read.
 * <p>
 * <b>Books with more than one enchantment are skipped entirely</b>, as QUOI does. Combining two identical multi-enchant
 * books would raise each enchant only where the anvil allows it, so the result could be partly upgraded and partly
 * not, and there is no level-by-level cap check that covers that safely. Single-enchant books are what the anvil's
 * book-levelling is for.
 * <p>
 * <b>The cap.</b> Combining two level-N books makes N+1 only where Hypixel's anvil can make N+1. That is NOT "up to
 * V": most enchantment-table enchants stop at the table's top level (two Sharpness V do not make VI - wiki: "the top
 * level of many enchants - often Level VI - cannot be found on the Enchantment Table, and cannot be made by combining
 * two level V books"), some stop lower (Looting III, Fire Aspect II), and some go well past V by combining
 * (Infinite Quiver to X, Feather Falling VII-X from VI books). {@link #ANVIL_RESULTS} lists, per enchantment id, the
 * result levels the anvil makes; an enchant that is not in it is never combined. Source: the
 * {@code Enchantments/Sword|Bow|Armor|Tool|Fishing Rod|Equipment} tables on hypixelskyblock.minecraft.wiki (read
 * 2026-10-07), whose per-level source column says "Enchantment Table" (levels 1-N) or "Combining Books" for each level,
 * intersected with the levels Hypixel's Bazaar actually lists for that book (api.hypixel.net/v2/skyblock/bazaar
 * {@code ENCHANTMENT_<ID>_<LEVEL>}, the same day) - where the two disagreed, the smaller set won. Ultimate enchants are
 * not in those tables: the wiki's Ultimate Enchantments page gives each one's max level (V for almost all) and a
 * drop/craft source for its lowest level only, so the levels between come from combining - that is inferred, not
 * quoted, and noted per row.
 */
public final class AnvilBooks {

    private AnvilBooks() {
    }

    /**
     * One single-enchantment SkyBlock book.
     *
     * @param enchant the {@code enchantments} key, e.g. {@code sharpness}, {@code ultimate_wise}
     * @param level   its level
     * @param extras  every other ExtraAttributes key/value except {@code id}, {@code enchantments}, {@code uuid},
     *                {@code timestamp} and {@code originTag}, rendered as text; two books must agree on all of them
     */
    public record Book(String enchant, int level, String extras) {
        public boolean sameBookAs(Book o) {
            return o != null && enchant.equals(o.enchant) && level == o.level && extras.equals(o.extras);
        }

        /** The book two of these make, or null when the anvil cannot make it. */
        public Book combined() {
            return canCombine(enchant, level) ? new Book(enchant, level + 1, extras) : null;
        }

        @Override
        public String toString() {
            return enchant + " " + level;
        }
    }

    /** The result levels Hypixel's anvil makes from two books one level lower. See the class doc for sources. */
    static final Map<String, Set<Integer>> ANVIL_RESULTS = buildTable();

    private static Map<String, Set<Integer>> buildTable() {
        Map<String, Set<Integer>> t = new HashMap<>();
        // Enchantment-table enchants whose table goes I-V: two IV make V, two V make nothing.
        for (String id : new String[]{
                // swords
                "bane_of_arthropods", "cleave", "critical", "cubism", "ender_slayer", "execute", "giant_killer",
                "gravity", "lethality", "luck", "prosecute", "sharpness", "smite", "thunderbolt", "thunderlord",
                "titan_killer", "vampirism", "venomous",
                // bows
                "power",
                // armour
                "blast_protection", "fire_protection", "growth", "projectile_protection", "protection",
                // tools
                "efficiency", "harvesting", "woodsplitter",
                // fishing rods (pyroclasm is on both the sword and rod lists)
                "angler", "blessing", "caster", "frail", "luck_of_the_sea", "lure", "magnet", "piscary",
                "pyroclasm", "spiked_hook"}) {
            t.put(id, range(2, 5));
        }
        // Table enchants that stop at IV.
        for (String id : new String[]{"first_strike", "triple_strike"}) {
            t.put(id, range(2, 4));
        }
        // Table enchants that stop at III (the wiki's sword list says I-V for Impaling; the Bazaar and the bow list
        // say I-III, so III).
        for (String id : new String[]{"drain", "experience", "life_steal", "looting", "scavenger", "chance", "snipe",
                "depth_strider", "respiration", "thorns", "fortune", "impaling"}) {
            t.put(id, range(2, 3));
        }
        // Table enchants that stop at II.
        for (String id : new String[]{"fire_aspect", "knockback", "flame", "punch"}) {
            t.put(id, range(2, 2));
        }
        // "Combining Books" levels in the wiki tables.
        t.put("infinite_quiver", range(2, 10));          // I-V table, VI-VII chest or combining, VIII-X combining
        Set<Integer> feather = new java.util.HashSet<>(range(2, 5));
        feather.addAll(range(7, 10));                    // VI is a dungeon drop; VII-X "Combining Feather Falling VI Books"
        t.put("feather_falling", Set.copyOf(feather));
        t.put("overload", range(2, 5));
        t.put("tabasco", range(3, 3));                   // "Combining Tabasco II Books"
        t.put("ice_cold", range(2, 5));
        t.put("pesterminator", range(2, 5));             // VI is an item, not two V books
        t.put("reflection", range(2, 5));
        t.put("scuba", range(2, 5));                     // VI is Vibrant Coral
        t.put("sugar_rush", range(2, 3));
        t.put("tidal", range(2, 3));
        t.put("transylvanian", range(5, 5));
        t.put("forest_pledge", range(4, 5));             // wiki IV-VIII by combining; Bazaar lists III-V only
        t.put("dedication", range(2, 3));                // IV is a visitor reward
        t.put("paleontologist", range(2, 5));
        t.put("prismatic", range(2, 5));
        t.put("charm", range(2, 5));                     // VI is an item
        t.put("corruption", range(2, 5));
        t.put("quick_bite", range(2, 5));
        t.put("cayenne", range(5, 5));
        t.put("green_thumb", range(2, 5));
        t.put("prosperity", range(2, 5));
        t.put("quantum", range(4, 5));                   // wiki IV-VIII by combining; Bazaar lists III-V only
        // Turbo-Crop: wiki "2span4~Combining Books ; 5~Turbo Gourd" - V is also listed under the gourd, so IV is the
        // last level the table credits to combining alone.
        for (String crop : new String[]{"turbo_cactus", "turbo_cane", "turbo_carrot", "turbo_coco", "turbo_melon",
                "turbo_moonflower", "turbo_mushrooms", "turbo_potato", "turbo_pumpkin", "turbo_rose",
                "turbo_sunflower", "turbo_warts", "turbo_wheat"}) {
            t.put(crop, range(2, 4));
        }
        // Ultimates (inferred - see the class doc): max V, dropped/crafted at level I, so II-V are combined.
        for (String id : new String[]{"ultimate_bank", "ultimate_chimera", "ultimate_combo", "ultimate_fatal_tempo",
                "ultimate_flash", "ultimate_inferno", "ultimate_jerry", "ultimate_last_stand", "ultimate_legion",
                "ultimate_no_pain_no_gain", "ultimate_refrigerate", "ultimate_reiterate", "ultimate_rend",
                "ultimate_soul_eater", "ultimate_sunset", "ultimate_swarm", "ultimate_wisdom", "ultimate_wise",
                // these three are also in the Tool table with "2span4/2span5 ~ Combining Books"
                "ultimate_crop_fever", "ultimate_first_impression", "ultimate_missile"}) {
            t.put(id, range(2, 5));
        }
        t.put("ultimate_flowstate", range(2, 3));        // max III
        t.put("ultimate_bobbin_time", range(4, 5));      // crafted at III
        t.put("ultimate_habanero_tactics", range(5, 5)); // Bazaar lists IV-V
        t.put("ultimate_the_one", range(5, 5));          // Bazaar lists IV-V
        // Deliberately absent (never combined): anything the wiki says "cannot be combined in an anvil" (Champion,
        // Hecatomb, Expertise, Absorb, Cultivating, Compact, Toxophilite), single-level books, enchants whose higher
        // levels come only from drops/auctions/NPCs (Vicious, Big Brain, Counter-Strike, Smarty Pants, Mana Steal,
        // Smoldering, Divine Gift, Rainbow, ...), One For All, the Kuudra vitality/mana books (the wiki does not say
        // which levels drop), and every enchant the six tables above do not list at all.
        return Map.copyOf(t);
    }

    private static Set<Integer> range(int from, int to) {
        java.util.Set<Integer> s = new java.util.HashSet<>();
        for (int i = from; i <= to; i++) {
            s.add(i);
        }
        return Set.copyOf(s);
    }

    /** Whether two level-{@code level} books of {@code enchant} make a level {@code level + 1} book in the anvil. */
    public static boolean canCombine(String enchant, int level) {
        Set<Integer> results = ANVIL_RESULTS.get(enchant);
        return results != null && results.contains(level + 1);
    }

    /** A book in a player slot. */
    public record Candidate(int slot, Book book) {
    }

    /**
     * The next pair to combine, or null. Lowest level first, so two pairs of III become two IV before the IVs are
     * looked at - that is what makes a cascade (III+III, III+III, then IV+IV) happen without planning it. Within a
     * level, enchant id order, then slot order, so the choice is deterministic.
     *
     * @param budget when non-null, how many more books of each kind may still be used ({@code null} = no limit);
     *               Auto Anvil passes the session's starting counts here when "Combine Results Again" is off, so a
     *               book it made is never fed back in
     */
    public static int[] pickPair(List<Candidate> candidates, Map<String, Integer> budget) {
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.removeIf(c -> c.book() == null || c.book().combined() == null);
        sorted.sort(Comparator.comparingInt((Candidate c) -> c.book().level())
                .thenComparing(c -> c.book().enchant())
                .thenComparing(c -> c.book().extras())
                .thenComparingInt(Candidate::slot));
        for (int i = 0; i + 1 < sorted.size(); i++) {
            Candidate a = sorted.get(i);
            Candidate b = sorted.get(i + 1);
            if (!a.book().sameBookAs(b.book())) {
                continue;
            }
            if (budget != null && budget.getOrDefault(budgetKey(a.book()), 0) < 2) {
                continue;
            }
            return new int[]{a.slot(), b.slot()};
        }
        return null;
    }

    /** The budget map's key for a book: identity without the slot. */
    public static String budgetKey(Book b) {
        return b.enchant() + "|" + b.level() + "|" + b.extras();
    }
}
