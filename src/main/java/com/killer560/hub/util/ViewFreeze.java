package com.killer560.hub.util;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Holds the CAMERA still while something else turns the player's real yaw and pitch.
 * <p>
 * killer560 (2026-09-27): "For all of the auto puzzles, if it goes to reposition I should have my camera put into
 * a similar free state as ap3 so my vision isn't gonna give me epileptic attacks as it rotates everywhere."
 * <p>
 * Exactly the trick AP3's Camera Planner already uses, lifted somewhere both can reach it. The real rotation still
 * happens - the aim has to be real or the shot misses and the server sees something different from the client -
 * but {@code LocalPlayer#getViewYRot / getViewXRot} answer with the held view instead, and those two are read by
 * the camera and the crosshair pick ONLY. Movement ({@code getYRot()} in travel), {@code sendPosition} and the
 * player model never call them, so nothing Hypixel receives changes by a degree. That is the whole reason this is
 * safe to do: it is a lie told to the monitor, not to the server.
 * <p>
 * <b>It releases itself.</b> A puzzle that throws, is cancelled, or simply stops mid-rotation must never be able
 * to leave him stuck looking the wrong way, and relying on every caller to run a release in a finally block is
 * exactly the kind of promise that gets broken once and then never noticed. So a hold is a LEASE: whoever is
 * turning him re-asserts it each time it rotates, and it lapses on its own {@link #STALE_MS} after the last
 * assertion. Nothing to leak.
 * <p>
 * While held, his mouse steers the held view rather than his real body ({@code Ap3MouseYawMixin}), which is what
 * makes it a free camera instead of a frozen one - he can look around while the puzzle works.
 */
public final class ViewFreeze {

    /** A lease lapses this long after the last {@link #hold}. About eight ticks - long enough to span the gaps
     *  between a solver's rotations, short enough that a solver stopping is unnoticeable. */
    private static final long STALE_MS = 400L;

    private static float yaw = Float.NaN;
    private static float pitch = Float.NaN;
    private static long heldUntil;
    /** Every {@link #hold} ever made, so an owner can tell whether anybody else has held the camera since it did. */
    private static long holds;

    private ViewFreeze() {
    }

    /**
     * Take or renew the lease. The view is captured on the FIRST hold of a run and kept from then on, so a solver
     * rotating repeatedly does not drag the camera along one step at a time - which would be the very whipping
     * this exists to stop.
     *
     * @param currentYaw   his real yaw right now, used only if this is the first hold
     * @param currentPitch his real pitch right now, same
     */
    public static void hold(float currentYaw, float currentPitch) {
        holds++;
        if (!isHeld()) {
            yaw = currentYaw;
            pitch = currentPitch;
        }
        heldUntil = System.currentTimeMillis() + STALE_MS;
    }

    /** Give the camera straight back. Optional - a lease lapses by itself; this is for a clean, instant handover. */
    public static void release() {
        yaw = Float.NaN;
        pitch = Float.NaN;
        heldUntil = 0L;
    }

    /** The number of {@link #hold} calls so far (see util/TurnFirst: it never takes away a hold somebody else renewed). */
    public static long holdCount() {
        return holds;
    }

    public static boolean isHeld() {
        return !Float.isNaN(yaw) && System.currentTimeMillis() < heldUntil;
    }

    /** The held view yaw, or NaN when nothing is held. Deliberately NOT wrapped to 0-360 - see the mod's standing
     *  rule about running rotation values; this is a view, and wrapping it would jump the camera. */
    public static float viewYaw() {
        return isHeld() ? yaw : Float.NaN;
    }

    public static float viewPitch() {
        return isHeld() ? pitch : Float.NaN;
    }

    /**
     * Every client tick, at its END - after {@code LocalPlayer.aiStep} has moved the first-person hand sway toward the
     * REAL rotation: while held, the hand chases the held view instead, with the same half-a-tick chase vanilla uses.
     * AP3's own view freeze does exactly this ({@code Ap3Executor.tickView}); without it the hand swings across the
     * screen on every aim even though the camera stays put.
     */
    public static void followHandSway(LocalPlayer player) {
        if (player == null || !isHeld()) {
            return;
        }
        player.yBob = player.yBobO + (yaw - player.yBobO) * 0.5f;
        player.xBob = player.xBobO + (pitch - player.xBobO) * 0.5f;
    }

    /** His mouse, moving the held view instead of his body. */
    public static void turnView(float dYaw, float dPitch) {
        if (!isHeld()) {
            return;
        }
        yaw += dYaw;
        pitch = Mth.clamp(pitch + dPitch, -90f, 90f);
    }
}
