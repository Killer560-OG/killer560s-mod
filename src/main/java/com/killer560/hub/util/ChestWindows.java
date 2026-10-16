package com.killer560.hub.util;

import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.world.inventory.MenuType;

import java.util.Set;

/**
 * Every chest window the server opened for us, counted as its packet ARRIVES - whether or not a screen ever shows.
 *
 * <p>Why a counter and not {@code client.screen}: Auto Close Chest cancels the open-screen packet at the head of
 * {@code ClientPacketListener.handleOpenScreen} and answers it with a close, so with it on (killer560's setting) a chest
 * click never shows a screen, and the lid is open on the client for at most a tick - often not at all, the open and the
 * close block events arriving together. Auto Ice Path's reward chest read that as "did not open", clicked twice more,
 * gave up on it and then waited for a chest to "appear" (2026-10-15, his log 22:56:54: the sim's server logged the chest
 * opened on the first click). A click's answer is the window packet; this is where every automation that needs to know
 * whether its chest click worked reads it ({@link #count} before the click, {@link #since} after).
 *
 * <p>Fed by {@code autoclosechest.mixin.AutoCloseChestMixin} for every open-screen packet, in every build. That hook runs
 * twice for a packet that is not cancelled (once on the network thread, once again on the render thread after vanilla's
 * hand-off), with the same packet object both times, so the second sighting of one packet is not counted. (Not the
 * container id: a server numbers windows per player from 1, so a new world's first chest has the id the last world's
 * did - deduplicating by id swallowed the first chest window of every later sim run.)
 */
public final class ChestWindows {

    private static final Set<MenuType<?>> CHEST_TYPES = Set.of(
            MenuType.GENERIC_9x1, MenuType.GENERIC_9x2, MenuType.GENERIC_9x3,
            MenuType.GENERIC_9x4, MenuType.GENERIC_9x5, MenuType.GENERIC_9x6,
            MenuType.GENERIC_3x3);

    private static volatile int count;
    /** The last packet counted, by identity only. */
    private static ClientboundOpenScreenPacket lastPacket;

    private ChestWindows() {
    }

    /** From the open-screen packet hook, any thread. Never throws. */
    public static void noteOpenPacket(ClientboundOpenScreenPacket packet) {
        if (packet == null || !CHEST_TYPES.contains(packet.getType())) {
            return;
        }
        synchronized (ChestWindows.class) {
            if (packet == lastPacket) {
                return;
            }
            lastPacket = packet;
            count++;
        }
    }

    private static volatile int ackedUpTo = -1;

    /**
     * From {@code autoclosechest.mixin.ChestClickAckMixin}: the server has handled every block click up to this
     * prediction sequence. A server handles a click and sends the window it opens BEFORE the tick's ack
     * ({@code ServerGamePacketListenerImpl}: the window in {@code handleUseItemOn}, the ack in {@code tick}), and both
     * are counted here as they arrive, in order - so a click whose ack is in with no window since is one the server
     * took and did not open. Sequences restart with a new connection, so a lower one is taken as is.
     */
    public static void noteAck(int sequence) {
        ackedUpTo = sequence;
    }

    /** Whether the server has acked the block click with this sequence. */
    public static boolean acked(int sequence) {
        return sequence >= 0 && ackedUpTo >= sequence;
    }

    /** Chest windows the server has opened for us so far this session. */
    public static int count() {
        return count;
    }

    /** Whether a chest window has arrived since {@code mark} (an earlier {@link #count}). */
    public static boolean since(int mark) {
        return count != mark;
    }
}
