package com.killer560.hub.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * His own dig while the camera is held off the body (ap3digrot-1109): an AP3 walk freezes his view at one yaw and walks the
 * body at another, so his crosshair picks a block the look on the wire never strikes, and experimental GrimAC RotationBreak
 * flagged his digs ("post-flying", then "pre-flying" on every later one; 17 a run in 1109-boss-ee3route-same-dig). At the
 * head of {@code Minecraft.startAttack} / a sending {@code continueAttack} the body is turned to a look that strikes his
 * block - a walk-keyed yaw (the walk's path unchanged; no walk: his view) - and the dig's
 * flush is held to this tick's movement packet, which carries that look: GrimAC lends a dig the next movement packet's look
 * when both arrive within 15 ms, judged from the position it last heard (the eye now, before this tick's move). The next
 * tick gives the body back if nothing else wrote it. Cheat build only (only automation holds the camera off the body).
 * <p>
 * Under a held walk with no walk-keyed yaw on his block the dig waits for a tick that has one (ap3holddig-1110): the
 * least-bending yaw it used to take bent the walk up to 11 degrees, 0.15 blocks in one tick, and steering that back out
 * left him 0.17 blocks behind, which put the fall after AP3's boom #26 past the corner the route slides on, into the lava.
 */
public final class HandDigAim {

    private static final org.slf4j.Logger LOGGER = ModLog.get("killer560smod-turnfirst");
    private static boolean turned, digTick;
    private static float prevYaw, prevPitch, setYaw, setPitch;
    private static int turns, waits;

    private HandDigAim() {
    }

    /** Next tick's START (from {@link BeforeMove#register}): the body back where it was, unless something wrote it since. */
    static void tick(Minecraft mc) {
        digTick = false;
        if (!turned) return;
        turned = false;
        LocalPlayer p = mc.player;
        if (p == null || p.getYRot() != setYaw || p.getXRot() != setPitch) return;
        float yaw = p.getYRot() + Mth.wrapDegrees(prevYaw - p.getYRot());
        p.setYRot(yaw);
        p.setYHeadRot(yaw);
        p.setXRot(prevPitch);
    }

    /** HEAD of a held {@code continueAttack}: as {@link #beforeDig} when a dig packet goes this tick. */
    public static boolean beforeHeldDig(Minecraft mc) {
        return !com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED || mc.player == null || !DigWindow.handDigsThisTick(mc)
                || beforeDig(mc);
    }

    private static boolean offBody(LocalPlayer p) { return p.getViewYRot(1f) != p.getYRot() || p.getViewXRot(1f) != p.getXRot(); }

    /** HEAD of his dig: turns the body onto his block when its look misses it, and holds the dig's flush to the move.
     *  False: the dig waits this tick (a held walk has no walk-keyed yaw on his block). */
    public static boolean beforeDig(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED || p == null || mc.level == null || mc.missTime > 0
                || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return true;
        digTick = true;
        if (!offBody(p)) return true; // the camera is the body: the pick is the wire's look
        float viewYaw = p.getViewYRot(1f);
        float viewPitch = p.getViewXRot(1f);
        var pos = hit.getBlockPos();
        VoxelShape shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
        java.util.function.BiPredicate<Vec3, Vec3> ray = (a, b) -> shape.isEmpty() ? new AABB(pos).clip(a, b).isPresent()
                : shape.clip(a, b, pos) != null;
        double reach = p.blockInteractionRange();
        Vec3 eye = p.getEyePosition();
        if (ray.test(eye, eye.add(TurnFirst.look(p).scale(reach)))) return true;
        if (ActionGate.hisUseGoesThisTick(mc) || TurnFirst.bodyOwnedAgainstHand()) return true; // a use_item's look (BadPacketsJ) / an AP3 align
        float[] allowed = TurnFirst.yawConstraint();
        float[] found = allowed == null ? null : TurnFirst.walkKeyedLook(allowed, eye, viewPitch, ray, reach, 720);
        if (found == null && allowed != null) {
            digTick = false;
            if (++waits <= 20 || waits % 100 == 0) {
                LOGGER.info("[HandDigAim] his dig on {} waits: no walk-keyed look strikes it - #{}", pos.toShortString(), waits);
            }
            return false;
        }
        float yaw = found == null ? viewYaw : found[0];
        float pitch = found == null ? viewPitch : found[1];
        if (!turned) {
            prevYaw = p.getYRot();
            prevPitch = p.getXRot();
        }
        // From the yaw the server last heard, as TurnFirst turns (a whole-turn step on the wire is GrimAC AimModulo360).
        float heard = p instanceof com.killer560.hub.blink.mixin.BlinkReminderAccessor a ? a.killer560$yRotLast() : p.getYRot();
        setYaw = heard + Mth.wrapDegrees(yaw - heard);
        setPitch = Mth.clamp(pitch, -90f, 90f);
        p.setYRot(setYaw);
        p.setYHeadRot(setYaw);
        p.setXRot(setPitch);
        turned = true;
        BeforeMove.holdUntilMove(true);
        if (++turns <= 20 || turns % 100 == 0) {
            LOGGER.info("[HandDigAim] his dig on {}: body turned to yaw {} pitch {} ({}) - #{}", pos.toShortString(),
                    String.format(java.util.Locale.US, "%.1f", yaw), String.format(java.util.Locale.US, "%.1f", pitch),
                    found == null ? "his view" : "walk-keyed", turns);
        }
        return true;
    }

    /** RETURN of his dig: the dig's packets are written; the movement packet flushes them (BeforeMove.afterSendPosition). */
    public static void afterDig() { BeforeMove.holdUntilMove(false); }

    /** This tick's body yaw is a dig turn (AP3's held walk counts the bend it puts in the walk and steers it back out). */
    public static boolean turnedThisTick() { return turned; }

    /** His dig goes this tick: the movement packet's look is the dig's, so nothing else may turn the body now. */
    public static boolean digThisTick() { return digTick; }

    /** Digs turned for (only goes up) - read by the testkit. */
    public static int turns() { return turns; }

    /** His digs held back for want of a walk-keyed look (only goes up) - read by the testkit. */
    public static int waits() { return waits; }
}
