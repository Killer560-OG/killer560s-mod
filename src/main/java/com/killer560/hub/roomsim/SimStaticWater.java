package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.util.ModLog;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dungeon water stands still in the sim, except in the Water Board.
 *
 * <p>killer560 (2026-10-13): "make it so if there is water it will not update its flow path outside of water board
 * in sim." Hypixel's dungeon water is static - a pasted room's falls and pools are exactly as captured and never
 * spread, drain or grow a new flowing block. Vanilla water in the sim did all three: a fall without its source
 * drained, a pool with an open side ran across the floor, a wall the Dungeon Breaker opened let it out. The Water
 * Board is the one room whose puzzle IS the water flowing ({@code SimWaterPuzzle} lets it down the maze with real
 * fluid ticks), so it is the one room left alone.
 *
 * <p>How: {@code SimStaticWaterMixin} cancels {@code FlowingFluid.tick} for WATER on the integrated server's
 * overworld while {@link SimState#isActive()}, unless the position is inside a placed Water Board room's paste box.
 * A canceled tick is simply consumed - nothing spreads, nothing drains, nothing reschedules - and a later neighbor
 * update that schedules another gets canceled the same way. A normal singleplayer world (SimState inactive) and
 * Hypixel (no integrated server ticks fluids for it) are untouched.
 *
 * <p>The Water Board's area is its PASTE box - the same anchor {@code RoomPlacer.PasteJob} pastes from
 * ({@code cellCenter - TILE/2 - margin}) and the room's own size, turned by its paste rotation - read from
 * {@link SimRoomIndex}, so it exists whether or not the puzzle itself armed. Every y is included.
 */
public final class SimStaticWater {

    private static final Logger LOGGER = ModLog.get("killer560smod-roomsim");

    /** A Water Board paste box, inclusive, in world x/z. */
    private record Box(int minX, int minZ, int maxX, int maxZ) {
        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    private static volatile List<Box> boxes = List.of();
    private static volatile int boxesVersion = -1;

    /** Water ticks held still, and Water Board ticks let through - the proof the hook runs. Server thread only. */
    private static volatile long frozen = 0;
    private static volatile long allowed = 0;
    private static volatile boolean loggedThisFloor = false;

    private SimStaticWater() {
    }

    /**
     * Whether a water tick at this position must be canceled. Called from the mixin on the server thread, once per
     * scheduled water tick in any level - so the cheap tests come first.
     */
    public static boolean shouldFreeze(ServerLevel level, BlockPos pos) {
        if (!SimState.isActive() || level != level.getServer().overworld()) {
            return false;
        }
        for (Box box : currentBoxes()) {
            if (box.contains(pos.getX(), pos.getZ())) {
                allowed++;
                return false;
            }
        }
        frozen++;
        if (!loggedThisFloor) {
            loggedThisFloor = true;
            LOGGER.info("Sim water: holding water still outside the Water Board (first at {}; Water Board box(es) {})",
                    pos.toShortString(), describe());
        }
        return true;
    }

    /** Water ticks canceled since the game started. */
    public static long frozenTicks() {
        return frozen;
    }

    /** Water ticks let through inside a Water Board since the game started. */
    public static long allowedTicks() {
        return allowed;
    }

    /** The Water Board box(es) currently exempt, as {@code minX,minZ..maxX,maxZ}; "none" without one. */
    public static String describe() {
        List<Box> list = currentBoxes();
        if (list.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (Box b : list) {
            sb.append(sb.isEmpty() ? "" : " ").append(b.minX).append(',').append(b.minZ)
                    .append("..").append(b.maxX).append(',').append(b.maxZ);
        }
        return sb.toString();
    }

    private static List<Box> currentBoxes() {
        int v = SimRoomIndex.version();
        if (v != boxesVersion) {
            List<Box> out = new ArrayList<>();
            for (SimRoomIndex.Placed p : SimRoomIndex.placed()) {
                if (!"water board".equals(p.name().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                RoomLibrary.Room room = RoomLibrary.get(p.name());
                if (room == null) {
                    continue;
                }
                BlockPos origin = DungeonLayout.cellCenter(p.gridZ() * DungeonLayout.GRID + p.gridX());
                int x0 = origin.getX() - RoomLibrary.TILE / 2 - room.margin;
                int z0 = origin.getZ() - RoomLibrary.TILE / 2 - room.margin;
                boolean quarter = p.pasteRotation() == 90 || p.pasteRotation() == 270;
                int sx = quarter ? room.sizeZ : room.sizeX;
                int sz = quarter ? room.sizeX : room.sizeZ;
                out.add(new Box(x0, z0, x0 + sx - 1, z0 + sz - 1));
            }
            boxes = List.copyOf(out);
            boxesVersion = v;
            loggedThisFloor = false;
        }
        return boxes;
    }
}
