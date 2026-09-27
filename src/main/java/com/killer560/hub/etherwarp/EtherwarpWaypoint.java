package com.killer560.hub.etherwarp;

import java.util.UUID;

/**
 * One personal etherwarp/secret-location bookmark - see {@link EtherwarpFeature}'s class doc for why this is
 * a room-relative position rather than an absolute one.
 * <p>
 * killer560, 2026-09-27: "Make sure they dont save based off of location but off of location in a room." So,
 * same convention every other per-room-relative position in this mod already uses ({@code RoomEntry.Pos},
 * {@code RoomDatabase.toRealCoord}/{@code toRelativeCoord}): {@link #roomName} names the room TEMPLATE this
 * spot belongs to and {@link #relX}/{@link #relY}/{@link #relZ} are its position in that room's own
 * unrotated frame - the same numbers regardless of which grid cell, rotation or dungeon run the room lands
 * in. Turning that back into a real world position for THIS run needs the room's current
 * clay position/rotation (see {@code LiveMapFeature.currentRoomClayAndRotation}), exactly like every secret
 * waypoint.
 */
public final class EtherwarpWaypoint {
    public final String id;
    public String name;
    public final String roomName;
    public final int relX;
    public final int relY;
    public final int relZ;
    /** Placement order within {@link #roomName} - what "numbered in order of the room" numbers by default. */
    public final int order;

    public EtherwarpWaypoint(String id, String name, String roomName, int relX, int relY, int relZ, int order) {
        this.id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        this.name = name;
        this.roomName = roomName;
        this.relX = relX;
        this.relY = relY;
        this.relZ = relZ;
        this.order = order;
    }
}
