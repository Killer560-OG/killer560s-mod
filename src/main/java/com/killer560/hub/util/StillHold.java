package com.killer560.hub.util;

import com.killer560.hub.invwalk.InventoryWalkFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;

import java.util.ArrayDeque;

/**
 * Holds a container CLOSE the mod sends while the server still has him moving, sprinting or sneaking, until its last input
 * and sprint are still ({@link InventoryWalkFeature#serverHasStillInput}); GrimAC's MultiActionsD (default config too) flags
 * a close sent that way, and Auto Close Chest sends one for a chest opened on the run with no screen to release the keys.
 * Chat and commands are NEVER held (chatnow-1112 / stillhold-1113: mod chat leaves the moment it is requested). The one
 * choke point is the head of {@code Connection.send} ({@link com.killer560.hub.util.mixin.StillHoldMixin}). Order is kept,
 * one held close leaves per {@link #GAP_TICKS} client ticks, bounded by {@link #CAP}.
 */
public final class StillHold {

    private static final int CAP = 32;
    private static final int GAP_TICKS = 10;
    private static final ArrayDeque<Packet<?>> HELD = new ArrayDeque<>();
    private static boolean releasing;
    private static int wait;

    private StillHold() {
    }

    /** Called at the head of {@code Connection.send}; true = this packet is held, cancel the send. */
    public static boolean hold(Packet<?> p) {
        if (releasing || !(p instanceof ServerboundContainerClosePacket)) {
            return false;
        }
        if (Minecraft.getInstance().player == null || (HELD.isEmpty() && InventoryWalkFeature.serverHasStillInput()) || HELD.size() >= CAP) {
            return false;
        }
        HELD.add(p);
        return true;
    }

    /** Client tick end: lets one held close go once the server has him still. */
    public static void tick(Minecraft mc) {
        if (HELD.isEmpty()) {
            return;
        }
        if (mc.player == null || mc.getConnection() == null) {
            HELD.clear();
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        if (!InventoryWalkFeature.serverHasStillInput()) {
            return;
        }
        releasing = true;
        try {
            mc.getConnection().send(HELD.poll());
        } finally {
            releasing = false;
        }
        wait = GAP_TICKS;
    }
}
