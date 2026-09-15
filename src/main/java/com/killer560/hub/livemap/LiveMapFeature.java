package com.killer560.hub.livemap;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.leapmenu.LeapMenuConfig;
import com.killer560.hub.leapmenu.LeapMenuFeature;
import com.killer560.hub.puzzlesolvers.BeamsSolverConfig;
import com.killer560.hub.puzzlesolvers.BlazeSolverConfig;
import com.killer560.hub.puzzlesolvers.BoulderSolverConfig;
import com.killer560.hub.puzzlesolvers.IceFillSolverConfig;
import com.killer560.hub.puzzlesolvers.QuizSolverConfig;
import com.killer560.hub.puzzlesolvers.WaterSolverConfig;
import com.killer560.hub.puzzlesolvers.WeirdosSolverConfig;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.secretwaypoints.SecretWaypointsConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final long[] rotationRetryAtMs = new long[GRID * GRID];
    private static long lastScanAtMs = 0;
    private static boolean wasInDungeon = false;
    private static Object lastLevel = null;
    /** Latched once the player is seen inside the current floor's boss room; cleared on grid reset. */
    private static boolean bossLatched = false;

    // Boss room bounds per floor 1..7 - copied verbatim from NoammAddons' own (26.1.2 upstream)
    // LocationUtils.bossRoomBounds; {x1, y1, z1, x2, y2, z2}, min/max normalized by AABB's constructor.
    private static final net.minecraft.world.phys.AABB[] BOSS_ROOM_BOUNDS = {
            new net.minecraft.world.phys.AABB(-14, 55, 49, -72, 146, -40),
            new net.minecraft.world.phys.AABB(-40, 99, -40, 24, 54, 59),
            new net.minecraft.world.phys.AABB(-40, 118, -40, 42, 64, 37),
            new net.minecraft.world.phys.AABB(-40, 112, -40, 50, 53, 47),
            new net.minecraft.world.phys.AABB(-40, 112, -8, 50, 53, 118),
            new net.minecraft.world.phys.AABB(-40, 51, -8, 22, 110, 134),
            new net.minecraft.world.phys.AABB(-8, 0, -8, 134, 254, 147)
    };

    // [LiveMap] diagnostics - logging only, never affects scanning.
    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-livemap");
    private static String lastLoggedGates = null;
    private static final int[] lastLoggedCore = new int[GRID * GRID];
    private static final boolean[] loggedNoRotation = new boolean[GRID * GRID];
    private static String lastLoggedPlayerRoom = null;
    private static String lastLoggedSummary = null;
    private static long lastSummaryCheckMs = 0;

    static {
        java.util.Arrays.fill(grid, Tile.UNKNOWN);
        java.util.Arrays.fill(rotationGrid, -1);
    }

    private LiveMapFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static void resetGrid(String reason) {
        java.util.Arrays.fill(grid, Tile.UNKNOWN);
        java.util.Arrays.fill(roomEntryGrid, null);
        java.util.Arrays.fill(rotationGrid, -1);
        java.util.Arrays.fill(lastLoggedCore, 0);
        java.util.Arrays.fill(loggedNoRotation, false);
        java.util.Arrays.fill(rotationRetryAtMs, 0L);
        lastLoggedSummary = null;
        bossLatched = false;
        LOGGER.info("[LiveMap] {} - grid reset (floor={})", reason, DungeonState.getFloor());
    }

    /** Real bug found and fixed (2026-09-14, code review): room scanning used to run ONLY while Live Map
     *  itself was enabled, so every puzzle solver and Secret Waypoints (all of which read
     *  {@link #currentRoomEntry()}/{@link #identifiedRoomsWithRotation()}) silently did nothing unless
     *  Live Map was also on. Scanning now runs whenever ANY consumer needs it; the HUD element still only
     *  draws when Live Map itself is enabled. */
    private static String scanConsumers() {
        StringBuilder sb = new StringBuilder();
        if (LiveMapConfig.getInstance().isEnabled()) sb.append("LiveMap,");
        if (SecretWaypointsConfig.getInstance().isEnabled()) sb.append("SecretWaypoints,");
        if (BoulderSolverConfig.getInstance().isEnabled()) sb.append("Boulder,");
        if (QuizSolverConfig.getInstance().isEnabled()) sb.append("Quiz,");
        if (IceFillSolverConfig.getInstance().isEnabled()) sb.append("IceFill,");
        if (WeirdosSolverConfig.getInstance().isEnabled()) sb.append("Weirdos,");
        if (WaterSolverConfig.getInstance().isEnabled()) sb.append("Water,");
        if (BeamsSolverConfig.getInstance().isEnabled()) sb.append("Beams,");
        if (BlazeSolverConfig.getInstance().isEnabled()) sb.append("Blaze,");
        return sb.length() == 0 ? "" : sb.substring(0, sb.length() - 1);
    }

    private static void tick() {
        Minecraft client = Minecraft.getInstance();
        boolean inDungeon = DungeonState.isInDungeon();
        if (client.level != lastLevel) {
            // Real bug found and fixed (2026-09-14): dungeon -> dungeon warps can keep isInDungeon()
            // true across the server switch, leaving the previous run's rooms/rotations in the grid.
            lastLevel = client.level;
            if (inDungeon || wasInDungeon) {
                resetGrid("World changed");
            }
        }
        if (inDungeon && !wasInDungeon) {
            resetGrid("Dungeon entered");
        }
        wasInDungeon = inDungeon;
        updateBossState(client, inDungeon);

        String consumers = scanConsumers();
        boolean scanning = !consumers.isEmpty() && inDungeon && !isInBoss();
        String gates = "consumers=[" + consumers + "] inDungeon=" + inDungeon
                + " bossPhase=" + DungeonState.isBossPhaseActive() + " inBoss=" + isInBoss()
                + " roomDbReady=" + RoomDatabase.isReady();
        if (!gates.equals(lastLoggedGates)) {
            LOGGER.info("[LiveMap] Gates changed: {} (scanning={}, hudEnabled={})", gates, scanning,
                    LiveMapConfig.getInstance().isEnabled());
            lastLoggedGates = gates;
        }
        if (inDungeon) {
            logPlayerRoomIfChanged();
        }

        if (!scanning) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastSummaryCheckMs >= 5000) {
            lastSummaryCheckMs = nowMs;
            logSummaryIfChanged();
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
                // Real bug found and fixed (2026-09-14, code review): this used to retry only while
                // roomEntryGrid[idx]==null, so a room identified BEFORE its blue-terracotta corner marker
                // loaded never got a rotation -> no waypoints and no solver for that room all run.
                // Identified-but-unrotated cells now keep retrying rotation too.
                if (grid[idx] == Tile.ROOM && rowEven && colEven
                        && ((roomEntryGrid[idx] == null && RoomDatabase.isReady()) || rotationGrid[idx] < 0)
                        && client.level.isLoaded(new BlockPos(wx, 70, wz))) {
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
                    LOGGER.info("[LiveMap] Cell ({},{}) world=({},{}) roofY={} -> ROOM", x, z, wx, wz, roofHeight);
                    identifyRoom(client, idx, wx, wz);
                } else if (roofHeight == 73 || roofHeight == 74 || roofHeight == 81 || roofHeight == 82) {
                    grid[idx] = classifyDoor(client, wx, wz);
                    LOGGER.info("[LiveMap] Cell ({},{}) world=({},{}) roofY={} -> {} (y69 block={})", x, z, wx, wz,
                            roofHeight, grid[idx], client.level.getBlockState(new BlockPos(wx, 69, wz)).getBlock());
                } else {
                    // Corridor/connector for a larger room - copies the parent room's identity when
                    // known, same simplification NoammAddons itself only avoids via full multi-tile
                    // grouping this port doesn't replicate.
                    grid[idx] = Tile.ROOM;
                    LOGGER.info("[LiveMap] Cell ({},{}) world=({},{}) roofY={} -> ROOM (connector)", x, z, wx, wz, roofHeight);
                }
            }
        }
    }

    private static void identifyRoom(Minecraft client, int idx, int wx, int wz) {
        if (roomEntryGrid[idx] == null && RoomDatabase.isReady()) {
            int core = RoomDatabase.getCore(client.level, wx, wz);
            RoomEntry entry = RoomDatabase.lookup(core);
            if (entry != null) {
                roomEntryGrid[idx] = entry;
                LOGGER.info("[LiveMap] Room identified at cell ({},{}) world=({},{}): \"{}\" type={} shape={} secrets={} core={} (rotationKnown={})",
                        idx % GRID, idx / GRID, wx, wz, entry.name, entry.type, entry.shape, entry.secrets, core,
                        rotationGrid[idx] >= 0);
            } else if (lastLoggedCore[idx] != core) {
                // Retried every 250ms until matched - only log when the computed hash actually changes.
                lastLoggedCore[idx] = core;
                LOGGER.info("[LiveMap] No room DB match at cell ({},{}) world=({},{}) core={} (will retry)",
                        idx % GRID, idx / GRID, wx, wz, core);
            }
        }
        long nowMs = System.currentTimeMillis();
        if (rotationGrid[idx] < 0 && nowMs >= rotationRetryAtMs[idx]) {
            // Throttled to 1s per cell: non-corner cells of multi-tile rooms may never have a marker of
            // their own, and each attempt is a full 255-block roof column scan.
            rotationRetryAtMs[idx] = nowMs + 1000;
            int roofHeight = getHighestY(client, wx, wz);
            int[] rot = RoomDatabase.findRotationAndCorner(client.level, wx, wz, roofHeight);
            if (rot != null) {
                clayXGrid[idx] = rot[0];
                clayZGrid[idx] = rot[1];
                rotationGrid[idx] = rot[2];
                LOGGER.info("[LiveMap] Rotation found at cell ({},{}): clay=({},{}) rotation={} roofY={} room={} lateRetry={}",
                        idx % GRID, idx / GRID, rot[0], rot[1], rot[2], roofHeight,
                        roomEntryGrid[idx] != null ? "\"" + roomEntryGrid[idx].name + "\"" : "null", loggedNoRotation[idx]);
            } else if (!loggedNoRotation[idx]) {
                loggedNoRotation[idx] = true;
                LOGGER.info("[LiveMap] No blue-terracotta corner marker at cell ({},{}) world=({},{}) roofY={} (roomIdentified={})",
                        idx % GRID, idx / GRID, wx, wz, roofHeight, roomEntryGrid[idx] != null);
            }
        }
    }

    /** Logs the player's current grid cell / identified room whenever it changes. */
    private static void logPlayerRoomIfChanged() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        int[] cell = gridCellFor(client.player.position());
        int idx = cell[0] + cell[1] * GRID;
        RoomEntry entry = roomEntryGrid[idx];
        String key = "cell=(" + cell[0] + "," + cell[1] + ") tile=" + grid[idx]
                + " room=" + (entry != null ? entry.name : "null") + " rotation=" + rotationGrid[idx]
                + " matchable=" + (currentRoomIndex() >= 0) + " inBoss=" + isInBoss();
        if (!key.equals(lastLoggedPlayerRoom)) {
            var pos = client.player.position();
            LOGGER.info("[LiveMap] Player room changed: {} -> {} (pos={},{},{})", lastLoggedPlayerRoom, key,
                    (int) pos.x, (int) pos.y, (int) pos.z);
            lastLoggedPlayerRoom = key;
        }
    }

    /** Scan progress summary, checked every 5s and logged only on change. */
    private static void logSummaryIfChanged() {
        int known = 0;
        int rooms = 0;
        int identified = 0;
        int rotated = 0;
        int unloadedRoomCells = 0;
        Minecraft client = Minecraft.getInstance();
        for (int idx = 0; idx < GRID * GRID; idx++) {
            if (grid[idx] != Tile.UNKNOWN) {
                known++;
            } else if (client.level != null && (idx % GRID) % 2 == 0 && (idx / GRID) % 2 == 0
                    && !client.level.isLoaded(new BlockPos(START_X + (idx % GRID) * HALF_ROOM, 70, START_Z + (idx / GRID) * HALF_ROOM))) {
                unloadedRoomCells++;
            }
            if (grid[idx] == Tile.ROOM && (idx % GRID) % 2 == 0 && (idx / GRID) % 2 == 0) {
                rooms++;
            }
            if (roomEntryGrid[idx] != null) {
                identified++;
            }
            if (rotationGrid[idx] >= 0) {
                rotated++;
            }
        }
        String summary = "knownCells=" + known + "/" + (GRID * GRID) + " roomCells=" + rooms + " identified=" + identified
                + " withRotation=" + rotated + " identifiedAndRotated=" + identifiedRoomsWithRotation().size()
                + " unloadedRoomCells=" + unloadedRoomCells + " roomDbReady=" + RoomDatabase.isReady();
        if (!summary.equals(lastLoggedSummary)) {
            LOGGER.info("[LiveMap] Scan summary: {}", summary);
            lastLoggedSummary = summary;
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

    /** Real bug found and fixed (2026-09-14, code review + NoammAddons e42d3316 "reset when entering
     *  boss"): {@link #gridCellFor} clamps to the 11x11 grid, so standing in a boss room (outside the
     *  -201..-9 dungeon footprint) mapped to corner cell (10,10) and solvers could "match" whatever room
     *  was identified there. @return the player's current room cell index, or -1 when there's no
     *  player, the player is in boss, or the player is outside the dungeon grid footprint. */
    private static int currentRoomIndex() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || isInBoss()) {
            return -1;
        }
        Vec3 pos = client.player.position();
        if (!insideGridFootprint(pos.x, pos.z)) {
            return -1;
        }
        int[] cell = gridCellFor(pos);
        return cell[0] + cell[1] * GRID;
    }

    private static boolean insideGridFootprint(double x, double z) {
        long roomIndexX = Math.round((x - START_X) / 32.0);
        long roomIndexZ = Math.round((z - START_Z) / 32.0);
        return roomIndexX >= 0 && roomIndexX <= 5 && roomIndexZ >= 0 && roomIndexZ <= 5;
    }

    /** @return true while the player is in (or has entered, this run) the current floor's boss room -
     *  real F7/M7 boss-phase detection ({@link DungeonState#isBossPhaseActive()}, which is also forced
     *  on by /killer560 sim) OR NoammAddons' own per-floor boss-room bounds. The bounds check also
     *  requires being outside the room-grid footprint, since NoammAddons' F1-F4 boxes overlap the
     *  grid's corner cells. Latched until the next grid reset (dungeon entry / world change). */
    public static boolean isInBoss() {
        return bossLatched || DungeonState.isBossPhaseActive();
    }

    private static void updateBossState(Minecraft client, boolean inDungeon) {
        if (!inDungeon || bossLatched || client.player == null) {
            return;
        }
        String floor = DungeonState.getFloor();
        int floorNumber = floor != null && !floor.isEmpty() && Character.isDigit(floor.charAt(floor.length() - 1))
                ? floor.charAt(floor.length() - 1) - '0' : 0;
        if (floorNumber < 1 || floorNumber > 7) {
            return;
        }
        Vec3 pos = client.player.position();
        if (BOSS_ROOM_BOUNDS[floorNumber - 1].contains(pos.x, pos.y, pos.z) && !insideGridFootprint(pos.x, pos.z)) {
            bossLatched = true;
            LOGGER.info("[LiveMap] Boss room entered (floor={} pos={},{},{}) - room matching disabled, solvers reset",
                    floor, (int) pos.x, (int) pos.y, (int) pos.z);
        }
    }

    public static RoomEntry currentRoomEntry() {
        int idx = currentRoomIndex();
        return idx < 0 ? null : roomEntryGrid[idx];
    }

    /** For real puzzle solvers (e.g. {@code BoulderSolverFeature}) that need to translate a puzzle's own
     *  stored relative coordinates into real world positions for THIS run, the same way
     *  {@link #identifiedRoomsWithRotation()} already does for Secret Waypoints - just narrowed to
     *  whichever single room the player is currently standing in. @return
     *  {@code [clayX, clayZ, rotationDegrees]}, or null if the current room's identity/rotation aren't
     *  both known yet. */
    public static int[] currentRoomClayAndRotation() {
        int idx = currentRoomIndex();
        if (idx < 0 || roomEntryGrid[idx] == null || rotationGrid[idx] < 0) {
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
