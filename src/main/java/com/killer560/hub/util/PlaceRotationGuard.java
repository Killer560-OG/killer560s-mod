package com.killer560.hub.util;

import com.killer560.hub.compat.McPackets;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
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
    /** The yaw the server last heard: a movement packet's or (26.3) a teleport accept's, which GrimAC counts as a turn too. */
    private static float sentYaw;
    /** The yaw turn of the last look the server heard. */
    private static float lastTurn;
    /**
     * The turn the server last heard before each recent block click, newest at {@code clicks - 1}. GrimAC judges a click
     * against the last one it did NOT drop, and it drops a click that arrives while a teleport of its own is unanswered
     * (shouldBlockMovement) or one a place check cancelled (leverfreeze-tp-1117: a lever flick sent just before a teleport
     * accept was dropped, the guard steered the next turn off its 180.00 back onto the 180.01 of the click before, and
     * DuplicateRotPlace flagged). So a turn must miss every click that may still be the last one judged.
     */
    private static final float[] CLICK_TURN = new float[8];
    private static final long[] CLICK_AT = new long[8];
    private static final boolean[] CLICK_DROPPABLE = new boolean[8];
    private static int clicks;
    /** A click newer than this when a teleport accept leaves may have met that teleport unanswered at the server. */
    private static final long TELEPORT_WINDOW_NS = 1_000_000_000L;
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
            heard(move.getYRot(sentYaw));
        } else if (p instanceof ServerboundAcceptTeleportationPacket accept) {
            long now = System.nanoTime();
            for (int i = Math.max(0, clicks - CLICK_AT.length); i < clicks; i++) {
                if (now - CLICK_AT[i % CLICK_AT.length] < TELEPORT_WINDOW_NS) CLICK_DROPPABLE[i % CLICK_AT.length] = true;
            }
            float yaw = McPackets.acceptYRot(accept);
            if (!Float.isNaN(yaw)) heard(yaw);
        } else if (p instanceof ServerboundUseItemOnPacket) {
            int i = clicks++ % CLICK_TURN.length;
            CLICK_TURN[i] = lastTurn; // a click with no turn since the last one leaves GrimAC's count where it was: the same turn
            CLICK_AT[i] = System.nanoTime();
            CLICK_DROPPABLE[i] = false;
        }
    }

    private static void heard(float yaw) {
        if (sentKnown) lastTurn = Math.abs(yaw - sentYaw);
        sentYaw = yaw;
        sentKnown = true;
    }

    /**
     * Whether {@code turn} could repeat the turn GrimAC compares the next click with: any click back to the newest one
     * that is surely judged (four back, older than the teleport window, met no teleport accept).
     */
    private static boolean repeats(float turn) {
        long now = System.nanoTime();
        for (int k = 0; k < Math.min(clicks, CLICK_TURN.length); k++) {
            int i = (clicks - 1 - k) % CLICK_TURN.length;
            if (Math.abs(turn - CLICK_TURN[i]) < 10 * SAME) return true;
            if (k >= 3 && !CLICK_DROPPABLE[i] && now - CLICK_AT[i] > TELEPORT_WINDOW_NS) return false;
        }
        return false;
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
        if (clicks == 0 || useSinceMove) {
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
        if (useSinceMove || !Float.isNaN(useUnNudged) || clicks == 0
                || !(self instanceof com.killer560.hub.blink.mixin.BlinkReminderAccessor a)) {
            return;
        }
        useUnNudged = nudge(self, a.killer560$yRotLast());
    }

    /**
     * Moves the yaw by a few {@link #NUDGE}s when its turn from the yaw the server last heard would repeat a click turn;
     * returns the yaw it had, or NaN. Only a yaw change vanilla sends anyway is moved: no look packet is added.
     */
    private static float nudge(LocalPlayer self, float yRotLast) {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return Float.NaN; // a legit jar never touches what his own mouse sends; the automation it guards is cheat-only
        }
        float yaw = self.getYRot();
        float from = sentKnown ? sentYaw : yRotLast; // a teleport accept can have moved what the server heard
        float turn = Math.abs(yaw - from);
        if (yaw == yRotLast || turn <= MIN_TURN || !repeats(turn)) return Float.NaN;
        float step = yaw >= from ? NUDGE : -NUDGE;
        float nudged = yaw + step;
        for (int k = 2; k <= CLICK_TURN.length + 1 && repeats(Math.abs(nudged - from)); k++) nudged = yaw + k * step;
        self.setYRot(nudged);
        nudges++;
        if (nudges <= 20 || nudges % 100 == 0) {
            ModLog.get("killer560smod-rightclick").info("[PlaceRotationGuard] yaw turn {} would repeat a turn before a recent "
                    + "block click (GrimAC DuplicateRotPlace) - sent {} instead of {} (nudge #{})", turn, nudged, yaw, nudges);
        }
        return yaw;
    }

    /** Turns moved so far (only goes up) - read by the testkit. */
    public static int nudges() { return nudges; }
}
