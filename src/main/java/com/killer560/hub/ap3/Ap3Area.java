package com.killer560.hub.ap3;

import java.util.Locale;

/**
 * Which set of nodes a chain belongs to. There is exactly one: the boss room.
 * <p>
 * killer560 (2026-09-23): "remove this split by section thing. there should be no sections like storm or p3 or
 * whatever just boss room is the only split you need from the actual dungeon... it is the thing that hides stuff
 * outside of the section i am in."
 * <p>
 * It used to be a boss phase plus, in P3, the terminal section you stood in (S1-S5), and only that area's nodes were
 * ever shown or armed. So placing a node meant being inside the right box, every other node in the fight was
 * invisible while you worked, and standing in P3 outside all five sections meant AP3 refused to do anything at all.
 * One chain for the whole boss room ends that: everything is visible, everything is armed, and where you happen to
 * be standing no longer decides what you can see or edit.
 * <p>
 * The type survives its one value on purpose - the split he does want, boss room against the dungeon proper, is
 * another constant here the day AP3 runs outside the boss.
 * <p>
 * Chains are keyed {@code "BOSS"} / {@code "BOSS:MAGE"}. Older files keyed by area ({@code "S3"}, {@code "P1"},
 * {@code "P4:TANK"}) still load: every one of those keys reads as this area, and chains that collide because of it
 * are merged rather than dropped - see {@code Ap3Store.readChain}.
 */
public enum Ap3Area {

    /** The F7/M7 boss room, end to end: P1 through P5, every section. */
    BOSS;

    /** Stable file / chat key. */
    public String key() {
        return "BOSS";
    }

    /** What the player reads. */
    public String label() {
        return "Boss";
    }

    /** Longer form for status lines. */
    public String longLabel() {
        return "Boss Room";
    }

    /**
     * The area a chain key names, or null when it is not one.
     * <p>
     * Accepts every key the per-area files used - {@code "S1".."S5"}, {@code "P1".."P5"}, with or without a
     * {@code ":CLASS"} suffix - because they all describe somewhere inside the boss room, which is this area.
     * Anything else is a typo and is refused rather than quietly swept in here.
     */
    public static Ap3Area parseKey(String key) {
        if (key == null) {
            return null;
        }
        String t = key.trim().toUpperCase(Locale.ROOT);
        int colon = t.indexOf(':');
        String s = colon < 0 ? t : t.substring(0, colon);
        if (s.equals("BOSS")) {
            return BOSS;
        }
        // Legacy per-area keys: S1-S5 (P3 sections) and P1-P5 (whole phases).
        if (s.length() == 2 && (s.charAt(0) == 'S' || s.charAt(0) == 'P')
                && s.charAt(1) >= '1' && s.charAt(1) <= '5') {
            return BOSS;
        }
        return null;
    }

    @Override
    public String toString() {
        return key();
    }
}
