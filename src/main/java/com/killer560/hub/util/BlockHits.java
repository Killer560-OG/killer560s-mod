package com.killer560.hub.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Builds the {@link BlockHitResult} an automated click should send: a point on the block's real SURFACE, with
 * the face that point actually lies on.
 *
 * <p>This exists because of a measured flag. Several of this mod's auras used to send
 * {@code new BlockHitResult(Vec3.atCenterOf(pos), Direction.EAST, pos, false)} - the block's centre, which is
 * a point <i>inside</i> the block, paired with a hardcoded east face regardless of where the player was
 * standing. No real click can produce that: a click comes from a raycast, so its position is always on a
 * surface and its face is always the one the ray struck.
 *
 * <p>Measured 2026-09-28 against a live GrimAC, Secret Aura clicking a lever at 4.50 blocks - inside vanilla's
 * own 4.5 limit, and accepted by the server - still drew a {@code PositionPlace} violation every time. Breaker
 * Aura, which has always clipped its hit from the eye through the block's shape, measured clean in the same
 * harness across hundreds of interactions. The difference was the hit itself, not the distance and not the
 * timing.
 *
 * <p>The clip is deliberately generous at the far end ({@code + 1.5}) so a shape whose centre sits behind a
 * partial outline - a lever on a wall, a skull, a chest lid - is still struck rather than missed by a ray that
 * stopped a hair short.
 */
public final class BlockHits {

    private BlockHits() {
    }

    /**
     * A surface hit on {@code pos} as seen from {@code eye}.
     *
     * @return the clipped hit, or null when the block has no outline to strike - callers should treat null as
     *         "do not click this", because a synthetic fallback is the very thing this class exists to avoid
     */
    public static BlockHitResult surface(BlockGetter level, BlockPos pos, Vec3 eye) {
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        if (shape.isEmpty()) {
            return null;
        }
        Vec3 centre = shape.bounds().getCenter().add(pos.getX(), pos.getY(), pos.getZ());
        Vec3 dir = centre.subtract(eye);
        if (dir.lengthSqr() < 1.0E-9) {
            return null; // standing inside it; there is no direction to come from
        }
        Vec3 end = eye.add(dir.normalize().scale(eye.distanceTo(centre) + 1.5));
        return shape.clip(eye, end, pos);
    }

    /**
     * Squared distance from {@code eye} to the NEAREST POINT of a block's cell - the measure a server uses when
     * it decides whether an interaction is in range.
     *
     * <p>Not the distance to the centre, which is what the auras used to compare against their range setting and
     * which reads up to half a block further (0.87 at a corner). That difference is not academic: with the range
     * capped at vanilla's own 4.5, measuring to the centre put the effective reach nearer 4.0 and silently
     * dropped clicks the server would have accepted. Breaker Aura hit the same thing from the other direction in
     * September and was fixed the same way.
     *
     * <p>The cell rather than the collision shape, deliberately: a lever's shape is a few pixels and measuring
     * to it would make a lever on a far wall unreachable at a range that comfortably reaches a chest beside it,
     * which is not how the server decides either.
     */
    public static double boxDistanceSq(Vec3 eye, BlockPos pos) {
        double dx = Math.max(0, Math.max(pos.getX() - eye.x, eye.x - (pos.getX() + 1)));
        double dy = Math.max(0, Math.max(pos.getY() - eye.y, eye.y - (pos.getY() + 1)));
        double dz = Math.max(0, Math.max(pos.getZ() - eye.z, eye.z - (pos.getZ() + 1)));
        return dx * dx + dy * dy + dz * dz;
    }

    /** Squared distance from {@code eye} to the nearest point of an arbitrary box - the entity equivalent of
     *  {@link #boxDistanceSq(Vec3, BlockPos)}, and the measure a server applies to an entity interaction. */
    public static double boxDistanceSq(Vec3 eye, net.minecraft.world.phys.AABB box) {
        double dx = Math.max(0, Math.max(box.minX - eye.x, eye.x - box.maxX));
        double dy = Math.max(0, Math.max(box.minY - eye.y, eye.y - box.maxY));
        double dz = Math.max(0, Math.max(box.minZ - eye.z, eye.z - box.maxZ));
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Same, but falling back to the centre with the nearest-facing side when the ray misses the outline.
     *
     * <p>For callers that must click <i>something</i> rather than skip a turn. Still better than a hardcoded
     * face - the side is at least the one facing the player - but it is the weaker option and the fallback is
     * the shape that got flagged, so prefer {@link #surface} and skip the tick when it returns null.
     */
    public static BlockHitResult surfaceOrCentre(BlockGetter level, BlockPos pos, Vec3 eye) {
        BlockHitResult hit = surface(level, pos, eye);
        if (hit != null) {
            return hit;
        }
        Vec3 centre = Vec3.atCenterOf(pos);
        return new BlockHitResult(centre, Direction.getApproximateNearest(eye.subtract(centre)), pos, false);
    }
}
