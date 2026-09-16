package com.killer560.hub.livemap;

import com.killer560.hub.chunkcache.ChunkCacheManager;
import com.killer560.hub.roomdatabase.RoomEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Immutable snapshot of the scanned dungeon (rooms, doors, lock state) taken on the client thread, so the teleport
 * pathfinders ({@code livemap.autoclear}) can read it from worker threads without racing {@link LiveMapFeature}'s
 * regrouping. The QUOI equivalent is {@code ScanUtils.grid} ({@code OdonRoom}/{@code OdonDoor} per cell).
 */
public final class DungeonLayout {

    public static final int GRID = 11;
    public static final int DOOR_NONE = 0;
    public static final int DOOR_NORMAL = 1;
    public static final int DOOR_WITHER = 2;
    public static final int DOOR_BLOOD = 3;
    public static final int DOOR_ENTRANCE = 4;

    /** Room id per cell (tiles and connectors), or -1. */
    private final int[] roomOf = new int[GRID * GRID];
    private final int[] doorType = new int[GRID * GRID];
    private final boolean[] doorLocked = new boolean[GRID * GRID];
    private final String[] names;
    private final RoomEntry[] entries;
    private final int[][] clayRotation;
    private final int[][] tiles;
    private final float[] labelGX;
    private final float[] labelGZ;
    private final int currentRoom;

    private DungeonLayout(int roomCount) {
        names = new String[roomCount];
        entries = new RoomEntry[roomCount];
        clayRotation = new int[roomCount][];
        tiles = new int[roomCount][];
        labelGX = new float[roomCount];
        labelGZ = new float[roomCount];
        java.util.Arrays.fill(roomOf, -1);
        currentRoom = -1;
    }

    private DungeonLayout(DungeonLayout base, int currentRoom) {
        System.arraycopy(base.roomOf, 0, roomOf, 0, roomOf.length);
        System.arraycopy(base.doorType, 0, doorType, 0, doorType.length);
        System.arraycopy(base.doorLocked, 0, doorLocked, 0, doorLocked.length);
        names = base.names;
        entries = base.entries;
        clayRotation = base.clayRotation;
        tiles = base.tiles;
        labelGX = base.labelGX;
        labelGZ = base.labelGZ;
        this.currentRoom = currentRoom;
    }

    /** Must be called on the client thread. */
    public static DungeonLayout capture() {
        Minecraft client = Minecraft.getInstance();
        List<LiveMapFeature.RoomGroup> groups = LiveMapFeature.groupsView();
        DungeonLayout layout = new DungeonLayout(groups.size());
        for (int gid = 0; gid < groups.size(); gid++) {
            LiveMapFeature.RoomGroup group = groups.get(gid);
            layout.names[gid] = group.entry != null && group.entry.name != null ? group.entry.name : "Unknown";
            layout.entries[gid] = group.entry;
            layout.clayRotation[gid] = LiveMapFeature.clayAndRotation(group);
            layout.tiles[gid] = group.tiles.clone();
            layout.labelGX[gid] = group.labelGX;
            layout.labelGZ[gid] = group.labelGZ;
            for (int c : group.cells) {
                layout.roomOf[c] = gid;
            }
        }
        for (int idx = 0; idx < GRID * GRID; idx++) {
            if (layout.roomOf[idx] >= 0) {
                continue;
            }
            LiveMapFeature.Tile tile = LiveMapFeature.effectiveTile(idx);
            LiveMapFeature.Tile mapDoor = DungeonMapScanner.doorTileAt(idx);
            int type = switch (tile) {
                case DOOR_NORMAL -> mapDoor == LiveMapFeature.Tile.DOOR_WITHER ? DOOR_WITHER : DOOR_NORMAL;
                case DOOR_WITHER -> DOOR_WITHER;
                case DOOR_BLOOD -> DOOR_BLOOD;
                case DOOR_ENTRANCE -> DOOR_ENTRANCE;
                default -> DOOR_NONE;
            };
            layout.doorType[idx] = type;
            if (type == DOOR_WITHER || type == DOOR_BLOOD) {
                BlockPos pos = doorBlock(idx);
                if (client.level != null && ChunkCacheManager.isLoadedOrCached(client.level, pos)) {
                    layout.doorLocked[idx] = !client.level.getBlockState(pos).isAir();
                } else {
                    // Out of render distance: trust the map (an opened wither door turns normal-coloured there).
                    layout.doorLocked[idx] = type == DOOR_BLOOD || mapDoor == LiveMapFeature.Tile.DOOR_WITHER;
                }
            }
        }
        int current = -1;
        if (client.player != null) {
            current = layout.roomAtWorld(client.player.getX(), client.player.getZ());
        }
        return new DungeonLayout(layout, current);
    }

    /** World position of a door cell's lock block (QUOI {@code OdonDoor.pos} at y 69). */
    public static BlockPos doorBlock(int idx) {
        return new BlockPos(LiveMapFeature.START_X + (idx % GRID) * LiveMapFeature.HALF_ROOM, 69,
                LiveMapFeature.START_Z + (idx / GRID) * LiveMapFeature.HALF_ROOM);
    }

    /** World centre of a cell at y 70 (QUOI {@code RoomTile.blockPos}). */
    public static BlockPos cellCenter(int idx) {
        return new BlockPos(LiveMapFeature.START_X + (idx % GRID) * LiveMapFeature.HALF_ROOM, 70,
                LiveMapFeature.START_Z + (idx / GRID) * LiveMapFeature.HALF_ROOM);
    }

    public int roomCount() {
        return names.length;
    }

    public int roomOfCell(int idx) {
        return idx >= 0 && idx < roomOf.length ? roomOf[idx] : -1;
    }

    /** QUOI {@code ScanUtils.getRoomFromPos}: clamped grid tile under a world position, or -1. */
    public int roomAtWorld(double x, double z) {
        int gx = Math.max(0, Math.min(10, (int) Math.round(((int) x - LiveMapFeature.START_X) / 32.0) * 2));
        int gz = Math.max(0, Math.min(10, (int) Math.round(((int) z - LiveMapFeature.START_Z) / 32.0) * 2));
        return roomOf[gz * GRID + gx];
    }

    public int doorType(int idx) {
        return doorType[idx];
    }

    public boolean isDoor(int idx) {
        return doorType[idx] != DOOR_NONE;
    }

    public boolean isLocked(int idx) {
        return doorLocked[idx];
    }

    public String name(int room) {
        return room >= 0 ? names[room] : null;
    }

    public RoomEntry entry(int room) {
        return room >= 0 ? entries[room] : null;
    }

    /** @return {@code [clayX, clayZ, rotation]} or null. */
    public int[] clayRotation(int room) {
        return room >= 0 ? clayRotation[room] : null;
    }

    public int[] tiles(int room) {
        return tiles[room];
    }

    public float labelGX(int room) {
        return labelGX[room];
    }

    public float labelGZ(int room) {
        return labelGZ[room];
    }

    public int currentRoom() {
        return currentRoom;
    }

    public int bloodDoor() {
        for (int i = 0; i < doorType.length; i++) {
            if (doorType[i] == DOOR_BLOOD) {
                return i;
            }
        }
        return -1;
    }

    public static Vec3 doorCentre(int idx) {
        BlockPos p = doorBlock(idx);
        return new Vec3(p.getX() + 0.5, 71.0, p.getZ() + 0.5);
    }
}
