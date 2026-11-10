package com.killer560.hub.util;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Packets that must go right ahead of this tick's movement packet, as vanilla sends a dig or a click it aims with a look
 * turned in the same tick (gridexp-1102). Vanilla's order inside one tick is {@code handleKeybinds} (the dig) and then
 * {@code sendPosition} (the movement packet carrying the look that aimed it), nothing between; GrimAC's RotationBreak
 * (experimental) and RotationPlace lend a dig or place the next movement packet's look only when that packet arrives within
 * 15 ms of it. Automation acting at START_CLIENT_TICK leaves the whole tick between the two - a slow tick opened the gap
 * (AP3's places, 2026-10-18; Auto Routes' breaker digs, 62-argrim with experimental checks on). So a dig whose look is
 * turned in the same tick is queued here and sent at the head of {@code LocalPlayer.sendPosition}
 * ({@code Ap3RotationSendMixin}); one still queued when the next tick starts (no sendPosition ran) goes at that tick's
 * start, when the server has heard its look. Render thread only.
 */
public final class BeforeMove {

    private static final Identifier HEAD = Identifier.fromNamespaceAndPath("killer560smod", "before_move_head");
    private static final List<Runnable> QUEUE = new ArrayList<>();
    private static int sentAtMove;
    private static int sentLate;

    private BeforeMove() {
    }

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.addPhaseOrdering(HEAD, Event.DEFAULT_PHASE);
        ClientTickEvents.START_CLIENT_TICK.register(HEAD, client -> {
            HandDigAim.tick(client);
            if (!QUEUE.isEmpty()) {
                sentLate += QUEUE.size();
                flush();
            }
        });
    }

    /** Queues {@code send} for the head of this tick's {@code sendPosition}. */
    public static void queue(Runnable send) { QUEUE.add(send); }

    /**
     * From {@code Ap3RotationSendMixin} at the head of {@code sendPosition}. The queued packets are WRITTEN, not flushed
     * ({@code Ap3SendTogetherMixin} reads {@link #holdFlush}): the movement packet sendPosition sends next flushes them
     * with it in one write, so the server reads the dig and the packet carrying its look together (rbcause-1102). Sent
     * as two flushes they were two reads on the server, and under load GrimAC's 15 ms lending window
     * ({@code CheckManagerListener.handleQueuedBreaks}, server {@code currentTimeMillis} at each read) was missed though
     * the client wrote them 0.1-1.3 ms apart: RotationBreak post-flying, then pre-flying on every later dig.
     */
    public static void beforeSendPosition() {
        if (!QUEUE.isEmpty()) {
            sentAtMove += QUEUE.size();
            holdFlush = true;
            flushOwed = true;
            try {
                flush();
            } finally {
                holdFlush = false;
            }
        }
    }

    private static boolean holdFlush;
    private static boolean flushOwed;

    /** {@link HandDigAim}: hold the flush of his dig's packets until this tick's movement packet carries their look. */
    static void holdUntilMove(boolean on) {
        holdFlush = on;
        flushOwed |= on;
    }
    private static int heldFlushes;

    /** For {@code Ap3SendTogetherMixin}: hold this send's flush (only BeforeMove's own sends, render thread). */
    public static boolean holdFlush() { return holdFlush && Minecraft.getInstance().isSameThread(); }

    public static void noteHeldFlush() { heldFlushes++; }

    /** Packets whose flush was held to go with the movement packet (testkit: the mixin applied and acted). */
    public static int heldFlushes() { return heldFlushes; }

    /** From {@code Ap3RotationSendMixin} at the RETURN of {@code sendPosition}: a held flush goes now if no movement
     *  packet flushed it (no movement packet this tick). */
    public static void afterSendPosition(net.minecraft.client.player.LocalPlayer self) {
        if (!flushOwed) return;
        flushOwed = false;
        if (self.connection != null && self.connection.getConnection().isConnected()) {
            self.connection.getConnection().flushChannel();
        }
    }

    private static void flush() {
        if (!Minecraft.getInstance().isSameThread()) return;
        List<Runnable> now = new ArrayList<>(QUEUE);
        QUEUE.clear();
        for (Runnable r : now) {
            try {
                r.run();
            } catch (RuntimeException e) {
                ModLog.get("killer560smod-autoroutes").error("[BeforeMove] a queued send threw", e);
            }
        }
        DigWindow.clearPending(); // a queued dig that was refused never went
    }

    /** Sends that went at a movement packet's head / late at the next tick's start (only go up) - read by the testkit. */
    public static int sentAtMove() { return sentAtMove; }

    public static int sentLate() { return sentLate; }

    /** Drops anything queued (world change). */
    public static void clear() {
        QUEUE.clear();
        DigWindow.clearPending();
    }
}
