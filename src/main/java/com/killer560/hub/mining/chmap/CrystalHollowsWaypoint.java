package com.killer560.hub.mining.chmap;

/**
 * One player-added Crystal Hollows waypoint - an absolute world position (unlike the dungeon's
 * {@code etherwarp.EtherwarpWaypoint}, which is room-relative because dungeon rooms move between runs).
 * Name, coordinates and color are editable from the Crystal Hollows Map tab; {@code color} 0 follows the menu theme.
 */
public final class CrystalHollowsWaypoint {
    public String name;
    public double x;
    public double y;
    public double z;
    public int color;

    public CrystalHollowsWaypoint(String name, double x, double y, double z) {
        this(name, x, y, z, 0);
    }

    public CrystalHollowsWaypoint(String name, double x, double y, double z, int color) {
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.color = color;
    }

    /** The color to draw: its own, or the theme accent when none was picked. */
    public int drawColor(int amber) {
        return color != 0 ? color : com.killer560.hub.gui.ThemeAccent.accent(amber);
    }
}
