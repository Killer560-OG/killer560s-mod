package com.killer560.hub.partyfinder;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Party Finder lore parsing - a port of Devonian's {@code api/dungeon/PartyFinderListener.kt} (regexes, data model,
 *  join-status rules). Pure functions; {@link PartyFinderOverlay} decides when to call them. */
public final class PartyFinderParser {

    public static final String PARTY_FINDER_TITLE = "Party Finder";
    public static final String CATACOMBS_GATE_TITLE = "Catacombs Gate";
    /** Devonian reads the selected class from this slot of the Catacombs Gate menu. */
    public static final int GATE_CLASS_SLOT = 45;

    private static final Pattern TYPE = Pattern.compile("^Dungeon: (Master Mode )?(The Catacombs)$");
    /** "Floor: Floor VII", or "Floor: Entrance" (floor 0, Hypixel's key "0" for its times). Devonian matches only the
     *  first, which left an Entrance party with floor -1 and no PB for anyone. */
    private static final Pattern FLOOR = Pattern.compile("^Floor: (?:Floor ([IV]+)|(Entrance))$");
    // killer560 9.1: was anchored ^...$ (the whole line had to be EXACTLY " Name: Class (Level)", nothing
    // after), so anything appended to that line - most notably another mod restyling the same lore (Devonian's
    // Party Finder Overview tacking its own extra stats on the end every tick; see
    // DetectedMods#isDevonianPartyFinderOverviewOn, found 2026-09-21 as the cause of "the current style does
    // not work") - broke the match completely and silently emptied the whole party: no members, "Missing:"
    // listed every class, no per-player PB, ever. The leading "^ " is kept (real Hypixel lines always start
    // with exactly one space then the name - that part was never the problem), only the trailing "$" is
    // dropped and matched with find() instead of matches(), so trailing decoration no longer kills the line.
    static final Pattern USER_ROLE = Pattern.compile("^ (\\w{1,16}): (Healer|Tank|Mage|Berserk|Archer) \\((\\d+)\\)");
    private static final Pattern LOW_CATA = Pattern.compile("^Requires Catacombs Level \\d+!$");
    private static final Pattern LOW_ROLE = Pattern.compile("^Requires a Class at Level \\d+!$");
    private static final Pattern CANNOT_JOIN = Pattern.compile("^Complete previous floor first!$");
    private static final Pattern CURRENTLY_SELECTED = Pattern.compile("^Currently Selected: (Healer|Tank|Mage|Berserk|Archer)$");
    static final Pattern CHAT_CLASS_SELECTED = Pattern.compile("^You have selected the (Healer|Tank|Mage|Berserk|Archer) Dungeon Class!$");

    public enum Status {
        CANNOT_JOIN, // hasn't completed the previous floor
        DUPE_CLASS,
        LOW_CATA,
        LOW_ROLE,
    }

    public record Member(String name, DungeonClass role, int level) {
    }

    public record Party(int slot, int floor, boolean masterMode, List<Member> members, List<DungeonClass> missing,
                        EnumSet<Status> blockers) {
    }

    private PartyFinderParser() {
    }

    /** @return the party shown by a Party Finder head, or null for anything else. */
    public static Party parse(int slot, ItemStack stack, DungeonClass currentRole) {
        if (stack == null || stack.isEmpty() || !stack.is(Items.PLAYER_HEAD)) {
            return null;
        }
        // The SERVER's lore for this slot when we have it, the live stack only as a fallback.
        //
        // Devonian's Party Finder Overview rewrites this lore in place every tick, so the live stack holds its
        // edits rather than Hypixel's text - which is what emptied this overlay entirely (2026-09-21). Loosening
        // the regexes recovered some of it, but no parser can be made proof against arbitrary rewriting by an
        // arbitrary mod. The packet copy cannot be rewritten by anyone. See PartyFinderLoreCache.
        List<String> fromServer = PartyFinderLoreCache.serverLore(slot);
        return parse(slot, fromServer != null ? fromServer : loreStrings(stack), currentRole);
    }

    public static Party parse(int slot, List<String> lore, DungeonClass currentRole) {
        boolean found = false;
        boolean master = false;
        int floor = -1;
        List<Member> members = new ArrayList<>();
        Set<DungeonClass> present = new LinkedHashSet<>();
        EnumSet<Status> blockers = EnumSet.noneOf(Status.class);

        for (String line : lore) {
            if (!found) {
                Matcher type = TYPE.matcher(line);
                if (type.matches()) {
                    found = true;
                    master = type.group(1) != null;
                }
                continue;
            }
            if (LOW_CATA.matcher(line).matches()) {
                blockers.add(Status.LOW_CATA);
                continue;
            }
            if (LOW_ROLE.matcher(line).matches()) {
                blockers.add(Status.LOW_ROLE);
                continue;
            }
            if (CANNOT_JOIN.matcher(line).matches()) {
                blockers.add(Status.CANNOT_JOIN);
                continue;
            }
            if (floor == -1) {
                Matcher f = FLOOR.matcher(line);
                if (f.matches()) {
                    floor = f.group(2) != null ? 0 : parseRoman(f.group(1));
                    continue;
                }
            }
            Matcher m = USER_ROLE.matcher(line);
            if (m.find()) {
                DungeonClass role = DungeonClass.from(m.group(2));
                present.add(role);
                members.add(new Member(m.group(1), role, parseInt(m.group(3))));
            }
        }
        if (!found) {
            return null;
        }
        if (currentRole != null && present.contains(currentRole)) {
            blockers.add(Status.DUPE_CLASS);
        }
        List<DungeonClass> missing = new ArrayList<>();
        for (DungeonClass c : DungeonClass.PICK_ORDER) {
            if (!present.contains(c)) {
                missing.add(c);
            }
        }
        return new Party(slot, floor, master, members, missing, blockers);
    }

    /** "Currently Selected: Mage" on the Catacombs Gate class item, or null. */
    public static DungeonClass selectedClass(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        DungeonClass result = null;
        for (String line : loreStrings(stack)) {
            Matcher m = CURRENTLY_SELECTED.matcher(line);
            if (m.matches()) {
                result = DungeonClass.from(m.group(1));
            }
        }
        return result;
    }

    public static List<String> loreStrings(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(lore.lines().size());
        lore.lines().forEach(c -> out.add(c.getString()));
        return out;
    }

    static int parseRoman(String roman) {
        int total = 0;
        int last = 0;
        for (int i = roman.length() - 1; i >= 0; i--) {
            int v = switch (roman.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                default -> 0;
            };
            total += v < last ? -v : v;
            last = Math.max(last, v);
        }
        return total;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
