package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import net.minecraft.core.BlockPos;

/** Room-relative <-> real coordinate shorthands (QUOI {@code OdonRoom.getRealCoords/getRelativeCoords}) on top of
 *  {@link RoomDatabase#toRealCoord}/{@link RoomDatabase#toRelativeCoord}, the same transform every existing solver
 *  uses. {@code clayAndRotation} is {@code LiveMapFeature.currentRoomClayAndRotation()}. */
public final class PuzzleCoords {

    private PuzzleCoords() {
    }

    /**
     * A room-relative coordinate in the world.
     *
     * <p><b>The y carries the sim's floor shift.</b> {@link RoomDatabase#toRealCoord} passes y straight
     * through - it only ever rotates x and z - so a relative y IS a world y on Hypixel, where every dungeon
     * floor sits at the same height. The dungeon sim shifts the whole map vertically
     * ({@code SimAltitude}), so the same call there returns a position tens of blocks away from the room it
     * was measured in. That is why killer560's solvers all drew nothing useful in the sim: not one of them
     * was wrong about the ROOM, they were all pointing at the right x and z at Hypixel's height.
     *
     * <p>{@link com.killer560.hub.livemap.DungeonLayout#simYOffset()} is zero outside the sim, so a real run
     * is byte-for-byte unchanged. It is the same helper {@code DungeonLayout.doorBlock} and
     * {@code cellCenter} already use, and it exists because four callers had each written their own literal
     * height - this is the fifth place that needed it.
     */
    public static BlockPos real(int x, int y, int z, int[] clayAndRotation) {
        RoomEntry.Pos relative = new RoomEntry.Pos();
        relative.x = x;
        relative.y = y + com.killer560.hub.livemap.DungeonLayout.simYOffset();
        relative.z = z;
        return RoomDatabase.toRealCoord(relative, clayAndRotation[0], clayAndRotation[1], clayAndRotation[2]);
    }

    public static BlockPos real(BlockPos relative, int[] clayAndRotation) {
        return real(relative.getX(), relative.getY(), relative.getZ(), clayAndRotation);
    }

    /** The inverse, undoing the same shift - see {@link #real(int, int, int, int[])}. */
    public static BlockPos relative(BlockPos real, int[] clayAndRotation) {
        RoomEntry.Pos pos = RoomDatabase.toRelativeCoord(real, clayAndRotation[0], clayAndRotation[1], clayAndRotation[2]);
        return new BlockPos(pos.x, pos.y - com.killer560.hub.livemap.DungeonLayout.simYOffset(), pos.z);
    }
}
