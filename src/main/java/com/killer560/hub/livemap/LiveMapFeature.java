package com.killer560.hub.livemap;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.leapmenu.LeapMenuConfig;
import com.killer560.hub.leapmenu.LeapMenuFeature;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * A live, self-drawn dungeon room/door map - killer560's "reference Noamm for the map, essentially
 * duplicate their map" request.
 * <p>
 * Real, confirmed data reused from NoammAddons' own {@code DungeonScanner.kt}/{@code ScanUtils.kt}/
 * {@code DoorType.kt}, not guessed:
 * <ul>
 * <li>The dungeon's room grid is a FIXED 11x11 coordinate system starting at world (-185, -185) with
 * each room cell 32 blocks wide - the same for every single dungeon run.
 * <li>Room vs. doorway (and door TYPE) can be told apart purely from real block data (roof height
 * 73/74/81/82 for doors; Blood/Wither/Entrance doors are real terracotta/coal/infested-brick blocks).
 * <li><b>Room identity (2026-09-13 update):</b> now uses the real room database (see
 * {@link RoomDatabase}) to identify each room's actual NAME by hashing its blocks and matching against
 * ~140 known rooms, and its real ROTATION/corner by finding the real blue-terracotta roof marker - the
 * same technique {@link com.killer560.hub.roomdatabase} ported directly from NoammAddons' own code.
 * </ul>
 * So this draws real room shapes, real names, real door types, and real live player dots. Room name
 * identification needs the database to finish its (small, ~30KB) download on first use - until then
 * rooms render as identified shapes without names, same as before this update.
 */
public final class LiveMapFeature {

    private static final int GRID = 11;
    private static final int START_X = -185;
    private static final int START_Z = -185;
    private static final int HALF_ROOM = 16;

    public enum Tile {
        UNKNOWN, ROOM, DOOR_NORMAL, DOOR_WITHER, DOOR_BLOOD, DOOR_ENTRANCE
    }

    private static final Tile[] grid = new Tile[GRID * GRID];
    private static final RoomEntry[] roomEntryGrid = new RoomEntry[GRID * GRID];
    private static final int[] rotationGrid = new int[GRID * GRID];
    private static final int[] clayXGrid = new int[GRID * GRID];
    private static final int[] clayZGrid = new int[GRID * GRID];
    private static long lastScanAtMs = 0;
    private static boolean wasInDungeon = false;

    static {
        java.util.Arrays.fill(grid, Tile.UNKNOWN);
        java.util.Arrays.fill(rotationGrid, -1);
    }

    private LiveMapFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static void tick() {
        boolean inDungeon = DungeonState.isInDungeon();
        if (inDungeon && !wasInDungeon) {
            java.util.Arrays.fill(grid, Tile.UNKNOWN);
            java.util.Arrays.fill(roomEntryGrid, null);
            java.util.Arrays.fill(rotationGrid, -1);
        }
        wasInDungeon = inDungeon;

        if (!LiveMapConfig.getInstance().isEnabled() || !inDungeon || DungeonState.isBossPhaseActive()) {
            return;
        }
        RoomDatabase.ensureLoading();
        long now = System.currentTimeMillis();
        if (now - lastScanAtMs < 250) {
            return;
        }
        lastScanAtMs = now;
        scan();
    }

    private static void scan() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        for (int x = 0; x < GRID; x++) {
            for (int z = 0; z < GRID; z++) {
                int idx = x + z * GRID;
                int wx = START_X + x * HALF_ROOM;
                int wz = START_Z + z * HALF_ROOM;

                boolean rowEven = z % 2 == 0;
                boolean colEven = x % 2 == 0;

                // Room identity/rotation can still be filled in after the tile itself was already
                // marked (e.g. the database finished loading after this cell was first scanned), so
                // this part re-checks even on an already-known ROOM tile; only the tile classification
                // itself is skip-if-known.
                if (grid[idx] == Tile.ROOM && rowEven && colEven && roomEntryGrid[idx] == null
                        && RoomDatabase.isReady()) {
                    identifyRoom(client, idx, wx, wz);
                }
                if (grid[idx] != Tile.UNKNOWN) {
                    continue;
                }

                BlockPos probe = new BlockPos(wx, 70, wz);
                if (!client.level.isLoaded(probe)) {
                    continue;
                }
                int roofHeight = getHighestY(client, wx, wz);
                if (roofHeight <= 0) {
                    continue;
                }

                if (rowEven && colEven) {
                    grid[idx] = Tile.ROOM;
                    if (RoomDatabase.isReady()) {
                        identifyRoom(client, idx, wx, wz);
                    }
                } else if (roofHeight == 73 || roofHeight == 74 || roofHeight == 81 || roofHeight == 82) {
                    grid[idx] = classifyDoor(client, wx, wz);
                } else {
                    // Corridor/connector for a larger room - copies the parent room's identity when
                    // known, same simplification NoammAddons itself only avoids via full multi-tile
                    // grouping this port doesn't replicate.
                    grid[idx] = Tile.ROOM;
                }
            }
        }
    }

    private static void identifyRoom(Minecraft client, int idx, int wx, int wz) {
        int core = RoomDatabase.getCore(client.level, wx, wz);
        RoomEntry entry = RoomDatabase.lookup(core);
        if (entry != null) {
            roomEntryGrid[idx] = entry;
        }
        if (rotationGrid[idx] < 0) {
            int roofHeight = getHighestY(client, wx, wz);
            int[] rot = RoomDatabase.findRotationAndCorner(client.level, wx, wz, roofHeight);
            if (rot != null) {
                clayXGrid[idx] = rot[0];
                clayZGrid[idx] = rot[1];
                rotationGrid[idx] = rot[2];
            }
        }
    }

    private static int getHighestY(Minecraft client, int x, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, 0, z);
        for (int y = 255; y >= 0; y--) {
            pos.setY(y);
            if (!client.level.getBlockState(pos).isAir()) {
                return y;
            }
        }
        return 0;
    }

    private static Tile classifyDoor(Minecraft client, int x, int z) {
        BlockState state = client.level.getBlockState(new BlockPos(x, 69, z));
        if (state.is(Blocks.RED_TERRACOTTA)) {
            return Tile.DOOR_BLOOD;
        }
        if (state.is(Blocks.COAL_BLOCK)) {
            return Tile.DOOR_WITHER;
        }
        if (state.is(Blocks.INFESTED_CHISELED_STONE_BRICKS)) {
            return Tile.DOOR_ENTRANCE;
        }
        return Tile.DOOR_NORMAL;
    }

    /** @return the player's current grid cell, clamped to the real 11x11 bounds - same transform as
     *  NoammAddons' own {@code ScanUtils.getRoomGraf}. */
    static int[] gridCellFor(Vec3 pos) {
        int roomIndexX = (int) Math.round((pos.x - START_X) / 32.0);
        int roomIndexZ = (int) Math.round((pos.z - START_Z) / 32.0);
        int gx = Math.max(0, Math.min(10, roomIndexX * 2));
        int gz = Math.max(0, Math.min(10, roomIndexZ * 2));
        return new int[]{gx, gz};
    }

    /** For {@link com.killer560.hub.secretwaypoints.SecretWaypointsFeature} - a snapshot of every grid
     *  cell that has both a known room identity AND a known rotation/corner (needed to translate that
     *  room's stored relative secret coordinates into real world positions for THIS run). */
    public static List<int[]> identifiedRoomsWithRotation() {
        List<int[]> result = new java.util.ArrayList<>();
        for (int idx = 0; idx < GRID * GRID; idx++) {
            if (roomEntryGrid[idx] != null && rotationGrid[idx] >= 0) {
                result.add(new int[]{idx, clayXGrid[idx], clayZGrid[idx], rotationGrid[idx]});
            }
        }
        return result;
    }

    public static RoomEntry roomEntryAt(int idx) {
        return roomEntryGrid[idx];
    }

    public static RoomEntry currentRoomEntry() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return null;
        }
        int[] cell = gridCellFor(client.player.position());
        return roomEntryGrid[cell[0] + cell[1] * GRID];
    }

    /** For real puzzle solvers (e.g. {@code BoulderSolverFeature}) that need to translate a puzzle's own
     *  stored relative coordinates into real world positions for THIS run, the same way
     *  {@link #identifiedRoomsWithRotation()} already does for Secret Waypoints - just narrowed to
     *  whichever single room the player is currently standing in. @return
     *  {@code [clayX, clayZ, rotationDegrees]}, or null if the current room's identity/rotation aren't
     *  both known yet. */
    public static int[] currentRoomClayAndRotation() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return null;
        }
        int[] cell = gridCellFor(client.player.position());
        int idx = cell[0] + cell[1] * GRID;
        if (roomEntryGrid[idx] == null || rotationGrid[idx] < 0) {
            return null;
        }
        return new int[]{clayXGrid[idx], clayZGrid[idx], rotationGrid[idx]};
    }

    public static final class LiveMapHudElement implements HudElement {
        @Override
        public String id() {
            return "live_map";
        }

        @Override
        public String displayName() {
            return "Live Dungeon Map";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 500;
        }

        @Override
        public int width() {
            return Math.max(GRID * LiveMapConfig.getInstance().getCellSize(), 90);
        }

        @Override
        public int height() {
            return GRID * LiveMapConfig.getInstance().getCellSize() + 12;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            LiveMapConfig cfg = LiveMapConfig.getInstance();
            if (!cfg.isEnabled() || Minecraft.getInstance().screen != null || !DungeonState.isInDungeon()) {
                return;
            }
            int cell = cfg.getCellSize();
            Minecraft client = Minecraft.getInstance();

            graphics.fill(x, y, x + GRID * cell, y + GRID * cell, 0x99000000);

            for (int gx = 0; gx < GRID; gx++) {
                for (int gz = 0; gz < GRID; gz++) {
                    Tile tile = grid[gx + gz * GRID];
                    if (tile == Tile.UNKNOWN) {
                        continue;
                    }
                    int color = switch (tile) {
                        case ROOM -> 0xFF555555;
                        case DOOR_NORMAL -> 0xFF888888;
                        case DOOR_WITHER -> 0xFF222222;
                        case DOOR_BLOOD -> 0xFFAA0000;
                        case DOOR_ENTRANCE -> 0xFF6699FF;
                        default -> 0x00000000;
                    };
                    int cx = x + gx * cell;
                    int cy = y + gz * cell;
                    graphics.fill(cx + 1, cy + 1, cx + cell - 1, cy + cell - 1, color);
                }
            }

            if (client.player != null) {
                int[] self = gridCellFor(client.player.position());
                int cx = x + self[0] * cell;
                int cy = y + self[1] * cell;
                graphics.fill(cx, cy, cx + cell, cy + cell, 0xFFFFFF55);
            }

            if (cfg.isShowTeammates()) {
                List<Player> members = LeapMenuFeature.currentPartyMembers();
                for (Player p : members) {
                    int[] cellPos = gridCellFor(p.position());
                    int color = 0xFFFFFFFF;
                    if (cfg.isClassRecolorTeammates()) {
                        DungeonClass cls = LeapMenuConfig.getInstance().getAssignedClass(p.getName().getString());
                        if (cls != null) {
                            color = cls.color();
                        }
                    }
                    int cx = x + cellPos[0] * cell + cell / 4;
                    int cy = y + cellPos[1] * cell + cell / 4;
                    graphics.fill(cx, cy, cx + cell / 2, cy + cell / 2, color);
                }
            }

            RoomEntry current = currentRoomEntry();
            String label = current != null ? current.name : (RoomDatabase.isReady() ? "Unknown Room" : "Loading room data...");
            graphics.text(Minecraft.getInstance().font, label, x, y + GRID * cell + 1, 0xFFFFFFFF, false);
        }
    }
}
