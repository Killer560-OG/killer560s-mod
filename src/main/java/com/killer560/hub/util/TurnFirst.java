package com.killer560.hub.util;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Turn-then-act for an aura whose target the reported look does not reach: Terminal Aura's Turn To Terminal
 * (2026-10-07), made shareable.
 * <p>
 * Why: an interaction aimed at something the rotation in the movement packets does not point at is not something a
 * vanilla client sends. Measured against GrimAC (testkit, 2026-10-07): Secret Aura clicking a lever directly behind the
 * player drew {@code RotationPlace post-flying} (419); a terminal stand behind drew {@code Hitboxes type=armor_stand}
 * (415, 418). With the body turned the same clicks are clean.
 * <p>
 * How: {@link #ready} answers whether the look the server was last told about (the body's, never the camera's) already
 * reaches the target. When it does, act now. When it does not, the BODY is turned to the target with {@link BodyAim} -
 * camera held by {@link ViewFreeze}, yaw a wrapped delta on the running value, pitch within +-90 - and the owner acts on
 * a later tick, once that tick's movement packet has reported the turn; the body is given back to the held view the
 * tick after it acted. One instance per owner. Nothing here sends a packet or touches position or velocity.
 */
public final class TurnFirst {

    /** Ticks a turn waits for its owner to act before it is given up (and the body given back). */
    private static final int MAX_WAIT = 5;

    private final BodyAim aim;
    private boolean pending;
    private int pendingAge;

    public TurnFirst() {
        this(() -> { });
    }

    /** {@code onWrite}: run after every write to his rotation (an owner with a camera-turn detector rebases it). */
    public TurnFirst(Runnable onWrite) {
        this.aim = new BodyAim(onWrite);
    }

    /**
     * Every client tick of the owner, FIRST, whether it is enabled or not: keeps the body held while a turned-for action
     * is still to go out, and gives it back the tick after.
     */
    public void tick(LocalPlayer player) {
        if (pending && ++pendingAge > MAX_WAIT) {
            pending = false;
        }
        aim.tick(player, pending);
    }

    /** Whether a turn is waiting for its action (the body is turned away from the camera). */
    public boolean isTurned() {
        return aim.isHeld();
    }

    /**
     * A block: true when its outline is on the reported look within {@code reach} - act now. Otherwise turns the body to
     * {@code aimPoint} (a point on the block, e.g. {@link BlockHits#surface}'s) and returns false: ask again next tick.
     */
    public boolean readyBlock(LocalPlayer player, BlockGetter level, BlockPos pos, Vec3 aimPoint, double reach) {
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(look(player).scale(reach));
        boolean hits = shape.isEmpty() ? new AABB(pos).clip(eye, end).isPresent() : shape.clip(eye, end, pos) != null;
        return settle(player, hits, aimPoint);
    }

    /** As {@link #readyBlock}, for an entity (or any) box. */
    public boolean readyBox(LocalPlayer player, AABB box, Vec3 aimPoint, double reach) {
        Vec3 eye = player.getEyePosition();
        boolean hits = box.contains(eye) || box.clip(eye, eye.add(look(player).scale(reach))).isPresent();
        return settle(player, hits, aimPoint);
    }

    private boolean settle(LocalPlayer player, boolean hits, Vec3 aimPoint) {
        if (hits) {
            pending = false; // acting now; the body (if turned) is still held this tick and given back the next
            return true;
        }
        Vec3 d = aimPoint.subtract(player.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        aim.turnTo(player, yaw, Mth.clamp(pitch, -90f, 90f));
        pending = true;
        pendingAge = 0;
        return false;
    }

    /** The look the movement packets report: the body's rotation, NOT getViewVector (which a held camera answers). */
    public static Vec3 look(LocalPlayer player) {
        float yaw = player.getYRot() * Mth.DEG_TO_RAD;
        float pitch = player.getXRot() * Mth.DEG_TO_RAD;
        float cp = Mth.cos(pitch);
        return new Vec3(-Mth.sin(yaw) * cp, -Mth.sin(pitch), Mth.cos(yaw) * cp);
    }
}
