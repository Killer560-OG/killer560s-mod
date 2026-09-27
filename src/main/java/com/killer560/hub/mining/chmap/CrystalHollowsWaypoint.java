package com.killer560.hub.mining.chmap;

/**
 * One player-added Crystal Hollows waypoint - an absolute world position (unlike the dungeon's
 * {@code etherwarp.EtherwarpWaypoint}, which is room-relative because dungeon rooms move between runs).
 * Crystal Hollows has no such per-run reshuffle of its own coordinate space, so a plain world (x, y, z) is
 * the correct, simplest representation here.
 */
public final class CrystalHollowsWaypoint {
    public final String name;
    public final double x;
    public final double y;
    public final double z;

    public CrystalHollowsWaypoint(String name, double x, double y, double z) {
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
