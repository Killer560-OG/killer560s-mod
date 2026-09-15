package com.killer560.hub.partyfinder;

import java.util.List;

/** Dungeon classes with the colour codes / short names Devonian's {@code DungeonClass} uses in its Party Finder
 *  tooltip. Colour codes use '&amp;' (converted to '§' when rendered). */
public enum DungeonClass {
    ARCHER("Archer", "Arch", 'A', "&c"),
    BERSERK("Berserk", "Bers", 'B', "&6"),
    MAGE("Mage", "Mage", 'M', "&3"),
    HEALER("Healer", "Heal", 'H', "&5"),
    TANK("Tank", "Tank", 'T', "&a"),
    UNKNOWN("Unknown", "Unknown", '?', "&7");

    /** Order Hypixel/Devonian list classes in ("Missing: Healer, Tank, Mage, Berserk, Archer"). */
    public static final List<DungeonClass> PICK_ORDER = List.of(HEALER, TANK, MAGE, BERSERK, ARCHER);

    public final String displayName;
    public final String shortName;
    public final char letter;
    public final String colorCode;

    DungeonClass(String displayName, String shortName, char letter, String colorCode) {
        this.displayName = displayName;
        this.shortName = shortName;
        this.letter = letter;
        this.colorCode = colorCode;
    }

    public static DungeonClass from(String name) {
        for (DungeonClass c : values()) {
            if (c.displayName.equalsIgnoreCase(name)) {
                return c;
            }
        }
        return UNKNOWN;
    }
}
