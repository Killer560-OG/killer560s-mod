package com.killer560.hub.dungeonextras;

import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.DigAim;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.TurnFirst;
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
 *   <li>any YAW turn while no movement key is down and he is not sprinting (no input means no input force, whatever
 *       the yaw; a sprint jump would push along it). The tick a movement key goes down, the yaw goes back to his view's
 *       before he moves;</li>
 *   <li>while he walks, a yaw k x 45 degrees off his view's with his keys turned k steps back (balate-1109,
 *       {@code walkYaws}): the same push along the same world direction, so his path is exactly what it was; under an
 *       AP3 held walk the walk's own exact yaws.</li>
 * </ul>
 * Nothing aims while he holds use or attack (his own click must carry his own look), while another owner holds the
 * camera (Auto Routes, a solver), while AP3 owns the body (an align, an aimed node: TurnFirst.bodyOwned), or while a route
 * runs. A pick no allowed look strikes waits.
 */
public final class BreakerAim {

    private static final Logger LOGGER = ModLog.get("killer560smod-dungeonextras");

    /** True while this class has turned his body (and holds the camera). */
    private static boolean aiming;
    /** True when the body's yaw is ours (a yaw turn), not his view's. */
    private static boolean yawOurs;
    /** Set by every {@link #turn} and {@link #keep}; an aim not renewed for a whole tick is released. */
    private static boolean renewed;
    /** Steps of 45 degrees the body's yaw is on from his view's this tick, with his keys turned to match (0 = none). */
    private static int keySteps;
    /** The last walkYaws answer was his own keys (turned by the input mixin), not an AP3 held walk's yaws. */
    private static boolean ownKeys;
    private static long pitchAims;
    private static long yawAims;
    private static long yawWaits;
    private static long offWalkAims;
    /** The last walkYaws answer allows a look off his walk for a pick in his way (not a sprint-jump take-off, not AP3
     *  driving without a held walk's yaws). */
    private static boolean freeLookOk;
    private static String lastWait = "";

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
        keySteps = 0;
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
        keySteps = 0;
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
    static String aimForMost(Minecraft client, LocalPlayer player, List<BlockPos> targets, boolean urgent) {
        return aim(client, player, targets, true, urgent);
    }

    /**
     * Turns the body (camera held) so this tick's movement packet carries the look that strikes the most of {@code targets}
     * (on GrimAC's three eye rays when {@code anyEye}, else on his own eye) from where he will be. While he walks only a
     * yaw that leaves his walk exactly as it was (walkYaws) is used, with any pitch; standing, any look. When none strikes
     * and the pick is {@code urgent} (in his way, he is not getting anywhere, or it waited too long: BreakerAuraFeature),
     * the look goes straight at it as before balate-1109, his keys turned the nearest 45-degree steps back (balatefix-1109).
     */
    private static String aim(Minecraft client, LocalPlayer player, List<BlockPos> targets, boolean anyEye, boolean urgent) {
        if (targets.isEmpty() || handsBusy(client) || routeRunning() || someoneElseHolds() || !pitchFree(player)
                || TurnFirst.bodyOwned()) {
            lastWait = targets.isEmpty() ? "" : handsBusy(client) ? "his hands are busy" : routeRunning() ? "a route runs"
                    : someoneElseHolds() ? "another feature holds the camera" : !pitchFree(player) ? "in water or riding"
                    : "AP3 owns the body";
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
        float[] yaws = walkYaws(client, player, yaw);
        // Each yaw his walk allows, his own first: the middle of the widest run of pitches at the best count there.
        int best = 0;
        float bestYaw = yaw;
        float bestPitch = 0f;
        for (float y : yaws == null ? new float[]{yaw} : yaws) {
            int top = 0;
            float lo = 0f;
            float hi = 0f;
            for (float p = -90f; p <= 90f; p += 0.5f) {
                int n = struck(feet, now, eyeH, y, p, targets, anyEye);
                if (n > top) {
                    top = n;
                    lo = p;
                    hi = p;
                } else if (n == top && n > 0 && hi == p - 0.5f) {
                    hi = p;
                }
            }
            if (top > best) {
                best = top;
                bestYaw = y;
                bestPitch = (lo + hi) / 2f;
            }
        }
        if (best > 0) {
            boolean ownYaw = bestYaw != yaw;
            turn(player, bestYaw, bestPitch, ownYaw);
            keySteps = ownYaw && ownKeys ? Math.round((bestYaw - yaw) / 45f) : 0;
            if (ownYaw) {
                yawAims++;
            } else {
                pitchAims++;
            }
            return String.format(java.util.Locale.ROOT, ownYaw ? "yaw %.1f/%.1f, walk kept (%d of %d)" : "pitch %2$.1f (%3$d of %4$d)",
                    bestYaw, bestPitch, best, targets.size());
        }
        if (yaws != null && !(urgent && freeLookOk)) {
            yawWaits++;
            lastWait = String.format(java.util.Locale.ROOT, "first %s, feet %.2f %.2f %.2f, view yaw %.1f, yaws tried %s, %s",
                    targets.get(0).toShortString(), now.x, now.y, now.z, yaw, java.util.Arrays.toString(yaws),
                    !urgent ? "not in his way yet" : "free look barred (sprint-jump take-off or AP3 drives)");
            return null;
        }
        // Standing, or a pick in his way no walk-keeping look strikes: a look near each target's center, the one striking
        // most; while he walks, of those the yaw nearest a whole 45-degree step off his own, so his keys turned back by whole
        // steps bend his walk the least on that one tick.
        Vec3 eye = feet.add(0, eyeH, 0);
        float bestErr = 99f;
        for (BlockPos t : targets) {
            Vec3 to = Vec3.atCenterOf(t).subtract(eye);
            float wantPitch = (float) Mth.clamp(Math.toDegrees(-Math.atan2(to.y, Math.hypot(to.x, to.z))), -90, 90);
            float runYaw = player.getYRot() + Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-to.x, to.z)) - player.getYRot());
            for (int d = 0; d <= (yaws == null ? 0 : 60); d++) {
                float y = runYaw + (d % 2 == 0 ? d / 2 : -(d + 1) / 2);
                float off = Mth.wrapDegrees(y - yaw);
                float err = yaws == null ? 0f : Math.abs(off - 45f * Math.round(off / 45f));
                if (err >= bestErr && best == targets.size()) {
                    continue;
                }
                int n = struck(feet, feet, eyeH, y, wantPitch, targets, anyEye);
                if (n > best || (n == best && n > 0 && err < bestErr)) {
                    best = n;
                    bestErr = err;
                    bestYaw = y;
                    bestPitch = wantPitch;
                }
            }
        }
        if (best == 0) {
            lastWait = String.format(java.util.Locale.ROOT, "first %s, feet %.2f %.2f %.2f: no look at a center strikes it",
                    targets.get(0).toShortString(), now.x, now.y, now.z);
            return null;
        }
        turn(player, bestYaw, bestPitch, true);
        yawAims++;
        if (yaws != null) {
            // Off his walk for one tick: his own keys turned the nearest whole steps back (off by at most 22.5 degrees);
            // an AP3 held walk re-picks its keys for this yaw itself (Ap3Executor.beforeInputInstall).
            keySteps = ownKeys ? Math.round((bestYaw - yaw) / 45f) : 0;
            offWalkAims++;
            return String.format(java.util.Locale.ROOT, "yaw %.1f/%.1f off his walk, in his way (%d of %d)", bestYaw, bestPitch,
                    best, targets.size());
        }
        return String.format(java.util.Locale.ROOT, "yaw %.1f/%.1f (%d of %d)", bestYaw, bestPitch, best, targets.size());
    }

    /** How many of {@code targets} the look strikes from both {@code feet} and {@code alsoFeet} (where he is now and where
     *  he will be). A target more than 75 degrees off the yaw and not under or over him is skipped without a ray. */
    private static int struck(Vec3 feet, Vec3 alsoFeet, double eyeH, float yaw, float pitch, List<BlockPos> targets,
                              boolean anyEye) {
        int n = 0;
        for (BlockPos t : targets) {
            double dx = t.getX() + 0.5 - feet.x;
            double dz = t.getZ() + 0.5 - feet.z;
            if (dx * dx + dz * dz > 2.25
                    && Math.abs(Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)) - yaw)) > 75f) {
                continue;
            }
            if (strikes(feet, eyeH, yaw, pitch, t, anyEye)
                    && (alsoFeet == feet || strikes(alsoFeet, eyeH, yaw, pitch, t, anyEye))) {
                n++;
            }
        }
        return n;
    }

    private static boolean strikes(Vec3 feet, double eyeH, float yaw, float pitch, BlockPos t, boolean anyEye) {
        return anyEye ? DigAim.strikesAnyEye(feet, yaw, pitch, t) : DigAim.strikes(feet, eyeH, yaw, pitch, t);
    }

    /**
     * The yaws the body may take this tick without changing his walk, his own first; null when he is not walking (any
     * yaw). balate-1109: picks off his look waited until he stopped (live S1, 2026-11-09: a wall reached 3-5 ticks late,
     * one pick passed and never broke). His movement is his eight-way keys turned by the body's yaw, so the same keys
     * turned k x 45 degrees, at a yaw k x 45 degrees the other way, give exactly the same push ({@code mixin/
     * BreakerAuraInputMixin}): only combinations that keep W if his have it (a sprint keeps going, none starts), none on
     * a sprint jump's take-off tick (the jump boost goes along the yaw; in the air the keys alone move him). An AP3 held walk gives its own yaws (TurnFirst.yawConstraint).
     */
    private static float[] walkYaws(Minecraft client, LocalPlayer player, float yaw) {
        ownKeys = false;
        freeLookOk = true;
        float[] held = TurnFirst.yawConstraint();
        if (held != null) {
            float[] out = new float[held.length + 1];
            out[0] = yaw;
            for (int i = 0; i < held.length; i++) {
                out[i + 1] = yaw + Mth.wrapDegrees(held[i] - yaw);
            }
            return out;
        }
        if (!walking(client, player)) {
            return null;
        }
        var o = client.options;
        int f = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0);
        int s = (o.keyLeft.isDown() ? 1 : 0) - (o.keyRight.isDown() ? 1 : 0);
        if ((f == 0 && s == 0) || (player.isSprinting() && o.keyJump.isDown() && player.onGround()) || ap3Drives()) {
            freeLookOk = f == 0 && s == 0 && !ap3Drives() && !(o.keyJump.isDown() && player.onGround());
            return new float[]{yaw};
        }
        ownKeys = true;
        float[] out = new float[8];
        int n = 0;
        for (int k : new int[]{0, 1, -1, 2, -2, 3, -3, 4}) {
            int[] r = rotate(f, s, k);
            if ((r[0] > 0) == (f > 0)) {
                out[n++] = yaw + 45f * k;
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private static boolean ap3Drives() {
        return com.killer560.hub.ap3.Ap3Executor.isSessionActive()
                && (com.killer560.hub.ap3.Ap3Executor.isDriving() || com.killer560.hub.ap3.Ap3Executor.isInputOverridden());
    }

    /** Keys (forward -1..1, left -1..1) that push along the same world direction at a yaw {@code k} x 45 degrees on. */
    static int[] rotate(int f, int s, int k) {
        double rel = Math.toDegrees(Math.atan2(-s, f)) - 45.0 * k;
        return new int[]{(int) Math.round(Math.cos(Math.toRadians(rel))), (int) -Math.round(Math.sin(Math.toRadians(rel)))};
    }

    /**
     * From {@code mixin/BreakerAuraInputMixin} at the end of {@code KeyboardInput.tick}: his keys turned by the aim's
     * steps, so they push exactly as his own did at his view's yaw. When the keys are not his physical ones (something
     * else drives him), the body goes back to his view's yaw instead, before the move; that pick waits a tick.
     */
    public static net.minecraft.world.entity.player.Input rotateInput(Minecraft client, net.minecraft.world.entity.player.Input keys) {
        if (keySteps == 0) {
            return null;
        }
        int k = keySteps;
        keySteps = 0;
        var o = client.options;
        LocalPlayer player = client.player;
        if (player == null || keys.forward() != o.keyUp.isDown() || keys.backward() != o.keyDown.isDown()
                || keys.left() != o.keyLeft.isDown() || keys.right() != o.keyRight.isDown()) {
            float viewYaw = ViewFreeze.viewYaw();
            if (player != null && !Float.isNaN(viewYaw)) {
                float y = player.getYRot() + Mth.wrapDegrees(viewYaw - player.getYRot());
                player.setYRot(y);
                player.setYHeadRot(y);
                yawOurs = false;
            }
            return null;
        }
        int[] r = rotate((keys.forward() ? 1 : 0) - (keys.backward() ? 1 : 0), (keys.left() ? 1 : 0) - (keys.right() ? 1 : 0), k);
        return new net.minecraft.world.entity.player.Input(r[0] > 0, r[0] < 0, r[1] > 0, r[1] < 0, keys.jump(), keys.shift(),
                keys.sprint());
    }

    /**
     * Turns the body (camera held) so this tick's movement packet carries a look that strikes {@code pos} from where he
     * will be (see {@link #aim}). @return what was done, or null when the pick cannot be aimed now without changing his walk.
     */
    static String aimFor(Minecraft client, LocalPlayer player, BlockPos pos, boolean urgent) {
        return aim(client, player, List.of(pos), false, urgent);
    }

    /** Why the last aim found nothing: the first pick, where he stood, his view yaw and the yaws tried (for the wait log). */
    static String lastWait() {
        return lastWait;
    }

    /** Keeps the current aim for another tick (its pick is dug next tick on the look already sent). */
    static void keep() {
        if (aiming) {
            renewed = true;
        }
    }

    private static void turn(LocalPlayer player, float yaw, float pitch, boolean ownYaw) {
        // The view he SEES, not the body: under AP3's own camera freeze the two differ, and holding the body's turned
        // the camera to it the moment AP3 let go (the S1 "look angle snap", balate-1109).
        ViewFreeze.hold(player.getViewYRot(1f), player.getViewXRot(1f));
        keySteps = 0;
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

    /** Looks turned off his walk for one tick because a pick in his way had no walk-keeping look (balatefix-1109). */
    static long offWalkAims() {
        return offWalkAims;
    }
}
