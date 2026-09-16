package com.killer560.hub.f7spots;

import com.killer560.hub.fastleap.Floor7Tracker;

import java.util.Locale;

/**
 * One F7/M7 "walk to here" waypoint. Ships with NO built-in coordinates - killer560 adds his own with
 * "Add Waypoint Here" in the F7 Spots tab, or by hand-editing {@code killer560smod-f7spots.json}
 * ({@code "walkWaypointList"}), see {@link F7SpotsConfig} for the exact JSON shape.
 *
 * @param x     block X (a box is drawn on the block at floor(x), floor(y), floor(z))
 * @param y     block Y
 * @param z     block Z
 * @param label shown above the box (may be blank)
 * @param color 0xAARRGGBB, or 0 to use the tab's "Waypoint Color"
 * @param phase "ANY" or "P1".."P5" - the F7 boss phase this waypoint belongs to
 * @param floor "F7", "M7" or "BOTH"
 */
public record WalkWaypoint(double x, double y, double z, String label, int color, String phase, String floor) {

    public static final String ANY_PHASE = "ANY";
    public static final String BOTH_FLOORS = "BOTH";

    /** @return the stored phase as a tracker phase, or null for "ANY" / anything unparseable. */
    public Floor7Tracker.Phase phaseEnum() {
        if (phase == null || phase.isBlank() || ANY_PHASE.equalsIgnoreCase(phase)) {
            return null;
        }
        try {
            Floor7Tracker.Phase p = Floor7Tracker.Phase.valueOf(phase.trim().toUpperCase(Locale.ROOT));
            return p == Floor7Tracker.Phase.UNKNOWN ? null : p;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * @param currentFloor  "F7" / "M7" from {@code DungeonState.getFloor()} (null = unknown)
     * @param currentPhase  the phase from {@link Floor7Tracker}
     * @param phaseFiltered whether "Current Phase Only" is on
     */
    public boolean appliesTo(String currentFloor, Floor7Tracker.Phase currentPhase, boolean phaseFiltered) {
        if (!floorMatches(currentFloor)) {
            return false;
        }
        if (!phaseFiltered) {
            return true;
        }
        Floor7Tracker.Phase mine = phaseEnum();
        // "ANY" waypoints always show; a phase-tagged one shows only once the phase is actually known.
        return mine == null || mine == currentPhase;
    }

    private boolean floorMatches(String currentFloor) {
        if (floor == null || floor.isBlank() || BOTH_FLOORS.equalsIgnoreCase(floor) || ANY_PHASE.equalsIgnoreCase(floor)) {
            return true;
        }
        return currentFloor != null && floor.equalsIgnoreCase(currentFloor);
    }

    public String phaseOrAny() {
        return phase == null || phase.isBlank() ? ANY_PHASE : phase.toUpperCase(Locale.ROOT);
    }

    public String floorOrBoth() {
        return floor == null || floor.isBlank() ? BOTH_FLOORS : floor.toUpperCase(Locale.ROOT);
    }
}
