package com.killer560.hub.f7spots;

import com.killer560.hub.fastleap.Floor7Tracker;

import java.util.Locale;

/**
 * Which part of the F7/M7 fight an aim spot belongs to. The phase gate reuses {@link Floor7Tracker} (chat-driven
 * phase, position-driven fallback) - no new phase detection.
 * <p>
 * Dragon names are the mod's existing pairing ({@code witherdragons/WitherDragon.java}, ported from Odin's
 * {@code WitherDragonsEnum.kt}): Red = Power, Orange = Flame, Green = Apex, Blue = Ice, Purple = Soul.
 */
public enum AimSituation {
    ANY("Any"),
    P2_STORM("P2 Storm"),
    P3_TERMS("P3 Terminals"),
    P5_RED("P5 Red (Power)"),
    P5_ORANGE("P5 Orange (Flame)"),
    P5_GREEN("P5 Green (Apex)"),
    P5_BLUE("P5 Blue (Ice)"),
    P5_PURPLE("P5 Purple (Soul)");

    public final String label;

    AimSituation(String label) {
        this.label = label;
    }

    public boolean isDragon() {
        return ordinal() >= P5_RED.ordinal();
    }

    /** @return whether this situation is "live" for the given tracker phase (ANY is always live). */
    public boolean activeIn(Floor7Tracker.Phase phase) {
        return switch (this) {
            case ANY -> true;
            case P2_STORM -> phase == Floor7Tracker.Phase.P2;
            case P3_TERMS -> phase == Floor7Tracker.Phase.P3;
            default -> phase == Floor7Tracker.Phase.P5;
        };
    }

    public AimSituation next() {
        AimSituation[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /** Never throws - an unknown / hand-typo'd value in the JSON falls back to {@link #ANY}. */
    public static AimSituation parse(String s) {
        if (s == null || s.isBlank()) {
            return ANY;
        }
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ANY;
        }
    }
}
