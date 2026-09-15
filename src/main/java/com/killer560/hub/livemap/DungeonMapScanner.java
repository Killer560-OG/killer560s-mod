package com.killer560.hub.livemap;

import com.killer560.hub.secrets.DungeonState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the vanilla Hypixel dungeon map item (hotbar slot 9) into the same 11x11 room/door grid
 * {@link LiveMapFeature} scans from the world - a straight port of NoammAddons' own
 * {@code MapUtils.calibrateMap} + {@code HotbarMapScanner} (26.1.2 branch), with QUOI's
 * {@code MapRenderer.update} as a cross-check (same calibration constants, same color ids).
 * <p>
 * Used for two things only: (1) room connectivity for cells whose chunks the world scan hasn't loaded
 * yet (a room "separator" pixel run on the map = the two tiles are one multi-tile room), and (2) each
 * tile's cleared/green/failed/unopened state for the map's checkmarks. The world scan stays
 * authoritative wherever it has data; with no map (p3sim.net, boss, before the run starts) every
 * accessor here just reports "nothing known" and the live map behaves exactly as it did before.
 * <p>
 * Caching: only the 242 sample pixels (one center + one side pixel per grid cell) are read per update,
 * and cells are only re-classified when one of those bytes actually changed - never a per-frame scan.
 */
final class DungeonMapScanner {

    static final int KIND_NONE = 0;
    static final int KIND_ROOM = 1;
    static final int KIND_SEPARATOR = 2;
    static final int KIND_DOOR = 3;

    // Same order/ordinals as NoammAddons' RoomState - lower ordinal = further progressed.
    static final int STATE_GREEN = 0;
    static final int STATE_CLEARED = 1;
    static final int STATE_DISCOVERED = 2;
    static final int STATE_FAILED = 3;
    static final int STATE_UNOPENED = 4;
    static final int STATE_UNDISCOVERED = 5;

    private static final int GRID = 11;
    private static final int CELLS = GRID * GRID;
    private static final int MAP_SIZE = 128;
    private static final int COLOR_ENTRANCE = 30;
    private static final int COLOR_PUZZLE = 66;
    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-livemap");

    private static final byte[] centerColors = new byte[CELLS];
    private static final byte[] sideColors = new byte[CELLS];
    private static final int[] kinds = new int[CELLS];
    private static final int[] states = new int[CELLS];
    private static final LiveMapFeature.Tile[] doorTiles = new LiveMapFeature.Tile[CELLS];
    private static boolean sampled = false;

    private static boolean calibrated = false;
    private static int mapRoomSize = 16;
    private static int startCornerX = 5;
    private static int startCornerZ = 5;
    private static long nextCalibrationAttemptMs = 0;

    static {
        reset();
    }

    private DungeonMapScanner() {
    }

    static void reset() {
        java.util.Arrays.fill(centerColors, (byte) 0);
        java.util.Arrays.fill(sideColors, (byte) 0);
        java.util.Arrays.fill(kinds, KIND_NONE);
        java.util.Arrays.fill(states, STATE_UNDISCOVERED);
        java.util.Arrays.fill(doorTiles, LiveMapFeature.Tile.UNKNOWN);
        sampled = false;
        calibrated = false;
        mapRoomSize = 16;
        startCornerX = 5;
        startCornerZ = 5;
        nextCalibrationAttemptMs = 0;
    }

    static boolean isCalibrated() {
        return calibrated;
    }

    static int kindAt(int idx) {
        return calibrated ? kinds[idx] : KIND_NONE;
    }

    static int stateAt(int idx) {
        return calibrated ? states[idx] : STATE_UNDISCOVERED;
    }

    static LiveMapFeature.Tile doorTileAt(int idx) {
        return calibrated && kinds[idx] == KIND_DOOR ? doorTiles[idx] : LiveMapFeature.Tile.UNKNOWN;
    }

    /** Interactive map: the map's room colour id for a room cell (NoammAddons {@code RoomType.fromMapColor}: 18 blood,
     *  82 fairy, 34 rare, 74 champion, 66 puzzle, 62 trap, 63/85 normal, 30 entrance), or 0 when unknown. */
    static int roomColorAt(int idx) {
        return calibrated && kinds[idx] == KIND_ROOM ? sideColors[idx] & 0xFF : 0;
    }

    /** Interactive map: player markers from the dungeon map item's decorations, in the order Hypixel sends them -
     *  NoammAddons {@code MapUpdater.updatePlayers} / QUOI {@code MapRenderer.update}. Each entry is
     *  {@code {worldX, worldZ, yawDegrees, isSelf(1/0)}} using NoammAddons' {@code DungeonPlayer.getRealPos}
     *  transform. Empty without a calibrated map (p3sim, boss). */
    static java.util.List<double[]> playerMarkers(Minecraft client) {
        java.util.List<double[]> out = new java.util.ArrayList<>();
        if (!calibrated) {
            return out;
        }
        MapItemSavedData data = findDungeonMap(client);
        if (data == null) {
            return out;
        }
        double multiplier = (mapRoomSize + 4.0) / 32.0;
        for (net.minecraft.world.level.saveddata.maps.MapDecoration decoration : data.getDecorations()) {
            boolean self = decoration.type().value() == net.minecraft.world.level.saveddata.maps.MapDecorationTypes.FRAME.value();
            int mapX = (decoration.x() + 128) >> 1;
            int mapZ = (decoration.y() + 128) >> 1;
            double worldX = (mapX - startCornerX) / multiplier - 185 - 15;
            double worldZ = (mapZ - startCornerZ) / multiplier - 185 - 15;
            out.add(new double[]{worldX, worldZ, decoration.rot() * 22.5, self ? 1 : 0});
        }
        return out;
    }

    /** @return true when any sampled map pixel changed since the last call (cells were re-classified). */
    static boolean update(Minecraft client) {
        MapItemSavedData data = findDungeonMap(client);
        if (data == null || data.colors == null || data.colors.length < MAP_SIZE * MAP_SIZE) {
            return false;
        }
        byte[] colors = data.colors;
        if (!calibrated) {
            long now = System.currentTimeMillis();
            if (now < nextCalibrationAttemptMs) {
                return false;
            }
            nextCalibrationAttemptMs = now + 1000;
            if (!calibrate(colors)) {
                return false;
            }
        }

        int halfRoom = mapRoomSize / 2;
        int halfTile = halfRoom + 2;
        int startX = startCornerX + halfRoom;
        int startY = startCornerZ + halfRoom;
        boolean changed = !sampled;
        for (int x = 0; x < GRID; x++) {
            for (int y = 0; y < GRID; y++) {
                int idx = y * GRID + x;
                int mapX = startX + x * halfTile;
                int mapY = startY + y * halfTile;
                byte center = 0;
                byte side = 0;
                if (mapX < MAP_SIZE && mapY < MAP_SIZE) {
                    center = colors[mapY * MAP_SIZE + mapX];
                    int sideIndex;
                    if (x % 2 == 0 && y % 2 == 0) {
                        sideIndex = (mapY - halfRoom) * MAP_SIZE + (mapX - halfRoom);
                    } else if (y % 2 == 1) {
                        sideIndex = mapY * MAP_SIZE + mapX - 4;
                    } else {
                        sideIndex = (mapY - 4) * MAP_SIZE + mapX;
                    }
                    side = sideIndex >= 0 && sideIndex < colors.length ? colors[sideIndex] : 0;
                }
                if (center != centerColors[idx] || side != sideColors[idx]) {
                    centerColors[idx] = center;
                    sideColors[idx] = side;
                    changed = true;
                }
            }
        }
        sampled = true;
        if (!changed) {
            return false;
        }
        for (int x = 0; x < GRID; x++) {
            for (int y = 0; y < GRID; y++) {
                classify(y * GRID + x, x, y);
            }
        }
        return true;
    }

    /** Port of NoammAddons' {@code HotbarMapScanner.scanTile} + the monotonic state merge in
     *  {@code MapUpdater.updateRooms} (a tile's state only moves forward, except puzzles which can fail). */
    private static void classify(int idx, int x, int y) {
        int center = centerColors[idx] & 0xFF;
        int side = sideColors[idx] & 0xFF;
        if (center == 0) {
            return;
        }
        int kind;
        int state;
        boolean puzzle = false;
        if (x % 2 == 0 && y % 2 == 0) {
            if (!isRoomColor(side)) {
                return;
            }
            kind = KIND_ROOM;
            puzzle = side == COLOR_PUZZLE;
            state = switch (center) {
                case 18 -> side == 18 ? STATE_DISCOVERED : (puzzle ? STATE_FAILED : STATE_UNDISCOVERED);
                case 30 -> side == COLOR_ENTRANCE ? STATE_DISCOVERED : STATE_GREEN;
                case 34 -> STATE_CLEARED;
                case 85, 119 -> STATE_UNOPENED;
                default -> STATE_DISCOVERED;
            };
        } else if (side == 0) {
            LiveMapFeature.Tile door = doorFromColor(center);
            if (door == null) {
                return;
            }
            kind = KIND_DOOR;
            doorTiles[idx] = door;
            state = center == 85 ? STATE_UNOPENED : STATE_DISCOVERED;
        } else {
            if (!isRoomColor(side)) {
                return;
            }
            kind = KIND_SEPARATOR;
            state = STATE_DISCOVERED;
        }
        if (kinds[idx] != kind) {
            kinds[idx] = kind;
        }
        if (state < states[idx] || puzzle) {
            states[idx] = state;
        }
    }

    /** NoammAddons {@code RoomType.fromMapColor}. */
    private static boolean isRoomColor(int color) {
        return switch (color) {
            case 18, 82, 34, 74, 66, 62, 63, 85, 30 -> true;
            default -> false;
        };
    }

    /** NoammAddons {@code DoorType.fromMapColor}. */
    private static LiveMapFeature.Tile doorFromColor(int color) {
        return switch (color) {
            case 18 -> LiveMapFeature.Tile.DOOR_BLOOD;
            case 30 -> LiveMapFeature.Tile.DOOR_ENTRANCE;
            case 119 -> LiveMapFeature.Tile.DOOR_WITHER;
            case 74, 82, 66, 62, 85, 63 -> LiveMapFeature.Tile.DOOR_NORMAL;
            default -> null;
        };
    }

    /** Port of NoammAddons' {@code MapUtils.calibrateMap}/{@code findEntranceCorner}: the entrance room is
     *  the first horizontal run of color 30 at least 16 pixels long; its length (16 or 18) is the map's
     *  room size, and the per-floor start corner follows from it. */
    private static boolean calibrate(byte[] colors) {
        int currLength = 0;
        int start = 0;
        int foundStart = -1;
        int foundLength = 0;
        for (int i = 0; i < colors.length; i++) {
            if (colors[i] == COLOR_ENTRANCE) {
                if (currLength == 0) {
                    start = i;
                }
                currLength++;
            } else {
                if (currLength >= 16) {
                    foundStart = start;
                    foundLength = currLength;
                    break;
                }
                currLength = 0;
            }
        }
        if (foundStart < 0) {
            foundStart = start;
            foundLength = currLength;
        }
        if (foundLength != 16 && foundLength != 18) {
            return false;
        }
        mapRoomSize = foundLength;
        switch (floorNumber()) {
            case 0 -> {
                startCornerX = 22;
                startCornerZ = 22;
            }
            case 1 -> {
                startCornerX = 22;
                startCornerZ = 11;
            }
            case 2, 3 -> {
                startCornerX = 11;
                startCornerZ = 11;
            }
            default -> {
                startCornerX = (foundStart & 127) % (mapRoomSize + 4);
                startCornerZ = (foundStart >> 7) % (mapRoomSize + 4);
            }
        }
        calibrated = true;
        sampled = false;
        LOGGER.info("[LiveMap] Dungeon map calibrated: roomSize={} startCorner=({},{}) floor={}",
                mapRoomSize, startCornerX, startCornerZ, DungeonState.getFloor());
        return true;
    }

    private static int floorNumber() {
        String floor = DungeonState.getFloor();
        if (floor == null || floor.isEmpty()) {
            return -1;
        }
        if (floor.equals("E")) {
            return 0;
        }
        char last = floor.charAt(floor.length() - 1);
        return Character.isDigit(last) ? last - '0' : -1;
    }

    /** Hypixel keeps the dungeon map in hotbar slot 9 (NoammAddons reads {@code getHotbarSlot(8)}). */
    private static MapItemSavedData findDungeonMap(Minecraft client) {
        if (client.player == null || client.level == null) {
            return null;
        }
        ItemStack stack = client.player.getInventory().getItem(8);
        MapId mapId = stack.get(DataComponents.MAP_ID);
        if (mapId == null) {
            return null;
        }
        return client.level.getMapData(mapId);
    }
}
