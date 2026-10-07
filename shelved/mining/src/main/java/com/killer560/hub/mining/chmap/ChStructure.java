package com.killer560.hub.mining.chmap;

import java.util.Locale;

/**
 * The named structures of the Crystal Hollows, as the map knows them.
 *
 * <p>killer560 (2026-09-30): "if I find a area like the [Mines of Divan] then it should show the border for
 * [it] on the map. I should have an option to show the waypoint for [it] as well."
 *
 * <p><b>Nothing here carries a position.</b> That is deliberate and it is the whole reason this feature is
 * built around discovery rather than a lookup table: Hypixel places these structures differently in every
 * lobby - the wiki's own wording is that placement "will always be different across different servers" - so
 * a hardcoded polygon would be a drawing of somewhere the player is not. An earlier pass on the map screen
 * declined to draw zone outlines for exactly this reason and said so in its class doc.
 *
 * <p>So a structure's extent is LEARNED, per lobby, and thrown away when the lobby changes. Where it comes
 * from is the only difference between the two maps he asked for: the legit one only ever learns from him
 * standing inside the place, and the cheat one scans for it.
 *
 * <p>The Crystal Nucleus is the exception - it is at a fixed coordinate in every lobby, which
 * {@code CrystalHollowsMapScreen} already relies on - so it is listed for naming and colour but never needs
 * discovering.
 */
public enum ChStructure {

    CRYSTAL_NUCLEUS("Crystal Nucleus", 0xFFB44DFF),
    JUNGLE_TEMPLE("Jungle Temple", 0xFF4CAF50),
    MINES_OF_DIVAN("Mines of Divan", 0xFF4DD0E1),
    GOBLIN_QUEENS_DEN("Goblin Queen's Den", 0xFFFFB300),
    LOST_PRECURSOR_CITY("Lost Precursor City", 0xFF7E57C2),
    KHAZAD_DUM("Khazad-dûm", 0xFFEF5350),
    FAIRY_GROTTO("Fairy Grotto", 0xFFF06292),
    DRAGONS_LAIR("Dragon's Lair", 0xFF8D6E63),
    CORLEONE("Corleone", 0xFFE0E0E0);

    public final String displayName;

    /** Border colour on the map. Distinct enough to tell apart at map scale, not a theme choice. */
    public final int colour;

    ChStructure(String displayName, int colour) {
        this.displayName = displayName;
        this.colour = colour;
    }

    /**
     * The structure a sidebar/area string names, or null.
     *
     * <p>Matched loosely on purpose - the sidebar wording is Hypixel's and has changed before - but only
     * against the distinctive part of each name, so "Jungle" alone never matches the Jungle Temple. That
     * matters because the Crystal Hollows has a JUNGLE BIOME as well as a Jungle Temple, and confusing a
     * biome for a structure would draw a border round a quarter of the map.
     */
    public static ChStructure fromAreaName(String area) {
        if (area == null || area.isBlank()) {
            return null;
        }
        String s = area.toLowerCase(Locale.ROOT);
        if (s.contains("nucleus")) {
            return CRYSTAL_NUCLEUS;
        }
        if (s.contains("jungle temple")) {
            return JUNGLE_TEMPLE;
        }
        if (s.contains("divan")) {
            return MINES_OF_DIVAN;
        }
        if (s.contains("goblin queen") || s.contains("queen's den")) {
            return GOBLIN_QUEENS_DEN;
        }
        if (s.contains("precursor city")) {
            return LOST_PRECURSOR_CITY;
        }
        if (s.contains("khazad")) {
            return KHAZAD_DUM;
        }
        if (s.contains("fairy grotto")) {
            return FAIRY_GROTTO;
        }
        if (s.contains("dragon's lair") || s.contains("dragons lair")) {
            return DRAGONS_LAIR;
        }
        return null;
    }

    /** The enum constant for a saved name, or null - tolerant of a name that no longer exists. */
    public static ChStructure byName(String name) {
        if (name == null) {
            return null;
        }
        for (ChStructure s : values()) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return null;
    }
}
