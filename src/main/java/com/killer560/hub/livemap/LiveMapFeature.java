package com.killer560.hub.livemap;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.leapmenu.LeapMenuConfig;
import com.killer560.hub.leapmenu.LeapMenuFeature;
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
 * <b>Real, disclosed scope-down from NoammAddons' own map:</b> NoammAddons identifies each room's
 * actual TYPE/NAME by hashing every block from y=140 down to y=12 at that grid cell and matching the
 * hash against a downloaded room database (`rooms-modern.json`, ~hundreds of known room layouts) -
 * that database isn't something this session has access to, and guessing room identities without it
 * would just be wrong. What IS real and confirmed (read directly from NoammAddons'
 * {@code DungeonScanner.kt}/{@code ScanUtils.kt}/{@code DoorType.kt}, not guessed) and reused here:
 * <ul>
 * <li>The dungeon's room grid is a FIXED 11x11 coordinate system starting at world (-185, -185) with
 * each room cell 32 blocks wide (even grid indices = room centers/16-block half-steps, odd = the
 * corridors between them) - the same for every single dungeon run, regardless of which room layout
 * ends up placed there.
 * <li>Whether a grid cell is a room or a doorway can be told apart, database-free, purely from the
 * real "roof height" at that cell (a doorway's ceiling sits at a real, fixed 73/74/81/82) and the real
 * door-type blocks (Blood = red terracotta, Wither = coal block, Entrance = infested chiseled stone
 * bricks, a plain opened door = none of those).
 * </ul>
 * So this draws real room SHAPES and real door TYPES/positions and real live player dots - genuinely
 * useful for seeing the maze layout and who's where - but it does NOT show room names, secret counts,
 * or mimic-room detection, all of which need that missing database. A real next step once that data
 * exists (or killer560 provides it) is teaching this same grid to look room names up instead of just
 * marking "a room is here".
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
    private static long lastScanAtMs = 0;
    private static boolean wasInDungeon = false;

    static {
        java.util.Arrays.fill(grid, Tile.UNKNOWN);
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
        }
        wasInDungeon = inDungeon;

        if (!LiveMapConfig.getInstance().isEnabled() || !inDungeon || DungeonState.isBossPhaseActive()) {
            return;
        }
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
                if (grid[idx] != Tile.UNKNOWN) {
                    continue;
                }
                int wx = START_X + x * HALF_ROOM;
                int wz = START_Z + z * HALF_ROOM;
                BlockPos probe = new BlockPos(wx, 70, wz);
                if (!client.level.isLoaded(probe)) {
                    continue;
                }
                int roofHeight = getHighestY(client, wx, wz);
                if (roofHeight <= 0) {
                    continue;
                }

                boolean rowEven = z % 2 == 0;
                boolean colEven = x % 2 == 0;
                if (rowEven && colEven) {
                    grid[idx] = Tile.ROOM;
                } else if (roofHeight == 73 || roofHeight == 74 || roofHeight == 81 || roofHeight == 82) {
                    grid[idx] = classifyDoor(client, wx, wz);
                } else {
                    // Corridor/connector for a larger room - NoammAddons copies the parent room's own
                    // identity here; without room identification this mod just marks it as "a room is
                    // here too", a real simplification, not a guess about what's actually there.
                    grid[idx] = Tile.ROOM;
                }
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
    private static int[] gridCellFor(Vec3 pos) {
        int roomIndexX = (int) Math.round((pos.x - START_X) / 32.0);
        int roomIndexZ = (int) Math.round((pos.z - START_Z) / 32.0);
        int gx = Math.max(0, Math.min(10, roomIndexX * 2));
        int gz = Math.max(0, Math.min(10, roomIndexZ * 2));
        return new int[]{gx, gz};
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
            return GRID * LiveMapConfig.getInstance().getCellSize();
        }

        @Override
        public int height() {
            return GRID * LiveMapConfig.getInstance().getCellSize();
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
        }
    }
}
