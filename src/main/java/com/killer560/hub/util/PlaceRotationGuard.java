package com.killer560.hub.util;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;

/**
 * GrimAC 2.3.74's experimental DuplicateRotPlace (gridexp-1102): a block click ({@code use_item_on}) flags when the yaw
 * turn the server last heard before it is over 2 degrees and equal, to 0.0001, to the turn heard before the previous block
 * click. A mouse never repeats a turn to four decimals; automation that snaps to recorded angles does (62-argrim-1017-
 * breakerwarp: two 45.0 turns, one before each click; 62-argrim-1020-acbat: two turns 0.000004 apart).
 *
 * <p>So the last movement packet's yaw turn and the turn that preceded the last block click are tracked from the send tap,
 * and right before {@code LocalPlayer.sendPosition} builds a packet whose yaw turn would repeat that one, the yaw moves
 * by {@link #NUDGE} degrees (a hundredth of a degree: nothing any aim can see, a hundred times Grim's tolerance). Every
 * feature's turns go through here, and his own mouse never needs it.
 */
public final class PlaceRotationGuard {

    /** Grim compares the two turns to this. */
    private static final float SAME = 0.0001f;
    /** What the yaw moves by when a turn would repeat. */
    public static final float NUDGE = 0.01f;
    /** Grim only compares turns over this many degrees. */
    private static final float MIN_TURN = 2f;

    private static boolean sentKnown;
    private static float sentYaw;
    /** The yaw turn of the last movement packet that carried a look, and whether one went since the last block click. */
    private static float lastTurn;
    private static boolean turnedSinceClick;
    /** The turn the server counted for the last block click. */
    private static float clickTurn = Float.NaN;
    private static int nudges;
    /** An item use (which carries its own look) has left since the last movement packet or tick end. */
    private static boolean useSinceMove;

    private PlaceRotationGuard() {
    }

    /** From the send tap, client thread. */
    public static void onPacketSent(Packet<?> p) {
        if (p instanceof net.minecraft.network.protocol.game.ServerboundUseItemPacket) {
            useSinceMove = true;
        } else if (p instanceof ServerboundMovePlayerPacket || p instanceof net.minecraft.network.protocol.game.ServerboundClientTickEndPacket) {
            useSinceMove = false;
        }
        if (p instanceof ServerboundMovePlayerPacket move && move.hasRotation()) {
            float yaw = move.getYRot(sentYaw);
            if (sentKnown) {
                lastTurn = Math.abs(yaw - sentYaw);
                turnedSinceClick = true;
            }
            sentYaw = yaw;
            sentKnown = true;
        } else if (p instanceof ServerboundUseItemOnPacket && turnedSinceClick) {
            clickTurn = lastTurn;
            turnedSinceClick = false;
        }
    }

    /**
     * From {@code Ap3RotationSendMixin} at the head of {@code sendPosition}, after AP3's own rotation swaps: when the
     * yaw about to be sent ({@code getYRot()} against {@code yRotLast}) would repeat the turn before the last block click,
     * moves it by {@link #NUDGE} for this one send (a running value, never wrapped).
     *
     * @return the yaw to put back once {@code sendPosition} returns (so nothing that compares his yaw - the routes'
     *         camera-turn detectors - ever sees the nudge), or NaN when nothing was moved
     */
    public static float beforeSend(LocalPlayer self, float yRotLast) {
        if (!Float.isNaN(useUnNudged)) {
            // beforeUse already moved this tick's yaw ahead of an item use: the packet reports that look, put back after.
            float yaw = useUnNudged;
            useUnNudged = Float.NaN;
            return yaw;
        }
        if (Float.isNaN(clickTurn) || useSinceMove) {
            // An item use went out since the last tick packet: it carries this tick's look, and GrimAC BadPacketsJ wants
            // the movement packet to report exactly that look - nothing may move it (62-argrim-1020-acbat, gridexp-1102).
            return Float.NaN;
        }
        return nudge(self, yRotLast);
    }

    /** The yaw {@link #beforeUse} moved for this tick's item use, put back after the tick's sendPosition; NaN when none. */
    private static float useUnNudged = Float.NaN;

    /**
     * From the head of {@code MultiPlayerGameMode.useItem} (his right click or a feature's): the {@code use_item} carries
     * this tick's look and the movement packet after it must report the same (BadPacketsJ), so a turn that would repeat
     * the one before the last block click is moved here, before the use, and kept through this tick's sendPosition
     * (grimsame-1105: the Lights Aura's turn plus his right click in one tick drew DuplicateRotPlace, 1105-same-lights-use).
     */
    public static void beforeUse(LocalPlayer self) {
        if (useSinceMove || !Float.isNaN(useUnNudged) || Float.isNaN(clickTurn)
                || !(self instanceof com.killer560.hub.blink.mixin.BlinkReminderAccessor a)) {
            return;
        }
        useUnNudged = nudge(self, a.killer560$yRotLast());
    }

    /** Moves the yaw by {@link #NUDGE} when its turn would repeat the click turn; returns the yaw it had, or NaN. */
    private static float nudge(LocalPlayer self, float yRotLast) {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return Float.NaN; // a legit jar never touches what his own mouse sends; the automation it guards is cheat-only
        }
        float yaw = self.getYRot();
        float turn = Math.abs(yaw - yRotLast);
        if (turn <= MIN_TURN || Math.abs(turn - clickTurn) >= 10 * SAME) return Float.NaN;
        float nudged = yaw + (yaw >= yRotLast ? NUDGE : -NUDGE);
        self.setYRot(nudged);
        nudges++;
        if (nudges <= 20 || nudges % 100 == 0) {
            ModLog.get("killer560smod-rightclick").info("[PlaceRotationGuard] yaw turn {} would repeat the {} before the "
                    + "last block click (GrimAC DuplicateRotPlace) - sent {} instead of {} (nudge #{})", turn, clickTurn,
                    nudged, yaw, nudges);
        }
        return yaw;
    }

    /** Turns moved so far (only goes up) - read by the testkit. */
    public static int nudges() { return nudges; }
}
