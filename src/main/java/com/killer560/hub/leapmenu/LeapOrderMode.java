package com.killer560.hub.leapmenu;

import com.killer560.hub.dungeonclass.DungeonClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The Leap Order's default order (killer560, 2026-10-09: "once I set everyone's class then it should automatically move
 * them where they would normally be ... for leaping there should be a few filter options: A-Z by class name, Z-A by class
 * name, the reference leaporder and whatnot"). Every teammate the player has not moved by hand goes where this puts them.
 * <ul>
 *   <li>{@link #ODIN}: the Odin order (a setting label) with the quadrants and priorities of
 *       the dungeon class table: Archer top-left, Berserk top-right, Healer
 *       bottom-left, Mage and Tank both bottom-right. Teammates are taken by priority (Berserk 0, Tank 1, the rest 2;
 *       ties keep their order), each into its class's spot if it is still free, and whoever lost their spot fills the
 *       free ones left to right, top to bottom. So with a Mage AND a Tank in the party the Tank gets bottom-right.</li>
 *   <li>{@link #CLASS_AZ}: class name A-Z, then player name - the "A-Z Class" ({@code compareBy({ it.clazz.ordinal }, { it.name })}, its enum is alphabetical) and the leap overlay
 *       ({@code PlayerReference.COMPARATOR}: class display name, then name).</li>
 *   <li>{@link #CLASS_ZA}: class name Z-A, then player name A-Z.</li>
 *   <li>{@link #HYPIXEL}: no sorting - the order Hypixel's own Spirit Leap menu shows them in (the "No Sorting").</li>
 * </ul>
 * A teammate whose class is not known yet always comes after every teammate whose class is, in the order given.
 */
public enum LeapOrderMode {
    ODIN("Odin"),
    CLASS_AZ("A-Z Class"),
    CLASS_ZA("Z-A Class"),
    HYPIXEL("Hypixel");

    private final String label;

    LeapOrderMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static LeapOrderMode byName(String name) {
        if (name != null) {
            for (LeapOrderMode m : values()) {
                if (m.name().equalsIgnoreCase(name)) {
                    return m;
                }
            }
        }
        return ODIN;
    }

    /** The {@code DungeonClass.defaultQuadrant} (0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right). */
    static int odinQuadrant(DungeonClass c) {
        return switch (c) {
            case ARCHER -> 0;
            case BERSERKER -> 1;
            case HEALER -> 2;
            case MAGE, TANK -> 3;
        };
    }

    /** The {@code DungeonClass.priority} (lower picks its quadrant first). */
    static int odinPriority(DungeonClass c) {
        return switch (c) {
            case BERSERKER -> 0;
            case TANK -> 1;
            case ARCHER, HEALER, MAGE -> 2;
        };
    }

    /**
     * Places {@code players} (nobody pinned) into the free spots of {@code out} ({@code null} = free). Players beyond the
     * free spots are dropped, as the 4-spot menu has to.
     */
    void place(List<String> players, String[] out, Function<String, DungeonClass> classOf) {
        List<String> known = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String p : players) {
            (classOf.apply(p) != null ? known : unknown).add(p);
        }
        List<String> rest = new ArrayList<>();
        switch (this) {
            case ODIN -> {
                // List.sort is stable: equal priorities keep the order given, as Kotlin's sortedBy does in the reference.
                known.sort(Comparator.comparingInt(p -> odinPriority(classOf.apply(p))));
                for (String p : known) {
                    int q = odinQuadrant(classOf.apply(p));
                    if (out[q] == null) {
                        out[q] = p;
                    } else {
                        rest.add(p);
                    }
                }
            }
            case CLASS_AZ -> {
                known.sort(Comparator.<String, String>comparing(p -> classOf.apply(p).displayName())
                        .thenComparing(p -> p.toLowerCase(Locale.ROOT)));
                rest.addAll(known);
            }
            case CLASS_ZA -> {
                known.sort(Comparator.<String, String>comparing(p -> classOf.apply(p).displayName()).reversed()
                        .thenComparing(p -> p.toLowerCase(Locale.ROOT)));
                rest.addAll(known);
            }
            case HYPIXEL -> rest.addAll(known);
        }
        if (this == HYPIXEL) {
            rest = new ArrayList<>(players);   // no sorting at all: unknown classes keep their place too
        } else {
            rest.addAll(unknown);
        }
        for (int i = 0; i < out.length && !rest.isEmpty(); i++) {
            if (out[i] == null) {
                out[i] = rest.remove(0);
            }
        }
    }
}
