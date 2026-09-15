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
    private static final Pattern FLOOR = Pattern.compile("^Floor: Floor ([IV]+)$");
    static final Pattern USER_ROLE = Pattern.compile("^ (\\w{1,16}): (Healer|Tank|Mage|Berserk|Archer) \\((\\d+)\\)$");
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
        return parse(slot, loreStrings(stack), currentRole);
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
                    floor = parseRoman(f.group(1));
                    continue;
                }
            }
            Matcher m = USER_ROLE.matcher(line);
            if (m.matches()) {
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
