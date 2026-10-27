package com.killer560.hub.ap3.mixin;

import com.killer560.hub.ap3.Ap3Executor;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * AP3's armed USE / BLOCK / BOOM packets go onto the wire in ONE write with the movement packet behind them.
 * {@code Connection#send(Packet, ChannelFutureListener, boolean)} (javap 26.1.2 and 26.2: the same signature on both,
 * see {@code blink/mixin/BlinkConnectionMixin}; the one- and two-argument {@code send}s call it) is told not to flush
 * while {@link Ap3Executor#holdFlush()} is set - only for the few packets {@code Ap3Executor.beforeSendPosition} sends -
 * and the movement packet {@code sendPosition} sends right after them flushes them all together
 * ({@code Ap3Executor.afterSendPosition} flushes if no movement packet went). Why: GrimAC 2.3.74 lends a place the next
 * movement packet's rotation only when it READS that packet within 15 ms of the place; sent as two flushes, a loaded
 * server read them in two wakeups and the gap went past 15 ms (RotationPlace post-flying, ap3-blockuse-1027).
 */
@Mixin(Connection.class)
public abstract class Ap3SendTogetherMixin {

    @ModifyVariable(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private boolean killer560smod$ap3HoldFlush(boolean flush) {
        if (flush && Ap3Executor.holdFlush()) {
            Ap3Executor.noteHeldFlush();
            return false;
        }
        return flush;
    }
}
