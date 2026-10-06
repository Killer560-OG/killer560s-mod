package com.killer560.hub.autopuzzles;

import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ViewFreeze;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import org.slf4j.Logger;

/**
 * One auto puzzle's free camera for its WHOLE run - the state AP3 puts him in: the real body turns to every aim (that
 * is what the server sees, and it is unchanged), while the camera stays on a held view that only his mouse steers
 * ({@link ViewFreeze}, drawn by {@code Ap3ViewYawMixin}, steered by {@code Ap3MouseYawMixin}).
 * <p>
 * killer560, 2026-10-06: "make higher lower blaze enter a free cam state right now it just snaps my camera around
 * everywhere. It should be the same state used for ap3." Every rotation did take a {@link ViewFreeze} lease, but the
 * lease is 400 ms and nothing renewed it between rotations: a Blaze shot waits out the arrow's flight plus the shoot
 * cooldown (often longer), and an etherwarp reposition waits up to two seconds for the landing. Each time it lapsed the
 * camera dropped onto the body - already facing the last blaze - and the next hold re-captured THAT as the view; Auto
 * Blaze's room-centre seed then grabbed the lapsed lease every tick and threw his mouse-steered view back to the centre.
 * The result was a camera that jumped to every target. The same lease-per-rotation pattern was in Auto Beams, Auto Ice
 * Path, Auto Ice Fill and Auto Water (whose only turns are reposition warps), so they all hold one of these now.
 * <p>
 * Use: {@link #engage} BEFORE the run's first rotation (so the held view is where he was looking), {@link #keep} on
 * every tick of the run whatever else that tick does, and {@link #release} when the run ends or is cancelled, which
 * turns the body back under the held view first so the hand-back is invisible - the same thing AP3's view freeze and
 * {@code util/BodyAim} do. A run that stops calling {@link #keep} (it threw, or the auto was switched off by a path
 * that skipped its reset) still loses the camera on its own when the lease lapses.
 */
final class FreeCam {

    private static final Logger LOGGER = ModLog.get("killer560smod-autopuzzles");

    private final String tag;
    private boolean engaged;

    FreeCam(String tag) {
        this.tag = tag;
    }

    boolean isEngaged() {
        return engaged;
    }

    /** {@link #engage(LocalPlayer, float, float)} from where he is looking now. */
    void engage(LocalPlayer player) {
        if (player != null) {
            engage(player, player.getYRot(), player.getXRot());
        }
    }

    /**
     * Takes the free camera, showing {@code viewYaw}/{@code viewPitch}. A no-op while this run already holds it, and
     * when some other owner is already holding the camera its view is kept rather than replaced.
     */
    void engage(LocalPlayer player, float viewYaw, float viewPitch) {
        if (engaged || player == null) {
            return;
        }
        engaged = true;
        ViewFreeze.hold(viewYaw, Mth.clamp(viewPitch, -90f, 90f));
        LOGGER.info("[AutoPuzzles] {}: free camera held", tag);
    }

    /** Every tick of the run: renews the lease. Does nothing when not engaged. */
    void keep(LocalPlayer player) {
        if (engaged && player != null) {
            ViewFreeze.hold(player.getYRot(), player.getXRot());
        }
    }

    /**
     * Ends the run's free camera: the body turns back under the view he is looking at (an ordinary rotation, reported
     * in the next movement packet like any mouse turn - the yaw stays on his running value, the pitch within +-90),
     * then the camera is let go, so nothing on screen moves.
     */
    void release(LocalPlayer player) {
        if (!engaged) {
            return;
        }
        engaged = false;
        float viewYaw = ViewFreeze.viewYaw();
        float viewPitch = ViewFreeze.viewPitch();
        if (player != null && !Float.isNaN(viewYaw)) {
            float yaw = player.getYRot() + Mth.wrapDegrees(viewYaw - player.getYRot());
            player.setYRot(yaw);
            player.setYHeadRot(yaw);
            player.setXRot(Mth.clamp(viewPitch, -90f, 90f));
            // The body stays on its own running yaw, so it can land a whole turn away from the held view's number
            // (93-solve-higherblaze: body 267.9 against a view of -92.1) - the same picture on screen, but the hand
            // sway has been chasing the view's number and would spin round to catch up. Shift it by the same whole
            // turns, as AP3's releaseView does.
            float shift = yaw - viewYaw;
            player.yBob += shift;
            player.yBobO += shift;
        }
        ViewFreeze.release();
        LOGGER.info("[AutoPuzzles] {}: free camera released", tag);
    }

    /** A world change: let go without touching a body that may belong to the new world. */
    void drop() {
        if (engaged) {
            engaged = false;
            ViewFreeze.release();
        }
    }
}
