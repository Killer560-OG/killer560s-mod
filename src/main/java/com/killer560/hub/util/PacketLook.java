package com.killer560.hub.util;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.BiPredicate;

/**
 * A look that goes out in ONE movement packet and nowhere else (ap3auralook-1116): the aura's aim rides the head of this
 * tick's {@code sendPosition} (Ap3RotationSendMixin) and the live yaw and pitch are put back at its RETURN, so the camera,
 * AP3's walk and every key stay as they were. The click goes at the next tick's START, ahead of that tick's packet, so
 * GrimAC judges it by the look before (RotationPlace and Reach/Hitboxes try the last packet's look too).
 * <p>The owner's rule (2026-11-16): "My auras can never affect my movement. If I am running and something is in range it
 * auras without changing anything." On a tick with movement keys or jump held an aura never changes the yaw (GrimAC
 * simulates the tick's input along the packet's yaw: any other yaw is Simulation, testkit 1116-nolook-probe) and never
 * waits for a key-free tick: {@link #whileMoving} picks, in order, the look already sent, a PITCH-only change along the
 * walk's own yaw (clean on default and experimental Grim, 1116-movingclick-probe), or a click with no look at all (draws
 * RotationPlace on a lever, Hitboxes on a stand: his accepted exception, "No switch."). A tick with no input takes the
 * full aim (coasting or airborne is fine for levers; a stand needs a full aim on the ground, {@link #standSafe}; pitch-only is clean airborne too).
 */
public final class PacketLook {
    private PacketLook() { }

    /** What a clicking aura does on a tick he gives movement input. */
    public enum Moving { CLICK, WAIT, NO_LOOK }

    private static float wantYaw = Float.NaN;
    private static float wantPitch;
    private static Vec3 wantAim; // pitch-only: the point the walk's yaw is pitched at, worked out from the sent eye
    private static int wantTick;
    private static float sentYaw = Float.NaN;
    private static float sentPitch;
    private static Vec3 sentEye;
    private static int sentTick;
    private static boolean sentGround;
    private static boolean sentPitchOnly;

    /** Asks for this look in this tick's movement packet (dropped unless the tick is one with no input). */
    public static void request(LocalPlayer player, float yaw, float pitch) {
        wantYaw = yaw;
        wantPitch = Mth.clamp(pitch, -90f, 90f);
        wantAim = null;
        wantTick = player.tickCount;
    }

    /** True when last tick's packet carried a requested look whose ray from that packet's eye meets the target. */
    public static boolean struck(LocalPlayer player, BiPredicate<Vec3, Vec3> ray, double reach) {
        if (Float.isNaN(sentYaw) || sentTick != player.tickCount || player.getEyePosition().distanceToSqr(sentEye) > 1.0e-6) {
            return false;
        }
        return ray.test(sentEye, sentEye.add(Vec3.directionFromRotation(sentPitch, sentYaw).scale(reach)));
    }

    /** Whether the aimed packet {@link #struck} judged suits a stand: a full aim on the ground, or a pitch-only aim. */
    public static boolean standSafe() {
        return sentGround || sentPitchOnly;
    }

    /** Whether he gives movement input this tick (a movement key or jump): an aura then leaves his yaw alone. */
    public static boolean keysHeld(LocalPlayer player) {
        Input k = player.input.keyPresses;
        return k.forward() || k.backward() || k.left() || k.right() || k.jump();
    }

    /**
     * Keys held: CLICK when last tick's packet carried a pitch-only aim that meets the target (clean on the ground and
     * airborne, levers and stands), WAIT when this tick's packet can carry one (asked for here), else NO_LOOK: click with
     * the look he has. The caller has already clicked when the look the server heard meets the target.
     */
    public static Moving whileMoving(LocalPlayer player, Vec3 aim, BiPredicate<Vec3, Vec3> ray, double reach) {
        return whileMoving(player, aim, ray, reach, null);
    }

    /**
     * As {@link #whileMoving(LocalPlayer, Vec3, BiPredicate, double)}; when the pitch at {@code aim} misses and a
     * {@code target} box is given, every pitch along the walk's yaw that crosses that box is tried (leverplace0-1117: a
     * lever whose outline point lies off the walk's plane can still have its full cube, which is all GrimAC RotationPlace
     * judges, on it), and the middle of the ones the ray accepts is aimed at.
     */
    public static Moving whileMoving(LocalPlayer player, Vec3 aim, BiPredicate<Vec3, Vec3> ray, double reach, AABB target) {
        return whileMoving(player, aim, ray, reach, target, null);
    }

    /** As above; {@code eyeAhead} (the eye of the tick AFTER this packet) is what the ray is judged from when given. */
    public static Moving whileMoving(LocalPlayer player, Vec3 aim, BiPredicate<Vec3, Vec3> ray, double reach, AABB target,
                                     Vec3 eyeAhead) {
        if (eyeAhead == null && sentPitchOnly && sentTick == player.tickCount) {
            return struck(player, ray, reach) ? Moving.CLICK : Moving.NO_LOOK;
        }
        Vec3 v = player.getDeltaMovement(); // judged from the eye this tick's packet will carry
        Vec3 eye = eyeAhead != null ? eyeAhead : player.getEyePosition().add(v.x, player.onGround() ? 0.0 : v.y, v.z);
        float yaw = player.getYRot();
        float pitch = pitchAlongYaw(eye, yaw, aim);
        if (Float.isNaN(pitch) || !ray.test(eye, eye.add(Vec3.directionFromRotation(pitch, yaw).scale(reach)))) {
            pitch = target == null ? Float.NaN : pitchThrough(eye, yaw, target, ray, reach);
            if (Float.isNaN(pitch)) {
                return Moving.NO_LOOK;
            }
            Vec3 dir = Vec3.directionFromRotation(pitch, yaw);
            Vec3 far = eye.add(dir.scale(reach));
            Vec3 in = target.clip(eye, far).orElse(eye);
            Vec3 out = target.clip(far, eye).orElse(far);
            aim = in.add(out).scale(0.5); // the middle of the ray's run through the box: apply() re-aims at it from the sent eye
        }
        wantYaw = Float.NaN;
        wantAim = aim;
        wantTick = player.tickCount;
        return Moving.WAIT;
    }

    /** The middle of the pitches (0.25 degree steps, over the box's own pitch span) whose ray along {@code yaw} the
     *  predicate accepts, or NaN. */
    private static float pitchThrough(Vec3 eye, float yaw, AABB box, BiPredicate<Vec3, Vec3> ray, double reach) {
        float lo = 90f;
        float hi = -90f;
        for (int i = 0; i < 8; i++) {
            float p = pitchAlongYaw(eye, yaw, new Vec3((i & 1) == 0 ? box.minX : box.maxX, (i & 2) == 0 ? box.minY : box.maxY,
                    (i & 4) == 0 ? box.minZ : box.maxZ));
            if (Float.isNaN(p)) {
                lo = -90f;
                hi = 90f; // a corner level with or behind the eye: try every pitch
                break;
            }
            lo = Math.min(lo, p);
            hi = Math.max(hi, p);
        }
        float first = Float.NaN;
        float last = Float.NaN;
        for (float p = lo; p <= hi; p += 0.25f) {
            if (ray.test(eye, eye.add(Vec3.directionFromRotation(p, yaw).scale(reach)))) {
                if (Float.isNaN(first)) first = p;
                last = p;
            }
        }
        return Float.isNaN(first) ? Float.NaN : (first + last) / 2f;
    }

    /** The pitch whose ray along {@code yaw} passes over or under {@code aim}; NaN when the point is not ahead. */
    private static float pitchAlongYaw(Vec3 eye, float yaw, Vec3 aim) {
        double r = Math.toRadians(yaw);
        double ahead = (aim.x - eye.x) * -Math.sin(r) + (aim.z - eye.z) * Math.cos(r);
        return ahead <= 0.05 ? Float.NaN
                : Mth.clamp((float) -Math.toDegrees(Math.atan2(aim.y - eye.y, ahead)), -90f, 90f);
    }

    /**
     * At the head of {@code sendPosition}, after AP3's own swaps: writes the requested look for this send - a full aim on
     * a tick with no input, a pitch-only aim on any tick. Returns true when it wrote (the mixin puts the live look back).
     */
    public static boolean apply(LocalPlayer self, float yRotLast) {
        float yaw = wantYaw;
        Vec3 aim = wantAim;
        wantYaw = Float.NaN;
        wantAim = null;
        if (Float.isNaN(yaw) && aim == null || self.tickCount - wantTick > 1 || TurnFirst.bodyOwned()) { // AP3 aiming
            return false;
        }
        String sent = HotbarSelect.orderConflict(); // a use_item ahead of this packet carried the live look: BadPacketsJ
        if (sent != null && sent.startsWith("use_item") || aim == null && keysHeld(self)) {
            return false;
        }
        float pitch = aim == null ? wantPitch : pitchAlongYaw(self.getEyePosition(), self.getYRot(), aim);
        if (Float.isNaN(pitch)) {
            return false;
        }
        if (aim == null) {
            yaw = yRotLast + Mth.wrapDegrees(yaw - yRotLast); // a running value from the yaw the server last heard
            self.setYRot(yaw);
        } else {
            yaw = self.getYRot(); // the walk's own yaw, exactly
        }
        self.setXRot(pitch);
        sentYaw = yaw;
        sentPitch = pitch;
        sentEye = self.getEyePosition();
        sentTick = self.tickCount;
        sentGround = self.onGround();
        sentPitchOnly = aim != null;
        return true;
    }
}
