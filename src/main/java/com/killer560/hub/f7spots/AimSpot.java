package com.killer560.hub.f7spots;

import com.killer560.hub.dungeonclass.DungeonClass;

import java.util.Locale;

/**
 * One "aim here" marker - a point in the world (not a block), drawn as a small crosshair box.
 *
 * @param x         exact world X to aim at
 * @param y         exact world Y
 * @param z         exact world Z
 * @param label     shown next to the crosshair (may be blank)
 * @param situation which part of the fight it belongs to, see {@link AimSituation}
 * @param clazz     "ANY" or a {@link DungeonClass} name - only shown for that class unless "All Classes" is on
 * @param color     0xAARRGGBB, or 0 to use the tab's "Aim Spot Color"
 */
public record AimSpot(double x, double y, double z, String label, AimSituation situation, String clazz, int color) {

    public static final String ANY_CLASS = "ANY";

    /** @return the class this spot is for, or null for "ANY" / an unknown name. */
    public DungeonClass dungeonClass() {
        if (clazz == null || clazz.isBlank() || ANY_CLASS.equalsIgnoreCase(clazz)) {
            return null;
        }
        return DungeonClass.byName(clazz);
    }

    /**
     * @param self       your class from {@code P5State.selfClass()} (may be null - unknown)
     * @param allClasses whether "Show All Classes" is on
     */
    public boolean appliesTo(DungeonClass self, boolean allClasses) {
        if (allClasses) {
            return true;
        }
        DungeonClass mine = dungeonClass();
        // ANY-class spots always show; a class-tagged spot shows while your class is unknown too, so a
        // tab-list hiccup can't silently hide every marker mid-fight.
        return mine == null || self == null || mine == self;
    }

    public String classOrAny() {
        return clazz == null || clazz.isBlank() ? ANY_CLASS : clazz.toUpperCase(Locale.ROOT);
    }
}
