package com.killer560.hub.livemap.autoclear;

import com.killer560.hub.livemap.DungeonLayout;

import java.util.List;

/**
 * The next door toward Blood, shared by Auto Clear's Door Only run and the fairy door. The old Auto Blood Rush run that
 * lived here is now Dungeons Autopilot's Blood Rush setting (apmenu-1121), which runs through Auto Clear.
 */
public final class BloodRush {

    private BloodRush() {
    }

    /** First locked door on the room path toward the Blood door, else nearest wither, else nearest locked door. */
    public static int nextDoor(DungeonLayout layout) {
        int room = layout.currentRoom();
        if (room < 0) {
            return -1;
        }
        int blood = layout.bloodDoor();
        if (blood >= 0) {
            int[] resolved = DungeonMapPathfinder.resolve(layout, room, blood, true);
            if (resolved != null) {
                int bloodSide = layout.roomOfCell(resolved[1]);
                List<DungeonMapPathfinder.RoomStep> path = bloodSide == room ? List.of()
                        : DungeonMapPathfinder.findPath(layout, room, bloodSide, true);
                if (path != null) {
                    for (DungeonMapPathfinder.RoomStep step : path) {
                        if (step.door() >= 0 && layout.isLocked(step.door())) {
                            return step.door();
                        }
                    }
                    if (layout.isLocked(blood)) {
                        return blood;
                    }
                }
            }
        }
        int best = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int idx = 0; idx < 121; idx++) {
            if (layout.doorType(idx) != DungeonLayout.DOOR_WITHER || !layout.isLocked(idx)) {
                continue;
            }
            int d = DungeonMapPathfinder.getDistToDoor(layout, room, idx, true);
            if (d != Integer.MAX_VALUE && d < bestDist) {
                best = idx;
                bestDist = d;
            }
        }
        return best >= 0 ? best : AutoClearUtils.getLockedDoor(layout);
    }
}
