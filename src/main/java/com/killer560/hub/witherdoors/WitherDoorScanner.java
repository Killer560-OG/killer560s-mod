package com.killer560.hub.witherdoors;

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
 * Local door-grid scan for Wither Doors, same QUOI-derived algorithm as
 * {@link com.killer560.hub.doorhelpers.DoorScanner} (11x11 grid, door cells are where exactly one of
 * gx/gz is odd, type read from the block at y=69 the first time a cell is seen). A second copy of that
 * scanner exists here - not a reuse of the one in {@code doorhelpers} - for two reasons: that class's
 * {@code tick(Minecraft)} is package-private, so it cannot be driven from this package at all; and even
 * if it were public, it is only ever ticked while {@code DoorHelpersConfig}'s Auto Door Opener or Look At
 * Door (both cheat-build-only) are switched on, so the legit jar's copy of that cache would sit empty
 * forever. Wither Doors needs its own detection that runs whenever ITS OWN toggle is on, in both builds.
 */
final class WitherDoorScanner {

    enum DoorType { BLOOD, NORMAL, WITHER, ENTRANCE }

    /** {@code y} is the anchor block's height: Hypixel's 69, plus the sim's floor shift when the cell was scanned. */
    record Door(int x, int y, int z, DoorType type) {
        BlockPos basePos() {
            return new BlockPos(x, y, z);
        }

        /** True if this door's wall runs along Z (i.e. it connects rooms that are side-by-side in X) -
         *  used only to orient the highlight box; the lock/type detection above never needs it. */
        boolean wallAlongZ() {
            return Math.floorMod((x - START) / HALF_ROOM, 2) == 1;
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

    private WitherDoorScanner() {
    }

    static void reset() {
        Arrays.fill(cells, null);
        ticksUntilScan = 0;
    }

    /** Called once per client tick while Wither Doors is on. */
    static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            reset();
        }
        if (client.level == null || --ticksUntilScan > 0) {
            return;
        }
        ticksUntilScan = RESCAN_TICKS;
        // Every height below is Hypixel's; the sim builds the whole floor shifted (DungeonLayout.simYOffset, zero on
        // Hypixel), and read at the bare heights its doors were never found (roof 73+ against a floor near y -60).
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
                if (!client.level.hasChunk(wx >> 4, wz >> 4)) {
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
                    cells[idx] = new Door(wx, 69 + off, wz, type);
                } else {
                    cells[idx] = NOT_A_DOOR;
                }
            }
        }
    }

    /** Section-first column scan - the same one, and for the same reasons, as
     *  {@code doorhelpers.DoorScanner.topLayer}; see its note. Both scanners are registered independently, so a
     *  dungeon with both features on paid the old block-by-block sweep twice. */
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
            // Hypixel's 0..255 band, moved with the sim's floor.
            for (int y = Math.min(base + 15, 255 + off); y >= Math.max(base, 1 + off); y--) {
                if (!section.getBlockState(x & 15, y & 15, z & 15).isAir()) {
                    return y;
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    /** Same rule as {@code doorhelpers.DoorScanner.isLocked}: only WITHER/BLOOD cells can be locked, and an
     *  opened one is air at its anchor block. */
    static boolean isLocked(Minecraft client, Door door) {
        if (door.type() != DoorType.WITHER && door.type() != DoorType.BLOOD) {
            return false;
        }
        return client.level != null && !(client.level.getBlockState(door.basePos()).getBlock() instanceof AirBlock);
    }

    static List<Door> lockedDoors(Minecraft client) {
        List<Door> out = new ArrayList<>();
        for (Object cell : cells) {
            if (cell instanceof Door door && isLocked(client, door)) {
                out.add(door);
            }
        }
        return out;
    }
}
