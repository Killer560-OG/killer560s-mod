package com.killer560.hub.autoroutes;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Sub-block-precision variants of {@link RoomDatabase#toRealCoord} / {@link RoomDatabase#toRelativeCoord}, plus the
 * yaw rotation those integer transforms never needed. {@code RoomDatabase} itself is untouched (it is the proven
 * secret-waypoint transform and every puzzle solver depends on it).
 * <p>
 * The block transforms rotate integer block coordinates as points, which means the block at relative {@code (x, z)}
 * lands at real {@code rotate(x, z) + clay}. For a continuous point inside that block to end up inside the SAME real
 * block, the negated axis has to be reflected across the cell ({@code 1 - p}, not {@code -p}) - otherwise a point at
 * relative {@code (0.5, 0.5)} would land in real block {@code -1}, one block off from where
 * {@code RoomDatabase.toRealCoord} puts block {@code 0}. Verified against the integer version: for every rotation,
 * {@code containing(toReal(block + 0.5))} equals {@code RoomDatabase.toRealCoord(block)}.
 * <p>
 * Yaw: rotating a direction {@code (-sin yaw, cos yaw)} by the same 90-degree steps gives {@code real = rel -
 * rotation}. Only ever used to build a target that is then applied as a wrapped DELTA onto the live yaw (Rotation
 * 360 rule) - a stored relative yaw is data, the live yaw is never assigned from it.
 */
public final class RouteCoords {

    private RouteCoords() {
    }

    /** The current room's identity and transform, captured once per tick from the Live Map. */
    public record Frame(String roomName, int clayX, int clayZ, int rotation) {

        /** @return the room the player is standing in, or null while its identity/rotation are unknown. */
        public static Frame current() {
            RoomEntry entry = LiveMapFeature.currentRoomEntry();
            int[] cr = LiveMapFeature.currentRoomClayAndRotation();
            if (entry == null || entry.name == null || cr == null) {
                return null;
            }
            return new Frame(entry.name, cr[0], cr[1], cr[2]);
        }

        public boolean sameRoom(Frame other) {
            return other != null && roomName.equals(other.roomName) && clayX == other.clayX && clayZ == other.clayZ
                    && rotation == other.rotation;
        }
    }

    private static int normalize(int degrees) {
        return ((degrees % 360) + 360) % 360;
    }

    /** Cell-preserving rotation of a continuous point (see class doc). */
    private static double[] rotate(double x, double z, int degrees) {
        return switch (normalize(degrees)) {
            case 90 -> new double[]{z, 1.0 - x};
            case 180 -> new double[]{1.0 - x, 1.0 - z};
            case 270 -> new double[]{1.0 - z, x};
            default -> new double[]{x, z};
        };
    }

    public static Vec3 toReal(Frame f, double rx, double ry, double rz) {
        double[] r = rotate(rx, rz, f.rotation());
        return new Vec3(r[0] + f.clayX(), ry, r[1] + f.clayZ());
    }

    public static Vec3 toReal(Frame f, Vec3 relative) {
        return toReal(f, relative.x, relative.y, relative.z);
    }

    public static Vec3 toRelative(Frame f, Vec3 real) {
        return toRelative(f, real.x, real.y, real.z);
    }

    public static Vec3 toRelative(Frame f, double x, double y, double z) {
        double[] r = rotate(x - f.clayX(), z - f.clayZ(), (360 - normalize(f.rotation())) % 360);
        return new Vec3(r[0], y, r[1]);
    }

    /** Real block for a room-relative block - the real {@link RoomDatabase#toRealCoord}, not a re-implementation. */
    public static BlockPos toRealBlock(Frame f, BlockPos relative) {
        RoomEntry.Pos p = new RoomEntry.Pos();
        p.x = relative.getX();
        p.y = relative.getY();
        p.z = relative.getZ();
        return RoomDatabase.toRealCoord(p, f.clayX(), f.clayZ(), f.rotation());
    }

    public static BlockPos toRelativeBlock(Frame f, BlockPos real) {
        RoomEntry.Pos p = RoomDatabase.toRelativeCoord(real, f.clayX(), f.clayZ(), f.rotation());
        return new BlockPos(p.x, p.y, p.z);
    }

    /** Real-world yaw equivalent of a stored relative yaw. Wrapped, because it is only ever a TARGET that the
     *  rotation controller turns into a delta - never written to the player. */
    public static float toRealYaw(Frame f, float relativeYaw) {
        return Mth.wrapDegrees(relativeYaw - f.rotation());
    }

    /** Relative yaw for storage. Wrapped: this is file data, not the player's running yaw. */
    public static float toRelativeYaw(Frame f, float realYaw) {
        return Mth.wrapDegrees(realYaw + f.rotation());
    }
}
