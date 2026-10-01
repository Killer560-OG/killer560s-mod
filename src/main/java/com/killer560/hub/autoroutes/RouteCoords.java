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
        // 90 and 270 are swapped relative to the obvious reading, to match RoomDatabase.toRealCoord's
        // negated angle (fixed 2026-09-20: it had been rotating room-relative coords the wrong way, putting
        // every point a room-width outside the room at those two rotations). These two must agree - this
        // file keeps its own sub-block rotation but delegates whole blocks to RoomDatabase.
        return switch (normalize(degrees)) {
            case 90 -> new double[]{1.0 - z, x};
            case 180 -> new double[]{1.0 - x, 1.0 - z};
            case 270 -> new double[]{z, 1.0 - x};
            default -> new double[]{x, z};
        };
    }

    /**
     * How far a stored route's heights have to move to land on the floor the sim actually built.
     *
     * <p>None of these four transforms touched y, which is correct on Hypixel - every dungeon floor is at the
     * same height there, so a relative y IS a world y. The sim shifts the whole map by one offset per build
     * ({@code SimAltitude}: normally the lowest block one above the void, or top-aligned when the floor holds
     * Higher Blaze), and that offset is DIFFERENT for different floors. So without this:
     *
     * <ul>
     *   <li>a route recorded on Hypixel replayed in the sim aims at Hypixel's height - under the floor or in
     *       the air, depending which way that build was shifted;</li>
     *   <li>a route recorded in the sim stores a shifted height as though it were relative, so it is wrong on
     *       Hypixel AND wrong in the sim's own next build, whose offset is not the same number.</li>
     * </ul>
     *
     * <p>Carrying it in both directions makes a stored route mean the same thing wherever it was recorded.
     * Zero outside the sim, so a real run is unchanged - the same
     * {@link com.killer560.hub.livemap.DungeonLayout#simYOffset()} {@code PuzzleCoords} and the waypoints use.
     */
    private static int shift() {
        return com.killer560.hub.livemap.DungeonLayout.simYOffset();
    }

    public static Vec3 toReal(Frame f, double rx, double ry, double rz) {
        double[] r = rotate(rx, rz, f.rotation());
        return new Vec3(r[0] + f.clayX(), ry + shift(), r[1] + f.clayZ());
    }

    public static Vec3 toReal(Frame f, Vec3 relative) {
        return toReal(f, relative.x, relative.y, relative.z);
    }

    public static Vec3 toRelative(Frame f, Vec3 real) {
        return toRelative(f, real.x, real.y, real.z);
    }

    public static Vec3 toRelative(Frame f, double x, double y, double z) {
        // NEGATED, because this is the inverse of toReal and rotate() is not its own inverse.
        //
        // Wrong until 2026-09-28: it passed +rotation, which happens to be right at 0 and 180 (identity, and
        // a 180 that undoes itself) and is WRONG at 90 and 270, where rotate swaps the axes - the inverse of
        // the 90 case (x, z) -> (1 - z, x) is the 270 case, not the 90 case again. So every route recorded in
        // a room the generator had turned a quarter turn came out mirrored around the clay corner, which is
        // half of all rooms, and the error is invisible in a square room recorded near its middle.
        double[] r = rotate(x - f.clayX(), z - f.clayZ(), -f.rotation());
        return new Vec3(r[0], y - shift(), r[1]);
    }

    /** Real block for a room-relative block - the real {@link RoomDatabase#toRealCoord}, not a re-implementation. */
    public static BlockPos toRealBlock(Frame f, BlockPos relative) {
        RoomEntry.Pos p = new RoomEntry.Pos();
        p.x = relative.getX();
        p.y = relative.getY() + shift();
        p.z = relative.getZ();
        return RoomDatabase.toRealCoord(p, f.clayX(), f.clayZ(), f.rotation());
    }

    public static BlockPos toRelativeBlock(Frame f, BlockPos real) {
        RoomEntry.Pos p = RoomDatabase.toRelativeCoord(real, f.clayX(), f.clayZ(), f.rotation());
        return new BlockPos(p.x, p.y - shift(), p.z);
    }

    /** Real-world yaw equivalent of a stored relative yaw. Wrapped, because it is only ever a TARGET that the
     *  rotation controller turns into a delta - never written to the player. */
    public static float toRealYaw(Frame f, float relativeYaw) {
        return Mth.wrapDegrees(relativeYaw + f.rotation());
    }

    /** Relative yaw for storage. Wrapped: this is file data, not the player's running yaw. */
    public static float toRelativeYaw(Frame f, float realYaw) {
        return Mth.wrapDegrees(realYaw - f.rotation());
    }
}
