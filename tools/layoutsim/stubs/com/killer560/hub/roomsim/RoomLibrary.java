package com.killer560.hub.roomsim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Offline stand-in: just the fields RoomDoors reads, filled by LayoutSim from the capture files. */
public final class RoomLibrary {
    public static final int TILE = 31;
    public static final int WALL_MARGIN = 1;
    public static final Map<String, Room> ROOMS = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    public static Room get(String name) {
        return ROOMS.get(name);
    }

    public static final class Room {
        public final String name;
        public int sizeX;
        public int sizeZ;
        public short[] blocks;
        public final List<String> palette = new ArrayList<>();
        public int margin = WALL_MARGIN;
        public final int minY;
        public final int maxY;
        public boolean usable = true;

        public Room(String name, int sizeX, int sizeZ, int minY, int maxY) {
            this.name = name;
            this.sizeX = sizeX;
            this.sizeZ = sizeZ;
            this.minY = minY;
            this.maxY = maxY;
            this.blocks = new short[sizeX * sizeZ * (maxY - minY + 1)];
            java.util.Arrays.fill(this.blocks, (short) -1);
        }

        int index(int x, int y, int z) {
            return (y - minY) * sizeX * sizeZ + z * sizeX + x;
        }

        public boolean usable() {
            return usable;
        }
    }
}
