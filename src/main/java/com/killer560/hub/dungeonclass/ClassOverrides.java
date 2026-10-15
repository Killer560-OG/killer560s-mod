package com.killer560.hub.dungeonclass;

import com.killer560.hub.leapmenu.LeapMenuConfig;

import java.util.Map;

/**
 * MOD-WIDE manual class picks: player -&gt; {@link DungeonClass}. Since 2026-10-15 (killer560: "Remove the class selection
 * overrides setting and instead have it use the /leaporder process") there is no Class Overrides settings tab and no
 * file of its own: the picks live in the Leap Order's data ({@link LeapMenuConfig}, {@code killer560smod-leapmenu.json})
 * and are set in the Leap Order editor ({@code /leaporder}, {@code /class}) or on the class-selection screen. A
 * {@code killer560smod-classoverrides.json} from an older version is moved into that data the first time it loads.
 * <p>
 * This class stays as the one static front every feature reads through, so nothing else changed: {@link #classOf} is the
 * pick when one exists, else whatever the caller detected, case-insensitive on the IGN ({@code leapmenu/PartyTracker.classOf},
 * which the Leap Menu, Live Map, Run Stats, Teammates, Fast Leap's class targeting, AP3's leap nodes and
 * {@code dungeonalerts/ClassColors} all sit on). The reason it exists: "in case I am in a party with say five mages but one
 * mage is doing berserk's term, one is doing archer's term" - a pick is about the ROLE a person is playing. Keyed on UUID
 * once one resolves (the standing rule), kept under the typed IGN until then. Not gated on the cheat build: a display and
 * targeting preference. {@link #set} and {@link #clear} save at once.
 */
public final class ClassOverrides {

    public static final int MAX_ENTRIES = LeapMenuConfig.MAX_PICKS;
    public static final int MAX_IGN = 16;

    private ClassOverrides() {
    }

    /** The class to treat {@code ign} as: the pick when one exists, else {@code detected}. Null-safe on both. */
    public static DungeonClass classOf(String ign, DungeonClass detected) {
        if (ign == null) {
            return detected;
        }
        DungeonClass picked = LeapMenuConfig.getInstance().pickOf(ign);
        return picked == null ? detected : picked;
    }

    /** True when {@code ign} has a pick. */
    public static boolean has(String ign) {
        return LeapMenuConfig.getInstance().hasPick(ign);
    }

    /** Sets (or replaces) the pick for {@code ign} and saves. A null class clears it. @return true when stored. */
    public static boolean set(String ign, DungeonClass cls) {
        return LeapMenuConfig.getInstance().setPick(ign, cls);
    }

    /** Removes the pick for {@code ign} and saves. @return true when one existed. */
    public static boolean clear(String ign) {
        return LeapMenuConfig.getInstance().clearPick(ign);
    }

    /** Removes every pick and saves. */
    public static void clearAll() {
        LeapMenuConfig.getInstance().clearAllPicks();
    }

    /** Every pick, current display name -&gt; class, in insertion order. A copy: mutate through {@link #set}/{@link #clear}. */
    public static Map<String, DungeonClass> all() {
        return LeapMenuConfig.getInstance().allPicks();
    }
}
