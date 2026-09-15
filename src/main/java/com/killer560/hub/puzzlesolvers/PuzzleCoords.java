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

    public static BlockPos real(int x, int y, int z, int[] clayAndRotation) {
        RoomEntry.Pos relative = new RoomEntry.Pos();
        relative.x = x;
        relative.y = y;
        relative.z = z;
        return RoomDatabase.toRealCoord(relative, clayAndRotation[0], clayAndRotation[1], clayAndRotation[2]);
    }

    public static BlockPos real(BlockPos relative, int[] clayAndRotation) {
        return real(relative.getX(), relative.getY(), relative.getZ(), clayAndRotation);
    }

    public static BlockPos relative(BlockPos real, int[] clayAndRotation) {
        RoomEntry.Pos pos = RoomDatabase.toRelativeCoord(real, clayAndRotation[0], clayAndRotation[1], clayAndRotation[2]);
        return new BlockPos(pos.x, pos.y, pos.z);
    }
}
