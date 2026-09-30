package com.killer560.hub.livemap;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.chunkcache.ChunkCacheManager;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.hud.HudElement;
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
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.killer560.hub.compat.McBlocks;

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
 * <li><b>Multi-tile rooms (2026-09-15 update):</b> 1x2/1x3/1x4/2x2/L rooms are grouped into ONE room
 * the way NoammAddons' {@code UniqueRoom}/{@code RoomTile.addToUnique} does - tiles sharing a database
 * name join one room, and "connector" cells (the filled gap between two tiles of the same room, from the
 * world scan or from the vanilla dungeon map item via {@link DungeonMapScanner}) join the tiles on either
 * side. A room's name, rotation/corner, map state and label are shared by every tile of it. Merges that
 * would contradict the database (two different names, or more tiles than the room's shape has) are
 * refused, so missing/odd data just falls back to single tiles.
 * </ul>
 * So this draws real room shapes, real names, real door types, and real live player dots. Room name
 * identification needs the database to finish its (small, ~30KB) download on first use - until then
 * rooms render as identified shapes without names, same as before this update.
 */
public final class LiveMapFeature {

    static final int GRID = 11;
    static final int START_X = -185;
    static final int START_Z = -185;
    static final int HALF_ROOM = 16;
    /** Bumped on every grid reset so the interactive map can drop per-run state (cleared-by, selections). */
    private static int resetGeneration = 0;
    /** Bumped every client tick. Renderers key their per-frame caches off this (fps report 2026-09-20: the map
     *  rebuilt the party list, the layout snapshot and every room label on every single frame). */
    private static int tickCounter = 0;

    public enum Tile {
        UNKNOWN, ROOM, DOOR_NORMAL, DOOR_WITHER, DOOR_BLOOD, DOOR_ENTRANCE
    }

    private static final Tile[] grid = new Tile[GRID * GRID];
    /** Per-tile core-hash match (even cells only). Room-level identity lives on {@link RoomGroup#entry}. */
    private static final RoomEntry[] roomEntryGrid = new RoomEntry[GRID * GRID];
    /** Rotation/corner, stored on the tile whose corner (or, for bounding-box hits, the group's main tile)
     *  carried the marker; resolved per room by {@link #rotationSourceIdx(RoomGroup)}. */
    private static final int[] rotationGrid = new int[GRID * GRID];
    private static final int[] clayXGrid = new int[GRID * GRID];
    private static final int[] clayZGrid = new int[GRID * GRID];
    private static final long[] rotationRetryAtMs = new long[GRID * GRID];
    private static long lastScanAtMs = 0;
    private static boolean wasInDungeon = false;
    private static Object lastLevel = null;
    /** Latched once the player is seen inside the current floor's boss room; cleared on grid reset. */
    private static boolean bossLatched = false;
    /** Real bug found and fixed (2026-09-14 review pass): right after a world change the player sits at the
     *  (0,100,0) placeholder until the server teleports them - inside the F3-F7 boss boxes and outside the
     *  room grid - so bossLatched could latch on for the whole run (all solvers off), especially under
     *  /killer560 sim (floor forced F7) or a dungeon -> dungeon warp. No position latching for this many
     *  ticks after a level change, nor while still exactly at the placeholder. */
    private static final int BOSS_LATCH_GRACE_TICKS = 20;
    private static int bossLatchGraceTicks = 0;

    // ---- multi-tile room grouping (rebuilt only when the grid, a tile identity, or the map changes) ----
    private static final int[] groupOfCell = new int[GRID * GRID];
    private static final List<RoomGroup> groups = new ArrayList<>();
    private static boolean groupsDirty = true;
    /** Per-room secrets found, from the action bar's "x/y Secrets" (NoammAddons' ActionBarParser). */
    private static final Map<String, Integer> foundSecretsByRoom = new HashMap<>();
    private static final Pattern ACTION_BAR_SECRETS = Pattern.compile("(\\d+)/(\\d+) Secrets");

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
    private static final Logger LOGGER = ModLog.get("killer560smod-livemap");
    private static final boolean[] loggedNoRotation = new boolean[GRID * GRID];

    static {
        java.util.Arrays.fill(grid, Tile.UNKNOWN);
        java.util.Arrays.fill(rotationGrid, -1);
        java.util.Arrays.fill(groupOfCell, -1);
    }

    private LiveMapFeature() {
    }

    /** One real dungeon room - one or more grid tiles plus the connector cells between them. */
    static final class RoomGroup {
        /** Top-left tile (smallest grid x, then z) - NoammAddons' {@code UniqueRoom.mainRoom}. */
        final int mainIdx;
        /** Even (room) cells. */
        final int[] tiles;
        /** Every cell of the room, tiles and connectors. */
        final int[] cells;
        final RoomEntry entry;
        final int minGX;
        final int maxGX;
        final int minGZ;
        final int maxGZ;
        /** Label center in grid units (QUOI's {@code OdonRoom.textPlacement}). */
        final float labelGX;
        final float labelGZ;
        final boolean lShape;
        final String[] nameLines;

        RoomGroup(int mainIdx, int[] tiles, int[] cells, RoomEntry entry) {
            this.mainIdx = mainIdx;
            this.tiles = tiles;
            this.cells = cells;
            this.entry = entry;
            int mnX = GRID;
            int mxX = -1;
            int mnZ = GRID;
            int mxZ = -1;
            for (int t : tiles) {
                mnX = Math.min(mnX, t % GRID);
                mxX = Math.max(mxX, t % GRID);
                mnZ = Math.min(mnZ, t / GRID);
                mxZ = Math.max(mxZ, t / GRID);
            }
            minGX = mnX;
            maxGX = mxX;
            minGZ = mnZ;
            maxGZ = mxZ;
            float lx = (mnX + mxX) / 2f;
            float lz = (mnZ + mxZ) / 2f;
            lShape = tiles.length == 3 && mxX - mnX == 2 && mxZ - mnZ == 2;
            if (lShape) {
                // L-shape: center on the horizontal pair (QUOI textPlacement).
                for (int a = 0; a < 3; a++) {
                    for (int b = a + 1; b < 3; b++) {
                        if (tiles[a] / GRID == tiles[b] / GRID) {
                            lx = (tiles[a] % GRID + tiles[b] % GRID) / 2f;
                            lz = tiles[a] / GRID;
                        }
                    }
                }
            }
            labelGX = lx;
            labelGZ = lz;
            nameLines = entry != null && entry.name != null ? entry.name.split(" ") : new String[0];
        }
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("LiveMapFeature", client -> tick()));
        InteractiveMapFeature.register();
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) {
                onActionBar(message.getString());
            }
        });
    }

    private static void resetGrid(String reason) {
        java.util.Arrays.fill(grid, Tile.UNKNOWN);
        java.util.Arrays.fill(roomEntryGrid, null);
        java.util.Arrays.fill(rotationGrid, -1);
        java.util.Arrays.fill(loggedNoRotation, false);
        java.util.Arrays.fill(rotationRetryAtMs, 0L);
        java.util.Arrays.fill(groupOfCell, -1);
        groups.clear();
        groupsDirty = true;
        resetGeneration++;
        foundSecretsByRoom.clear();
        DungeonMapScanner.reset();
        PartyMapIntel.reset();
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
        if (com.killer560.hub.puzzlesolvers.TicTacToeSolverConfig.getInstance().isEnabled()) sb.append("TicTacToe,");
        if (com.killer560.hub.puzzlesolvers.TeleportMazeSolverConfig.getInstance().isEnabled()) sb.append("TeleportMaze,");
        if (com.killer560.hub.puzzlesolvers.IcePathSolverConfig.getInstance().isEnabled()) sb.append("IcePath,");
        if (WeirdosSolverConfig.getInstance().isEnabled()) sb.append("Weirdos,");
        if (WaterSolverConfig.getInstance().isEnabled()) sb.append("Water,");
        if (BeamsSolverConfig.getInstance().isEnabled()) sb.append("Beams,");
        if (BlazeSolverConfig.getInstance().isEnabled()) sb.append("Blaze,");
        // Teleport Pathing folded into Interactive Map itself (killer560: "the entire portion of interactive
        // map is the teleport pathing") - no separate flag left to report here.
        if (LiveMapConfig.getInstance().isInteractiveMapEnabled()) sb.append("InteractiveMap,");
        if (LiveMapConfig.getInstance().isBloodRushEnabled()) sb.append("BloodRush,");
        return sb.length() == 0 ? "" : sb.substring(0, sb.length() - 1);
    }

    private static void tick() {
        tickCounter++;
        Minecraft client = Minecraft.getInstance();
        boolean inDungeon = DungeonState.isInDungeon();
        if (client.level != lastLevel) {
            // Real bug found and fixed (2026-09-14): dungeon -> dungeon warps can keep isInDungeon()
            // true across the server switch, leaving the previous run's rooms/rotations in the grid.
            lastLevel = client.level;
            bossLatchGraceTicks = BOSS_LATCH_GRACE_TICKS;
            if (inDungeon || wasInDungeon) {
                resetGrid("World changed");
            }
        }
        if (inDungeon && !wasInDungeon) {
            resetGrid("Dungeon entered");
        }
        wasInDungeon = inDungeon;
        updateBossState(client, inDungeon);

        // In the SIM the floor is PUBLISHED, not scanned - see publishSimFloor.
        //
        // The world scan reads the vanilla dungeon map item and the clay markers Hypixel puts in each room,
        // and a singleplayer sim has neither. It therefore found nothing, every tick, and overwrote anything
        // else that tried to fill the grid: "[LiveMap] Room groups rebuilt: rooms=0" in every sim log. That is
        // why the map, the interactive map and every pathfinder that reads the layout were all blank in
        // there, and why the map feature could not be tested in the sim at all.
        boolean sim = com.killer560.hub.roomsim.SimState.isActive();
        String consumers = scanConsumers();
        boolean scanning = !consumers.isEmpty() && inDungeon && !isInBoss() && !sim;
        if (!scanning) {
            return;
        }
        RoomDatabase.ensureLoading();
        long now = System.currentTimeMillis();
        if (now - lastScanAtMs < 250) {
            return;
        }
        lastScanAtMs = now;
        if (DungeonMapScanner.update(client)) {
            groupsDirty = true;
        }
        scan();
    }

    /**
     * Fills the grid from a floor the dungeon sim just built.
     *
     * <p>The sim knows exactly what it placed - name, cells, clay corner and rotation per room, and a door
     * type per connector - so it hands that over rather than leaving the map to look for markers that a
     * singleplayer world does not have. Everything downstream (the HUD map, the interactive map, the teleport
     * pathfinders, Secret Waypoints) reads the same arrays it always did and needs no sim-specific path.
     *
     * <p>Client thread only, like every other writer of these arrays.
     *
     * @param roomCells  for each of the 121 cells, the index into {@code names} of the room covering it, or -1
     * @param doorCells  for each cell, a {@link DungeonLayout} door constant, or DOOR_NONE
     * @param names      room names, indexed by {@code roomCells}
     * @param clay       per room name index, {@code {clayX, clayZ, rotation}}
     */
    public static void publishSimFloor(int[] roomCells, int[] doorCells, String[] names, int[][] clay) {
        resetGrid("Sim floor built");
        for (int idx = 0; idx < GRID * GRID; idx++) {
            int room = idx < roomCells.length ? roomCells[idx] : -1;
            if (room >= 0 && room < names.length) {
                // Tiles AND connectors are marked ROOM, which is what lets rebuildGroups union a multi-tile
                // room back together - the same convention a live capture uses.
                grid[idx] = Tile.ROOM;
                if ((idx % GRID) % 2 == 0 && (idx / GRID) % 2 == 0) {
                    roomEntryGrid[idx] = RoomDatabase.lookupByName(names[room]);
                    if (clay != null && room < clay.length && clay[room] != null) {
                        clayXGrid[idx] = clay[room][0];
                        clayZGrid[idx] = clay[room][1];
                        rotationGrid[idx] = clay[room][2];
                    }
                }
                continue;
            }
            int door = idx < doorCells.length ? doorCells[idx] : DungeonLayout.DOOR_NONE;
            grid[idx] = switch (door) {
                case DungeonLayout.DOOR_NORMAL -> Tile.DOOR_NORMAL;
                case DungeonLayout.DOOR_WITHER -> Tile.DOOR_WITHER;
                case DungeonLayout.DOOR_BLOOD -> Tile.DOOR_BLOOD;
                case DungeonLayout.DOOR_ENTRANCE -> Tile.DOOR_ENTRANCE;
                default -> Tile.UNKNOWN;
            };
        }
        groupsDirty = true;
        LOGGER.info("[LiveMap] Sim floor published: {} room(s)", names.length);
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

                // Room identity can still be filled in after the tile itself was already marked (e.g. the
                // database finished loading after this cell was first scanned), so this re-checks even on
                // an already-known ROOM tile - unless another tile of the same room already identified it.
                if (grid[idx] == Tile.ROOM && rowEven && colEven && roomEntryGrid[idx] == null
                        && RoomDatabase.isReady() && !cachedGroupHasEntry(idx)
                        && ChunkCacheManager.isLoadedOrCached(client.level, new BlockPos(wx, 70, wz))) {
                    identifyTile(client, idx, wx, wz);
                }
                if (grid[idx] != Tile.UNKNOWN) {
                    continue;
                }

                BlockPos probe = new BlockPos(wx, 70, wz);
                if (!ChunkCacheManager.isLoadedOrCached(client.level, probe)) {
                    continue;
                }
                int roofHeight = getHighestY(client, wx, wz);
                if (roofHeight <= 0) {
                    continue;
                }

                if (rowEven && colEven) {
                    grid[idx] = Tile.ROOM;
                    groupsDirty = true;
                    identifyTile(client, idx, wx, wz);
                } else if (roofHeight == 73 || roofHeight == 74 || roofHeight == 81 || roofHeight == 82) {
                    grid[idx] = classifyDoor(client, wx, wz);
                    groupsDirty = true;
                } else {
                    // Connector between two tiles of one larger room (or a 2x2 room's center) - the filled
                    // wall gap. Joins the neighbouring tiles into one room in rebuildGroups().
                    grid[idx] = Tile.ROOM;
                    groupsDirty = true;
                }
            }
        }

        ensureGroups();
        long nowMs = System.currentTimeMillis();
        for (RoomGroup group : groups) {
            if (rotationSourceIdx(group) >= 0 || nowMs < rotationRetryAtMs[group.mainIdx]) {
                continue;
            }
            // Throttled to 1s per room: each attempt is a full 255-block roof column scan per tile.
            rotationRetryAtMs[group.mainIdx] = nowMs + 1000;
            findRoomRotation(client, group);
        }
    }

    private static void identifyTile(Minecraft client, int idx, int wx, int wz) {
        if (roomEntryGrid[idx] != null || !RoomDatabase.isReady()) {
            return;
        }
        int core = RoomDatabase.getCore(client.level, wx, wz);
        RoomEntry entry = RoomDatabase.lookup(core);
        if (entry != null) {
            roomEntryGrid[idx] = entry;
            groupsDirty = true;
        }
    }

    /** Port of NoammAddons' {@code UniqueRoom.findRotation}: Fairy has no marker (fixed corner, rotation 0);
     *  a complete non-L room checks the 4 corners of the bounding box around all its tiles; L-shaped rooms,
     *  incomplete rooms and rooms with no database match fall back to each tile's own 4 corners (the
     *  pre-grouping behaviour). */
    private static void findRoomRotation(Minecraft client, RoomGroup group) {
        List<Integer> worldTiles = new ArrayList<>();
        for (int t : group.tiles) {
            if (grid[t] == Tile.ROOM) {
                worldTiles.add(t);
            }
        }
        if (worldTiles.isEmpty()) {
            return;
        }
        RoomEntry entry = group.entry;
        int mainIdx = worldTiles.contains(group.mainIdx) ? group.mainIdx : worldTiles.get(0);
        int mainX = START_X + (mainIdx % GRID) * HALF_ROOM;
        int mainZ = START_Z + (mainIdx / GRID) * HALF_ROOM;
        if (entry != null && "FAIRY".equals(entry.type) && group.tiles.length == 1) {
            setRotation(mainIdx, mainX - 15, mainZ - 15, 0, -1, group, "fairy");
            return;
        }
        if (entry != null && !"L".equals(entry.shape) && worldTiles.size() > 1
                && worldTiles.size() >= RoomDatabase.shapeTileCount(entry.shape)
                && ChunkCacheManager.isLoadedOrCached(client.level, new BlockPos(mainX, 70, mainZ))) {
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (int t : worldTiles) {
                int tx = START_X + (t % GRID) * HALF_ROOM;
                int tz = START_Z + (t / GRID) * HALF_ROOM;
                minX = Math.min(minX, tx);
                maxX = Math.max(maxX, tx);
                minZ = Math.min(minZ, tz);
                maxZ = Math.max(maxZ, tz);
            }
            int roofHeight = getHighestY(client, mainX, mainZ);
            if (roofHeight > 0) {
                int[] rot = RoomDatabase.findRotationAndCorner(client.level, minX, minZ, maxX, maxZ, roofHeight);
                if (rot != null) {
                    setRotation(mainIdx, rot[0], rot[1], rot[2], roofHeight, group, "bounds");
                    return;
                }
            }
        }
        for (int t : worldTiles) {
            int wx = START_X + (t % GRID) * HALF_ROOM;
            int wz = START_Z + (t / GRID) * HALF_ROOM;
            if (!ChunkCacheManager.isLoadedOrCached(client.level, new BlockPos(wx, 70, wz))) {
                continue;
            }
            int roofHeight = getHighestY(client, wx, wz);
            int[] rot = RoomDatabase.findRotationAndCorner(client.level, wx, wz, roofHeight);
            if (rot != null) {
                setRotation(t, rot[0], rot[1], rot[2], roofHeight, group, "tile");
                return;
            }
        }
        if (!loggedNoRotation[group.mainIdx]) {
            loggedNoRotation[group.mainIdx] = true;
            LOGGER.info("[LiveMap] No blue-terracotta corner marker for room at cell ({},{}) tiles={} (roomIdentified={})",
                    group.mainIdx % GRID, group.mainIdx / GRID, group.tiles.length, entry != null);
        }
    }

    private static void setRotation(int idx, int clayX, int clayZ, int rotation, int roofHeight, RoomGroup group, String how) {
        clayXGrid[idx] = clayX;
        clayZGrid[idx] = clayZ;
        rotationGrid[idx] = rotation;
    }

    /** @return the tile index holding this room's rotation/corner (main tile first), or -1. */
    private static int rotationSourceIdx(RoomGroup group) {
        if (rotationGrid[group.mainIdx] >= 0) {
            return group.mainIdx;
        }
        for (int t : group.tiles) {
            if (rotationGrid[t] >= 0) {
                return t;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------------------------------------
    // Grouping
    // ---------------------------------------------------------------------------------------------

    private static void ensureGroups() {
        if (groupsDirty) {
            groupsDirty = false;
            rebuildGroups();
        }
    }

    /** Union-find over the 121 cells. Real sources, in order: (1) connector cells join their neighbours -
     *  NoammAddons' {@code DungeonScanner.scanTile} "connection between large rooms"/"2x2 center" branches,
     *  plus {@code HotbarMapScanner.getConnected} for map-only cells; (2) tiles with the same database name
     *  join - {@code DungeonScanner.uniqueRooms} keyed by name. A union is refused when both sides have
     *  different names or the result would exceed the room's shape tile count, so bad data degrades to
     *  single tiles instead of wrong merges. */
    private static void rebuildGroups() {
        int n = GRID * GRID;
        int[] parent = new int[n];
        int[] tileCount = new int[n];
        RoomEntry[] rootEntry = new RoomEntry[n];
        boolean[] roomish = new boolean[n];
        boolean[] isTile = new boolean[n];
        for (int idx = 0; idx < n; idx++) {
            int gx = idx % GRID;
            int gz = idx / GRID;
            boolean even = gx % 2 == 0 && gz % 2 == 0;
            int mapKind = DungeonMapScanner.kindAt(idx);
            boolean worldRoom = grid[idx] == Tile.ROOM;
            boolean worldUnknown = grid[idx] == Tile.UNKNOWN;
            if (even) {
                isTile[idx] = worldRoom || (worldUnknown && mapKind == DungeonMapScanner.KIND_ROOM);
                roomish[idx] = isTile[idx];
            } else {
                roomish[idx] = worldRoom || (worldUnknown && mapKind == DungeonMapScanner.KIND_SEPARATOR);
            }
            parent[idx] = idx;
            tileCount[idx] = isTile[idx] ? 1 : 0;
            rootEntry[idx] = isTile[idx] ? roomEntryGrid[idx] : null;
        }

        int refused = 0;
        for (int idx = 0; idx < n; idx++) {
            int gx = idx % GRID;
            int gz = idx / GRID;
            if (!roomish[idx] || (gx % 2 == 0 && gz % 2 == 0)) {
                continue;
            }
            int mapKind = DungeonMapScanner.kindAt(idx);
            if (mapKind == DungeonMapScanner.KIND_DOOR) {
                continue; // the dungeon map shows a door in this gap, not a room connector
            }
            // A world-scanned connector only merges when something confirms it: the dungeon map shows a
            // room separator here, or an adjacent tile is identified (so the shape cap/name check apply).
            // No map + no database = no merge = single tiles, exactly the pre-grouping behaviour. Never next
            // to the Entrance - NoammAddons turns that gap into an entrance door.
            boolean anchored = mapKind == DungeonMapScanner.KIND_SEPARATOR;
            boolean nearEntrance = false;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int nx = gx + dx;
                    int nz = gz + dz;
                    if (nx < 0 || nz < 0 || nx >= GRID || nz >= GRID) {
                        continue;
                    }
                    RoomEntry neighbour = isTile[nx + nz * GRID] ? roomEntryGrid[nx + nz * GRID] : null;
                    if (neighbour != null) {
                        anchored = true;
                        nearEntrance |= "ENTRANCE".equals(neighbour.type);
                    }
                }
            }
            if (!anchored || nearEntrance) {
                continue;
            }
            if (gx > 0 && roomish[idx - 1] && !union(parent, tileCount, rootEntry, idx, idx - 1)) refused++;
            if (gx < GRID - 1 && roomish[idx + 1] && !union(parent, tileCount, rootEntry, idx, idx + 1)) refused++;
            if (gz > 0 && roomish[idx - GRID] && !union(parent, tileCount, rootEntry, idx, idx - GRID)) refused++;
            if (gz < GRID - 1 && roomish[idx + GRID] && !union(parent, tileCount, rootEntry, idx, idx + GRID)) refused++;
        }
        Map<String, Integer> firstByName = new HashMap<>();
        for (int idx = 0; idx < n; idx++) {
            if (!isTile[idx] || roomEntryGrid[idx] == null || roomEntryGrid[idx].name == null) {
                continue;
            }
            Integer first = firstByName.putIfAbsent(roomEntryGrid[idx].name, idx);
            if (first != null && !union(parent, tileCount, rootEntry, first, idx)) {
                refused++;
            }
        }

        java.util.Arrays.fill(groupOfCell, -1);
        groups.clear();
        Map<Integer, List<Integer>> tilesByRoot = new HashMap<>();
        Map<Integer, List<Integer>> cellsByRoot = new HashMap<>();
        for (int idx = 0; idx < n; idx++) {
            if (!roomish[idx]) {
                continue;
            }
            int root = find(parent, idx);
            if (tileCount[root] == 0) {
                continue; // connector with no tile attached (yet) - drawn as a plain cell
            }
            cellsByRoot.computeIfAbsent(root, k -> new ArrayList<>()).add(idx);
            if (isTile[idx]) {
                tilesByRoot.computeIfAbsent(root, k -> new ArrayList<>()).add(idx);
            }
        }
        for (Map.Entry<Integer, List<Integer>> e : cellsByRoot.entrySet()) {
            List<Integer> tileList = tilesByRoot.get(e.getKey());
            if (tileList == null || tileList.isEmpty()) {
                continue;
            }
            int main = tileList.get(0);
            for (int t : tileList) {
                int tx = t % GRID;
                int mx = main % GRID;
                if (tx < mx || (tx == mx && t / GRID < main / GRID)) {
                    main = t;
                }
            }
            int[] tiles = tileList.stream().mapToInt(Integer::intValue).toArray();
            int[] cells = e.getValue().stream().mapToInt(Integer::intValue).toArray();
            RoomGroup group = new RoomGroup(main, tiles, cells, rootEntry[e.getKey()]);
            int gid = groups.size();
            groups.add(group);
            for (int c : cells) {
                groupOfCell[c] = gid;
            }
        }
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static boolean union(int[] parent, int[] tileCount, RoomEntry[] rootEntry, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra == rb) {
            return true;
        }
        RoomEntry ea = rootEntry[ra];
        RoomEntry eb = rootEntry[rb];
        if (ea != null && eb != null && ea.name != null && !ea.name.equals(eb.name)) {
            return false;
        }
        RoomEntry merged = ea != null ? ea : eb;
        int count = tileCount[ra] + tileCount[rb];
        if (count > RoomDatabase.shapeTileCount(merged != null ? merged.shape : null)) {
            return false;
        }
        parent[rb] = ra;
        tileCount[ra] = count;
        rootEntry[ra] = merged;
        return true;
    }

    /** Reads the last built grouping without rebuilding - for the scan loop, which dirties it repeatedly. */
    private static boolean cachedGroupHasEntry(int idx) {
        int gid = groupOfCell[idx];
        return gid >= 0 && gid < groups.size() && groups.get(gid).entry != null;
    }

    private static RoomGroup groupAt(int idx) {
        ensureGroups();
        int gid = idx >= 0 && idx < GRID * GRID ? groupOfCell[idx] : -1;
        return gid >= 0 ? groups.get(gid) : null;
    }

    // ---------------------------------------------------------------------------------------------

    /** NoammAddons {@code ActionBarParser}: the action bar's "x/y Secrets" is the current room's count;
     *  only trusted when y matches the room's database secret total. */
    private static void onActionBar(String text) {
        if (text == null || !DungeonState.isInDungeon() || isInBoss() || !text.contains("Secrets")) {
            return;
        }
        // Legacy "§76/10 Secrets" would otherwise read as 76 found.
        Matcher m = ACTION_BAR_SECRETS.matcher(text.replaceAll("§.", ""));
        if (!m.find()) {
            return;
        }
        int idx = currentRoomIndex();
        RoomGroup group = idx < 0 ? null : groupAt(idx);
        if (group == null || group.entry == null || group.entry.name == null) {
            return;
        }
        try {
            int found = Integer.parseInt(m.group(1));
            int max = Integer.parseInt(m.group(2));
            if (max == group.entry.secrets) {
                foundSecretsByRoom.put(group.entry.name, found);
            }
        } catch (NumberFormatException ignored) {
        }
    }

    /** {@link #getHighestY} for callers outside this class - the roof probe that decides a cell holds a room. */
    public static int roofAt(net.minecraft.client.Minecraft client, int worldX, int worldZ) {
        return getHighestY(client, worldX, worldZ);
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
        if (state.is(McBlocks.RED_TERRACOTTA)) {
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

    /** For {@link com.killer560.hub.secretwaypoints.SecretWaypointsFeature} - one entry per identified
     *  ROOM (not per tile) that has both a known identity AND a known rotation/corner (needed to translate
     *  that room's stored relative secret coordinates into real world positions for THIS run):
     *  {@code [mainTileIdx, clayX, clayZ, rotationDegrees]}. */
    public static List<int[]> identifiedRoomsWithRotation() {
        ensureGroups();
        List<int[]> result = new ArrayList<>();
        for (RoomGroup group : groups) {
            if (group.entry == null) {
                continue;
            }
            int r = rotationSourceIdx(group);
            if (r >= 0) {
                result.add(new int[]{group.mainIdx, clayXGrid[r], clayZGrid[r], rotationGrid[r]});
            }
        }
        return result;
    }

    /** For {@link com.killer560.hub.dungeonalerts.RoomAlerts}' Room Cleared alert: true once the room at
     *  this identified-room index has been cleared - Hypixel's own dungeon map turns the tile green
     *  (cleared AND every secret found) or shows its plain cleared checkmark (objective done, secrets
     *  still missing); either counts here. Anything else (discovered/failed/unopened/undiscovered)
     *  does not. Package-private {@link DungeonMapScanner#stateAt} is the same source the map's own
     *  checkmark rendering already reads (see that class's doc), just exposed for another package. */
    public static boolean isRoomCleared(int idx) {
        int state = DungeonMapScanner.stateAt(idx);
        return state == DungeonMapScanner.STATE_GREEN || state == DungeonMapScanner.STATE_CLEARED;
    }

    /** @return the room identity for any cell of a room (every tile/connector of a multi-tile room gives
     *  the same entry), or null. */
    public static RoomEntry roomEntryAt(int idx) {
        if (idx < 0 || idx >= GRID * GRID) {
            return null;
        }
        RoomGroup group = groupAt(idx);
        return group != null ? group.entry : roomEntryGrid[idx];
    }

    /** @return every grid cell index (tiles and connectors) of the room containing {@code idx}, or just
     *  {@code idx} when it isn't part of a known room. */
    public static int[] roomCellIndices(int idx) {
        RoomGroup group = groupAt(idx);
        return group != null ? group.cells.clone() : new int[]{idx};
    }

    /** @return {@code [minX, minZ, maxX, maxZ]} world bounds of the whole room containing {@code idx} (each
     *  tile's 32x32 footprint, same box {@code SecretWaypointsFeature}'s mimic check builds per tile). */
    public static int[] roomWorldBounds(int idx) {
        RoomGroup group = groupAt(idx);
        int minGX = group != null ? group.minGX : idx % GRID;
        int maxGX = group != null ? group.maxGX : idx % GRID;
        int minGZ = group != null ? group.minGZ : idx / GRID;
        int maxGZ = group != null ? group.maxGZ : idx / GRID;
        return new int[]{START_X + minGX * HALF_ROOM - 16, START_Z + minGZ * HALF_ROOM - 16,
                START_X + maxGX * HALF_ROOM + 16, START_Z + maxGZ * HALF_ROOM + 16};
    }

    /** Real bug found and fixed (2026-09-14, code review + NoammAddons e42d3316 "reset when entering
     *  boss"): {@link #gridCellFor} clamps to the 11x11 grid, so standing in a boss room (outside the
     *  -201..-9 dungeon footprint) mapped to corner cell (10,10) and solvers could "match" whatever room
     *  was identified there. @return the player's current room cell index, or -1 when there's no
     *  player, the player is in boss, or the player is outside the dungeon grid footprint. */
    public static int currentRoomIndex() {
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
        if (bossLatchGraceTicks > 0) {
            bossLatchGraceTicks--;
            return;
        }
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
        if (pos.x == 0.0 && pos.y == 100.0 && pos.z == 0.0) {
            return; // still at the post-world-change placeholder, not a real position yet
        }
        if (BOSS_ROOM_BOUNDS[floorNumber - 1].contains(pos.x, pos.y, pos.z) && !insideGridFootprint(pos.x, pos.z)) {
            bossLatched = true;
            LOGGER.info("[LiveMap] Boss room entered (floor={} pos={},{},{}) - room matching disabled, solvers reset",
                    floor, (int) pos.x, (int) pos.y, (int) pos.z);
        }
    }

    public static RoomEntry currentRoomEntry() {
        int idx = currentRoomIndex();
        return idx < 0 ? null : roomEntryAt(idx);
    }

    /** For real puzzle solvers (e.g. {@code BoulderSolverFeature}) that need to translate a puzzle's own
     *  stored relative coordinates into real world positions for THIS run, the same way
     *  {@link #identifiedRoomsWithRotation()} already does for Secret Waypoints - just narrowed to
     *  whichever single room the player is currently standing in (any tile of it). @return
     *  {@code [clayX, clayZ, rotationDegrees]}, or null if the current room's identity/rotation aren't
     *  both known yet. */
    public static int[] currentRoomClayAndRotation() {
        int idx = currentRoomIndex();
        if (idx < 0) {
            return null;
        }
        RoomGroup group = groupAt(idx);
        if (group == null) {
            return roomEntryGrid[idx] == null || rotationGrid[idx] < 0 ? null
                    : new int[]{clayXGrid[idx], clayZGrid[idx], rotationGrid[idx]};
        }
        int r = rotationSourceIdx(group);
        if (group.entry == null || r < 0) {
            return null;
        }
        return new int[]{clayXGrid[r], clayZGrid[r], rotationGrid[r]};
    }

    // ---------------------------------------------------------------------------------------------
    // Interactive map / pathing accessors (package-private, main thread)
    // ---------------------------------------------------------------------------------------------

    static int resetGeneration() {
        return resetGeneration;
    }

    /** Client ticks since load - the invalidation key for every per-frame render cache in this package. */
    static int tickCount() {
        return tickCounter;
    }

    static List<RoomGroup> groupsView() {
        ensureGroups();
        return groups;
    }

    /** @return the room group id owning this cell, or -1. */
    static int groupIdAt(int idx) {
        ensureGroups();
        return idx >= 0 && idx < GRID * GRID ? groupOfCell[idx] : -1;
    }

    /** World-scanned tile, else what the dungeon map item shows (rooms from grouping, doors from the map). */
    static Tile effectiveTile(int idx) {
        Tile tile = grid[idx];
        if (tile != Tile.UNKNOWN) {
            return tile;
        }
        if (groupIdAt(idx) >= 0) {
            return Tile.ROOM;
        }
        return DungeonMapScanner.doorTileAt(idx);
    }

    static boolean isWorldScanned(int idx) {
        return grid[idx] != Tile.UNKNOWN;
    }

    /**
     * The action bar's own per-room secret count, which is the number Hypixel shows HIM.
     *
     * <p>Public because {@code DungeonInfoFeature} needs it: that class was deriving a per-room count by
     * subtracting the tab list's run total, and the tab list's total is the WHOLE TEAM's - so every secret a
     * teammate found anywhere on the floor was credited to whichever room he happened to be standing in.
     * The action bar line is per room and is already parsed here.
     *
     * @return the count, or -1 when no action bar line has been seen for that room this run
     */
    public static int foundSecretsForRoom(String roomName) {
        return foundSecrets(roomName);
    }

    static int foundSecrets(String roomName) {
        Integer found = roomName == null ? null : foundSecretsByRoom.get(roomName);
        return found == null ? -1 : found;
    }

    /** @return {@code [clayX, clayZ, rotation]} of a room, or null while identity/rotation are unknown. */
    static int[] clayAndRotation(RoomGroup group) {
        int r = rotationSourceIdx(group);
        return group.entry == null || r < 0 ? null : new int[]{clayXGrid[r], clayZGrid[r], rotationGrid[r]};
    }

    public static final class LiveMapHudElement implements HudElement {
        @Override
        public String id() {
            return "live_map";
        }

        @Override
        public String displayName() {
            return "Dungeon Map";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 500;
        }

        /** The map is {@link MapPainter#MAP_UNITS} units square plus a 2px margin each side, NOT an 11x11 grid of
         *  equal cells - rooms are 16 units and gaps 4, exactly like the real dungeon map. */
        private static int mapSize() {
            return Math.round(MapPainter.MAP_UNITS * LiveMapConfig.getInstance().getRoomPx() / 16f) + 4;
        }

        @Override
        public int width() {
            return mapSize();
        }

        @Override
        public int height() {
            // killer560, 2026-09-20: "remove room name below map" - the HUD is exactly the map now, no extra row.
            return mapSize();
        }

        @Override
        public boolean isRelevantNow() {
            return LiveMapConfig.getInstance().isEnabled() && DungeonState.isInDungeon() && !isInBoss()
                    && !MapPainter.onP3Sim();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            LiveMapConfig cfg = LiveMapConfig.getInstance();
            // Chat never hides the map any more (killer560: "dont make it hide the gui if i open chat") - it used to
            // stay only for the interactive map's "Open From HUD Click"; that click path still works the same.
            // killer560: "if you detect I am on the p3sim then make it hide the dungeon map just like it does in
            // boss rooms of dungeons" - isInBoss() is the one boss flag this whole package already gates room
            // matching/solvers on; p3sim now shares that same gate (via MapPainter.onP3Sim(), not a second IP
            // check) instead of a parallel hide rule. Interactive Map / teleport pathing / Auto Blood Rush are
            // untouched - those still work on p3sim off the world scan, same as before this change.
            if (!cfg.isEnabled() || HudVisibility.hidesHud() || !DungeonState.isInDungeon() || isInBoss()
                    || MapPainter.onP3Sim()) {
                return;
            }
            Minecraft client = Minecraft.getInstance();
            ensureGroups();

            // HUD peek: while the peek key is held, draw enlarged, shifted so it stays on screen.
            float peek = InteractiveMapFeature.isPeeking() ? cfg.getPeekScale() : 1f;
            boolean peeking = peek > 1f;
            if (peeking) {
                int[] pos = com.killer560.hub.hud.HudElementRegistry.resolvePosition(this);
                float hudScale = com.killer560.hub.hud.HudElementRegistry.resolveScale(this);
                float drawnW = width() * hudScale * peek;
                float drawnH = height() * hudScale * peek;
                float shiftX = Math.min(0, graphics.guiWidth() - (pos[0] + drawnW)) - Math.min(0, pos[0]);
                float shiftY = Math.min(0, graphics.guiHeight() - (pos[1] + drawnH)) - Math.min(0, pos[1]);
                graphics.pose().pushMatrix();
                graphics.pose().translate(shiftX / hudScale, shiftY / hudScale);
                graphics.pose().scale(peek, peek);
            }
            try {
                renderMap(graphics, x, y, cfg, client);
            } finally {
                if (peeking) {
                    graphics.pose().popMatrix();
                }
            }
        }

        /** killer560, 2026-09-17: "the live map hud does not look like the real dungeon map". It used to paint all
         *  121 cells as same-size grey squares with a 1px inset on every side, so rooms, corridors and gaps were
         *  indistinguishable and nothing ever touched. It now shares the interactive map's painter
         *  ({@link MapPainter}): 16-unit rooms, flush 4-unit connectors, real per-type colours and sprite
         *  checkmarks - the same geometry and palette as the held map itself. */
        private void renderMap(GuiGraphicsExtractor graphics, int x, int y, LiveMapConfig cfg, Minecraft client) {
            float ppu = cfg.getRoomPx() / 16f;
            int size = mapSize();
            graphics.fill(x, y, x + size, y + size, cfg.getMapBackground());
            int border = cfg.getMapBorderColor();
            if ((border >>> 24) != 0) {
                graphics.outline(x, y, size, size, border);
            }
            // Automatic zoom for small floors - no toggle, on his instruction. Applied by moving the origin
            // and multiplying the pixels-per-unit, so every drawing call below is untouched: doors, rooms,
            // labels, teammates and the player arrow all scale together because they all go through ppu.
            DungeonLayout fitLayout = DungeonLayout.current();
            float[] fit = MapPainter.autoFit(groups);
            ppu *= fit[0];
            float ox = x + 2 + fit[1] * ppu;
            float oy = y + 2 + fit[2] * ppu;

            // Per-tick snapshot, never per frame: capture() walks all 121 cells and block-checks every wither/blood
            // door (fps report 2026-09-20).
            DungeonLayout layout = fitLayout;
            MapPainter.drawDoors(graphics, layout, cfg, ox, oy, ppu, -1);
            MapPainter.drawReportedDoors(graphics, cfg, ox, oy, ppu);

            // killer560, 2026-09-20: "remove the current-room colour changer" - every revealed room just draws its
            // real colour now, current room or not.
            for (int gid = 0; gid < groups.size(); gid++) {
                RoomGroup group = groups.get(gid);
                if (!MapPainter.isRevealed(group)) {
                    continue; // legit build: the map item has not shown this room yet
                }
                MapPainter.drawRoom(graphics, group, gid, MapPainter.roomColor(group, cfg), ox, oy, ppu);
            }

            // killer560s-mod-relay task (2026-09-21): teammate-reported rooms this client has not scanned
            // itself yet - PartyMapIntel already dropped any cell local scanning has since taken over.
            for (PartyMapIntel.ReportedRoom rr : PartyMapIntel.reportedRoomsView()) {
                MapPainter.drawReportedRoom(graphics, rr, cfg, ox, oy, ppu);
            }

            MapPainter.drawLabels(graphics, client.font, cfg.getRoomLabels(), cfg, ox, oy, ppu);
            for (PartyMapIntel.ReportedRoom rr : PartyMapIntel.reportedRoomsView()) {
                MapPainter.drawReportedLabel(graphics, client.font, cfg.getRoomLabels(), cfg, rr, ox, oy, ppu);
            }

            // Real arrow/head markers at the exact world position instead of a whole cell filled yellow. The list is
            // cached per tick - it used to run a fresh level.players() party scan every frame.
            for (InteractiveMapFeature.MapPlayer mp : InteractiveMapFeature.playersCached(client)) {
                if (!mp.self() && !cfg.isShowTeammates()) {
                    continue;
                }
                MapPainter.drawMarker(graphics, client.font, mp, cfg, ox, oy, ppu, ppu,
                        cfg.isClassRecolorTeammates(), false, -1, -1);
            }
        }
    }
}
