package com.killer560.hub.util;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Aims an automated action the way a vanilla client's would be aimed: the player's BODY - the rotation every packet
 * reports - is turned to the action's look on the action's own tick, while the CAMERA is held still with
 * {@link ViewFreeze}; and the body is given back to the held view (his mouse movement included) a tick after the owner
 * stops acting.
 * <p>
 * Why: a use packet carrying a rotation the client never reports in a movement packet is not something a vanilla
 * client can send. GrimAC's BadPacketsJ flagged every such packet - Auto Puzzles on 2026-09-27, Auto Routes' etherwarps
 * on 2026-10-05 (62-argrim) - and the Interactive Map's warps sent exactly the same packet. With the body turned, the
 * tick's movement packet reports the rotation the use carried.
 * <p>
 * One instance per owner (Auto Routes, the Interactive Map's executor), so one owner finishing never gives back a view
 * the other is still turning him under. Lifted out of {@code autoroutes/RouteExecutor} on 2026-10-05 unchanged.
 */
public final class BodyAim {

    /** Run after every write to his rotation this class makes (Auto Routes rebases its camera-turn detector). */
    private final Runnable onWrite;
    /** True while this owner has turned his body and holds the camera. */
    private boolean held;
    /** Ticks of {@link #tick} since the body was last turned; the view is only given back once this is >= 1, so the
     *  movement packet of the action's own tick has reported the rotation the action used. */
    private int ticksSinceTurn;

    public BodyAim(Runnable onWrite) {
        this.onWrite = onWrite;
    }

    /**
     * Turns the BODY to {@code yaw}/{@code pitch} - a yaw already expressed on the running (unwrapped) yaw, pitch kept
     * within +-90 - while the camera stays where he had it.
     */
    public void turn(LocalPlayer player, float yaw, float pitch) {
        ViewFreeze.hold(player.getYRot(), player.getXRot());
        held = true;
        ticksSinceTurn = 0;
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(Mth.clamp(pitch, -90f, 90f));
        onWrite.run();
    }

    /** {@link #turn} to a target yaw given in any wrapping: the same direction as a delta on his running yaw. */
    public void turnTo(LocalPlayer player, float targetYaw, float pitch) {
        turn(player, player.getYRot() + Mth.wrapDegrees(targetYaw - player.getYRot()), pitch);
    }

    /**
     * Every client tick, before the owner acts: keeps the camera held while {@code acting}, and otherwise - once at
     * least one movement packet has reported the last action's rotation - turns the body back to the held view and
     * releases the camera.
     */
    public void tick(LocalPlayer player, boolean acting) {
        if (!held) {
            return;
        }
        if (player == null) {
            held = false;
            ViewFreeze.release();
            return;
        }
        ticksSinceTurn++;
        if (acting || ticksSinceTurn < 1) {
            ViewFreeze.hold(player.getYRot(), player.getXRot());
            return;
        }
        float viewYaw = ViewFreeze.viewYaw();
        float viewPitch = ViewFreeze.viewPitch();
        if (!Float.isNaN(viewYaw)) {
            float yaw = player.getYRot() + Mth.wrapDegrees(viewYaw - player.getYRot());
            player.setYRot(yaw);
            player.setYHeadRot(yaw);
            player.setXRot(Mth.clamp(viewPitch, -90f, 90f));
        }
        ViewFreeze.release();
        held = false;
        onWrite.run();
    }

    public boolean isHeld() {
        return held;
    }
}
