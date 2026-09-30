package com.killer560.hub.doorhelpers;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
            return new BlockPos(x, 69, z);
        }
    }

    private static final int GRID = 11;
    private static final int START = -185;
    private static final int HALF_ROOM = 16;
    private static final int RESCAN_TICKS = 10;

    /** null = cell not scanned yet (unloaded / no roof); NOT_A_DOOR marks a scanned non-door odd cell. */
    private static final Object NOT_A_DOOR = new Object();
    private static final Object[] cells = new Object[GRID * GRID];
    private static Object lastLevel = null;
    private static int ticksUntilScan = 0;

    private DoorScanner() {
    }

    public static void reset() {
        Arrays.fill(cells, null);
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
                if (!client.level.hasChunk(wx >> 4, wz >> 4)) {
                    continue;
                }
                int height = topLayer(client, mutable, wx, wz);
                if (height <= 0) {
                    continue;
                }
                if (height == 73 || height == 74 || height == 81 || height == 82) {
                    Block block = client.level.getBlockState(mutable.set(wx, 69, wz)).getBlock();
                    DoorType type = block == Blocks.COAL_BLOCK ? DoorType.WITHER
                            : block == Blocks.RED_TERRACOTTA ? DoorType.BLOOD
                            : block == Blocks.INFESTED_CHISELED_STONE_BRICKS ? DoorType.ENTRANCE
                            : DoorType.NORMAL;
                    cells[idx] = new Door(wx, wz, type);
                } else {
                    cells[idx] = NOT_A_DOOR;
                }
            }
        }
    }

    /**
     * The highest non-air block in this column between y 0 and 255, or 0 when there is none.
     *
     * <p>Section-first, rather than a block-by-block walk down from 255. A door's roof is at 73-82, so the old
     * loop threw away about 175 reads per cell before it reached anything - and a cell over open void read all
     * 256 and returned 0, which the caller does NOT cache (a 0 means "nothing there yet", so the cell stays
     * unresolved), so every empty cell was re-swept from the top every RESCAN_TICKS, forever. An 11x11 grid
     * with both this scanner and WitherDoorScanner registered paid that twice a tick-pair.
     *
     * <p>A section whose palette is only air cannot hold the answer, so asking {@code hasOnlyAir()} first skips
     * the whole empty top of the world in one test each. Reading through the section also skips the
     * chunk lookup the level-level getBlockState would redo for every single y.
     */
    private static int topLayer(Minecraft client, BlockPos.MutableBlockPos mutable, int x, int z) {
        net.minecraft.world.level.chunk.LevelChunk chunk = client.level.getChunk(x >> 4, z >> 4);
        net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
        int top = Math.min(chunk.getHighestFilledSectionIndex(), sections.length - 1);
        for (int i = top; i >= 0; i--) {
            net.minecraft.world.level.chunk.LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            int base = client.level.getSectionYFromSectionIndex(i) << 4;
            // Same 0..255 window the old loop used, so a world with a negative min Y still reports what it did.
            for (int y = Math.min(base + 15, 255); y >= Math.max(base, 0); y--) {
                if (!section.getBlockState(x & 15, y & 15, z & 15).isAir()) {
                    return y;
                }
            }
        }
        return 0;
    }

    /** QUOI {@code OdonDoor.locked}. */
    public static boolean isLocked(Minecraft client, Door door) {
        if (door.type() != DoorType.WITHER && door.type() != DoorType.BLOOD) {
            return false;
        }
        return client.level != null && !(client.level.getBlockState(door.basePos()).getBlock() instanceof AirBlock);
    }

    /** QUOI {@code ScanUtils.scannedDoors.filter { it.locked }}. */
    public static List<Door> lockedDoors(Minecraft client) {
        List<Door> out = new ArrayList<>();
        for (Object cell : cells) {
            if (cell instanceof Door door && isLocked(client, door)) {
                out.add(door);
            }
        }
        return out;
    }
}
