package com.killer560.hub.dungeonextras;

import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.DigAim;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ViewFreeze;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Breaker Aura's aim (ba-rotation-1102): every dig goes on a look GrimAC already has, and the look is changed only in
 * ways that cannot change his walk.
 *
 * <p>GrimAC 2.3.74's experimental RotationBreak ({@code checks/impl/breaking/RotationBreak.java}) traces the block's full
 * cube (no expansion, no occlusion) out to {@code block_interaction_range} from the position it has for him, at each of
 * his possible eye heights, along (lastYaw, pitch), (yaw, pitch) and (lastYaw, lastPitch) (lines 77-126). A dig is judged
 * at the next movement packet with that packet's look only if it arrives within 15 ms of the dig, else with the look
 * before (CheckManagerListener.handleQueuedBreaks 130-160); after one miss every dig is also judged on arrival by the look
 * before (lines 39-53, the buffer). So a dig is clean in every case when the look the server ALREADY heard strikes the
 * box ({@link DigAim#heardLookStrikes}): before any movement packet it is the current look, and after one the old look
 * is still the (lastYaw, lastPitch) candidate. The aura therefore digs only such picks, and aims for the next one in the
 * same tick (that tick's movement packet carries the new look, which the next tick's dig rides).
 *
 * <p>Walk: his movement comes from his yaw and his keys only (the sent pitch changes nothing on foot or in the air;
 * swimming, gliding and riding are excluded). So:
 * <ul>
 *   <li>a PITCH-only aim is always allowed - the body's yaw is kept on his view's yaw every tick, so his mouse steers
 *       his walk exactly as before;</li>
 *   <li>a YAW turn only while no movement key is down and he is not sprinting (no input means no input force, whatever
 *       the yaw; a sprint jump would push along it). The tick a movement key goes down, the yaw goes back to his view's
 *       before he moves.</li>
 * </ul>
 * Nothing aims while he holds use or attack (his own click must carry his own look), while another owner holds the
 * camera (Auto Routes, a solver), or while a route runs. A pick that needs a yaw turn while he walks waits.
 */
final class BreakerAim {

    private static final Logger LOGGER = ModLog.get("killer560smod-dungeonextras");

    /** True while this class has turned his body (and holds the camera). */
    private static boolean aiming;
    /** True when the body's yaw is ours (a yaw turn), not his view's. */
    private static boolean yawOurs;
    /** Set by every {@link #turn} and {@link #keep}; an aim not renewed for a whole tick is released. */
    private static boolean renewed;
    private static long pitchAims;
    private static long yawAims;
    private static long yawWaits;

    private BreakerAim() {
    }

    /** Whether a movement input that steers by yaw is (or may be) active this tick. */
    static boolean walking(Minecraft client, LocalPlayer player) {
        var o = client.options;
        return o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown()
                || player.isSprinting();
    }

    /** Whether the sent pitch is free to change (it moves nothing on foot or in the air). */
    static boolean pitchFree(LocalPlayer player) {
        return !player.isInWater() && !player.isInLava() && !player.isFallFlying() && !player.isSwimming()
                && !player.isPassenger();
    }

    private static boolean handsBusy(Minecraft client) {
        // A press queued for this tick counts too (a click shorter than a tick is never isDown): his attack / use then goes
        // with the look the next movement packet sends, so the body must be his again (Hitboxes, 1105-same-breaker-attack).
        var o = client.options;
        return o.keyUse.isDown() || o.keyAttack.isDown() || ActionGate.hisClickQueued(o.keyAttack)
                || ActionGate.hisClickQueued(o.keyUse);
    }

    private static boolean someoneElseHolds() {
        return !aiming && ViewFreeze.isHeld();
    }

    private static boolean routeRunning() {
        try {
            return com.killer560.hub.autoroutes.RouteExecutor.isRunning();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Every tick at the head of Breaker Aura's tick, active or not: keeps his walk his own. */
    static void startTick(Minecraft client, boolean active) {
        LocalPlayer player = client.player;
        boolean wasRenewed = renewed;
        renewed = false;
        if (!aiming) {
            return;
        }
        if (player == null || !active || !wasRenewed || handsBusy(client) || routeRunning() || !pitchFree(player)) {
            release(player, !active ? "aura inactive" : !wasRenewed ? "no pick wanted it" : "hands, route or water");
            return;
        }
        float viewYaw = ViewFreeze.viewYaw();
        if (Float.isNaN(viewYaw)) {
            // The lease lapsed (no hold for 400 ms): ViewFreeze already shows his body; nothing of ours is left.
            aiming = false;
            yawOurs = false;
            return;
        }
        if (!yawOurs || walking(client, player)) {
            // The body's yaw is his view's, before anything moves him this tick.
            float yaw = player.getYRot() + Mth.wrapDegrees(viewYaw - player.getYRot());
            player.setYRot(yaw);
            player.setYHeadRot(yaw);
            yawOurs = false;
        }
        ViewFreeze.hold(player.getYRot(), player.getXRot());
    }

    /** Gives the body back to his view and the camera back to him. */
    static void release(LocalPlayer player, String why) {
        if (!aiming) {
            return;
        }
        aiming = false;
        yawOurs = false;
        if (player != null) {
            float viewYaw = ViewFreeze.viewYaw();
            float viewPitch = ViewFreeze.viewPitch();
            if (!Float.isNaN(viewYaw)) {
                float yaw = player.getYRot() + Mth.wrapDegrees(viewYaw - player.getYRot());
                player.setYRot(yaw);
                player.setYHeadRot(yaw);
                player.setXRot(Mth.clamp(viewPitch, -90f, 90f));
            }
        }
        ViewFreeze.release();
        LOGGER.debug("[BreakerAura] aim released: {}", why);
    }

    /** True when a dig on {@code pos} sent now is judged by GrimAC on a look that strikes it. */
    static boolean digReady(LocalPlayer player, BlockPos pos, boolean anyEye) {
        return anyEye ? DigAim.heardLookStrikesAnyEye(player, pos) : DigAim.heardLookStrikes(player, pos);
    }

    /**
     * Burst (burstmax-1108): turns the body so this tick's movement packet carries the look that strikes the MOST of
     * {@code targets} from where he will be (pitch only while he walks, else yaw too), so next tick's burst digs them all
     * on a look the server heard. @return what was done, or null when none can be aimed without steering his walk.
     */
    static String aimForMost(Minecraft client, LocalPlayer player, List<BlockPos> targets) {
        if (targets.isEmpty() || handsBusy(client) || routeRunning() || someoneElseHolds() || !pitchFree(player)) {
            return null;
        }
        Vec3 feet = player.position().add(player.getDeltaMovement());
        Vec3 now = player.position();
        double eyeH = player.getEyeHeight();
        float yaw = player.getYRot();
        if (aiming && !yawOurs) {
            float viewYaw = ViewFreeze.viewYaw();
            if (!Float.isNaN(viewYaw)) {
                yaw = player.getYRot() + Mth.wrapDegrees(viewYaw - player.getYRot());
            }
        }
        // Pitch only: the middle of the widest run of pitches at the best count.
        int best = 0;
        float lo = 0f;
        float hi = 0f;
        for (float p = -90f; p <= 90f; p += 0.5f) {
            int n = struck(feet, now, yaw, p, targets);
            if (n > best) {
                best = n;
                lo = p;
                hi = p;
            } else if (n == best && n > 0 && hi == p - 0.5f) {
                hi = p;
            }
        }
        if (best > 0) {
            float pitch = (lo + hi) / 2f;
            turn(player, yaw, pitch, false);
            pitchAims++;
            return String.format(java.util.Locale.ROOT, "pitch %.1f (%d of %d)", pitch, best, targets.size());
        }
        if (walking(client, player)) {
            yawWaits++;
            return null;
        }
        // Standing: try the look at each target's center, keep the one striking most.
        Vec3 eye = feet.add(0, eyeH, 0);
        float bestYaw = 0f;
        float bestPitch = 0f;
        for (BlockPos t : targets) {
            Vec3 to = Vec3.atCenterOf(t).subtract(eye);
            float wantPitch = (float) Mth.clamp(Math.toDegrees(-Math.atan2(to.y, Math.hypot(to.x, to.z))), -90, 90);
            float runYaw = player.getYRot() + Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-to.x, to.z)) - player.getYRot());
            int n = struck(feet, feet, runYaw, wantPitch, targets);
            if (n > best) {
                best = n;
                bestYaw = runYaw;
                bestPitch = wantPitch;
            }
        }
        if (best == 0) {
            return null;
        }
        turn(player, bestYaw, bestPitch, true);
        yawAims++;
        return String.format(java.util.Locale.ROOT, "yaw %.1f/%.1f (%d of %d)", bestYaw, bestPitch, best, targets.size());
    }

    private static int struck(Vec3 feet, Vec3 alsoFeet, float yaw, float pitch, List<BlockPos> targets) {
        int n = 0;
        for (BlockPos t : targets) {
            if (DigAim.strikesAnyEye(feet, yaw, pitch, t) && (alsoFeet == feet || DigAim.strikesAnyEye(alsoFeet, yaw, pitch, t))) {
                n++;
            }
        }
        return n;
    }

    /**
     * Turns the body (camera held) so this tick's movement packet carries a look that strikes {@code pos} from where he
     * will be: pitch-only when that reaches it, else a yaw turn when he is not walking. @return what was done, or null
     * when the pick cannot be aimed now without changing his walk.
     */
    static String aimFor(Minecraft client, LocalPlayer player, BlockPos pos) {
        if (handsBusy(client) || routeRunning() || someoneElseHolds() || !pitchFree(player)) {
            return null;
        }
        Vec3 feet = player.position().add(player.getDeltaMovement());
        double eyeH = player.getEyeHeight();
        float yaw = player.getYRot();
        if (aiming && !yawOurs) {
            float viewYaw = ViewFreeze.viewYaw();
            if (!Float.isNaN(viewYaw)) {
                yaw = player.getYRot() + Mth.wrapDegrees(viewYaw - player.getYRot());
            }
        }
        Float pitch = pitchOnly(feet, player.position(), eyeH, yaw, pos);
        if (pitch != null) {
            turn(player, yaw, pitch, false);
            pitchAims++;
            return "pitch " + String.format(java.util.Locale.ROOT, "%.1f", pitch);
        }
        if (walking(client, player)) {
            yawWaits++;
            return null;
        }
        Vec3 eye = feet.add(0, eyeH, 0);
        Vec3 to = Vec3.atCenterOf(pos).subtract(eye);
        float wantYaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
        float wantPitch = (float) Mth.clamp(Math.toDegrees(-Math.atan2(to.y, Math.hypot(to.x, to.z))), -90, 90);
        float runYaw = player.getYRot() + Mth.wrapDegrees(wantYaw - player.getYRot());
        if (!DigAim.strikes(feet, eyeH, runYaw, wantPitch, pos)) {
            return null;
        }
        turn(player, runYaw, wantPitch, true);
        yawAims++;
        return "yaw " + String.format(java.util.Locale.ROOT, "%.1f/%.1f", runYaw, wantPitch);
    }

    /** The pitch, kept on {@code yaw}, that strikes {@code pos} from both {@code feet} and {@code alsoFeet} - the middle of
     *  the striking range, so a small error in where he ends up still strikes - or null when none does. */
    private static Float pitchOnly(Vec3 feet, Vec3 alsoFeet, double eyeH, float yaw, BlockPos pos) {
        float lo = Float.NaN;
        float hi = Float.NaN;
        for (float p = -90f; p <= 90f; p += 0.5f) {
            if (DigAim.strikes(feet, eyeH, yaw, p, pos) && DigAim.strikes(alsoFeet, eyeH, yaw, p, pos)) {
                if (Float.isNaN(lo)) {
                    lo = p;
                }
                hi = p;
            } else if (!Float.isNaN(lo)) {
                break;
            }
        }
        return Float.isNaN(lo) ? null : (lo + hi) / 2f;
    }

    /** Keeps the current aim for another tick (its pick is dug next tick on the look already sent). */
    static void keep() {
        if (aiming) {
            renewed = true;
        }
    }

    private static void turn(LocalPlayer player, float yaw, float pitch, boolean ownYaw) {
        ViewFreeze.hold(player.getYRot(), player.getXRot());
        aiming = true;
        renewed = true;
        yawOurs = ownYaw;
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(Mth.clamp(pitch, -90f, 90f));
    }

    static boolean aiming() {
        return aiming;
    }

    /** Pitch-only aims, yaw turns, and picks that waited because a yaw turn would have steered his walk (only go up). */
    static long pitchAims() {
        return pitchAims;
    }

    static long yawAims() {
        return yawAims;
    }

    static long yawWaits() {
        return yawWaits;
    }
}
