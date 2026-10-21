package com.killer560.hub.doorhelpers;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.killer560.hub.compat.McBlocks;

/**
 * Local door scan for Door Helpers - a port of QUOI {@code odonscanning/ScanUtils.scanTile}'s door branch plus
 * {@code tiles/OdonDoor.locked}, kept local because {@code livemap.LiveMapFeature} exposes no door accessor (and only
 * scans while one of its own consumers is enabled).
 * <ul>
 * <li>Grid: 11x11 cells at world {@code (-185 + gx*16, -185 + gz*16)}; door cells are the ones where exactly one of
 * gx/gz is odd (QUOI: not both even = room, not both odd = 2x2 centre).
 * <li>A door cell's roof (highest non-air block) is 73/74/81/82. Its type comes from the block at y 69 the first time
 * the cell is scanned: COAL_BLOCK = WITHER, RED_TERRACOTTA = BLOOD, INFESTED_CHISELED_STONE_BRICKS = ENTRANCE, else
 * NORMAL. Once a cell is classified it is never re-scanned (QUOI keeps {@code scannedDoors} until world change).
 * <li>{@code locked}: WITHER/BLOOD and the block at (x, 69, z) is not an {@link AirBlock} - an opened door is air.
 * </ul>
 */
public final class DoorScanner {

    public enum DoorType { BLOOD, NORMAL, WITHER, ENTRANCE }

    public record Door(int x, int z, DoorType type) {
        public BlockPos basePos() {
            // Hypixel's floor height, moved with the sim's floor (zero on Hypixel).
            return new BlockPos(x, 69 + com.killer560.hub.livemap.DungeonLayout.simYOffset(), z);
        }
    }

    private static final int GRID = 11;
    private static final int START = -185;
    private static final int HALF_ROOM = 16;
    private static final int RESCAN_TICKS = 10;

    /** null = cell not scanned yet (unloaded / no roof); NOT_A_DOOR marks a scanned non-door odd cell. */
    private static final Object NOT_A_DOOR = new Object();
    private static final Object[] cells = new Object[GRID * GRID];
    /** Each door cell's lock as last READ. A wither or blood door whose chunk the client has let go of reads as air
     *  - "opened" - so while its chunk is not loaded the last real reading stands (a door never re-locks). */
    private static final boolean[] lastLocked = new boolean[GRID * GRID];
    private static Object lastLevel = null;
    private static int ticksUntilScan = 0;

    private DoorScanner() {
    }

    public static void reset() {
        Arrays.fill(cells, null);
        Arrays.fill(lastLocked, false);
        ticksUntilScan = 0;
    }

    /** Called once per client tick while a Door Helpers feature needs door data. */
    static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            reset();
        }
        if (client.level == null || --ticksUntilScan > 0) {
            return;
        }
        ticksUntilScan = RESCAN_TICKS;
        // Every height below is Hypixel's; the sim builds the floor shifted (DungeonLayout.simYOffset, zero on Hypixel),
        // and read at the bare heights its doors were never found (so Blood Rush's opener had nothing to click).
        int off = com.killer560.hub.livemap.DungeonLayout.simYOffset();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int gx = 0; gx < GRID; gx++) {
            for (int gz = 0; gz < GRID; gz++) {
                boolean colEven = gx % 2 == 0;
                boolean rowEven = gz % 2 == 0;
                if (colEven == rowEven) {
                    continue; // room (both even) or 2x2 centre (both odd)
                }
                int idx = gx + gz * GRID;
                if (cells[idx] != null) {
                    continue;
                }
                int wx = START + gx * HALF_ROOM;
                int wz = START + gz * HALF_ROOM;
                if (!com.killer560.hub.util.ClientChunks.isLoadedAt(client.level, wx, wz)) {
                    continue;
                }
                int height = topLayer(client, mutable, wx, wz, off);
                if (height == Integer.MIN_VALUE) {
                    continue;
                }
                height -= off;
                if (height == 73 || height == 74 || height == 81 || height == 82) {
                    Block block = client.level.getBlockState(mutable.set(wx, 69 + off, wz)).getBlock();
                    DoorType type = block == Blocks.COAL_BLOCK ? DoorType.WITHER
                            : block == McBlocks.RED_TERRACOTTA ? DoorType.BLOOD
                            : block == Blocks.INFESTED_CHISELED_STONE_BRICKS ? DoorType.ENTRANCE
                            : DoorType.NORMAL;
                    cells[idx] = new Door(wx, wz, type);
                    lastLocked[idx] = type == DoorType.WITHER || type == DoorType.BLOOD;
                } else {
                    cells[idx] = NOT_A_DOOR;
                }
            }
        }
    }

    /**
     * The highest non-air block in this column in Hypixel's y 1..255 band (shifted by {@code off} in the sim), or
     * {@link Integer#MIN_VALUE} when there is none.
     *
     * <p>Section-first, rather than a block-by-block walk down from 255. A door's roof is at 73-82, so the old
     * loop threw away about 175 reads per cell before it reached anything - and a cell over open void read all
     * 256 and returned nothing, which the caller does NOT cache (it means "nothing there yet", so the cell stays
     * unresolved), so every empty cell was re-swept from the top every RESCAN_TICKS, forever. An 11x11 grid
     * with both this scanner and WitherDoorScanner registered paid that twice a tick-pair.
     *
     * <p>A section whose palette is only air cannot hold the answer, so asking {@code hasOnlyAir()} first skips
     * the whole empty top of the world in one test each. Reading through the section also skips the
     * chunk lookup the level-level getBlockState would redo for every single y.
     */
    private static int topLayer(Minecraft client, BlockPos.MutableBlockPos mutable, int x, int z, int off) {
        net.minecraft.world.level.chunk.LevelChunk chunk = client.level.getChunk(x >> 4, z >> 4);
        net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
        int top = Math.min(chunk.getHighestFilledSectionIndex(), sections.length - 1);
        for (int i = top; i >= 0; i--) {
            net.minecraft.world.level.chunk.LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            int base = client.level.getSectionYFromSectionIndex(i) << 4;
            // Hypixel's 1..255 band (y 0 never held a roof), moved with the sim's floor.
            for (int y = Math.min(base + 15, 255 + off); y >= Math.max(base, 1 + off); y--) {
                if (!section.getBlockState(x & 15, y & 15, z & 15).isAir()) {
                    return y;
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    /** QUOI {@code OdonDoor.locked}, except that a door whose chunk is not loaded keeps its last real reading (see
     *  {@link #lastLocked}). */
    public static boolean isLocked(Minecraft client, Door door) {
        int gx = Math.floorDiv(door.x() - START, HALF_ROOM);
        int gz = Math.floorDiv(door.z() - START, HALF_ROOM);
        if (gx < 0 || gx >= GRID || gz < 0 || gz >= GRID || cells[gx + gz * GRID] != door) {
            return readLocked(client, door);
        }
        return lockedAt(client, gx + gz * GRID);
    }

    private static boolean lockedAt(Minecraft client, int idx) {
        if (!(cells[idx] instanceof Door door) || (door.type() != DoorType.WITHER && door.type() != DoorType.BLOOD)
                || client.level == null) {
            return false;
        }
        if (com.killer560.hub.util.ClientChunks.isLoadedAt(client.level, door.x(), door.z())) {
            lastLocked[idx] = readLocked(client, door);
        }
        return lastLocked[idx];
    }

    private static boolean readLocked(Minecraft client, Door door) {
        if (door.type() != DoorType.WITHER && door.type() != DoorType.BLOOD) {
            return false;
        }
        return client.level != null && !(client.level.getBlockState(door.basePos()).getBlock() instanceof AirBlock);
    }

    /** QUOI {@code ScanUtils.scannedDoors.filter { it.locked }}. */
    public static List<Door> lockedDoors(Minecraft client) {
        List<Door> out = new ArrayList<>();
        for (int idx = 0; idx < cells.length; idx++) {
            if (cells[idx] instanceof Door door && lockedAt(client, idx)) {
                out.add(door);
            }
        }
        return out;
    }
}
