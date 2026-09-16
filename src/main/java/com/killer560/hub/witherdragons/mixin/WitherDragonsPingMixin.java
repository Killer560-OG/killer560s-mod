package com.killer560.hub.witherdragons.mixin;

import com.killer560.hub.witherdragons.WitherDragonsPackets;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Server-tick source for {@code ServerTickClock} - Odin counts one server tick per non-zero-id
 *  {@code ClientboundPingPacket} (ConnectionMixin.channelRead0). Here the hook sits right after
 *  {@code handlePing}'s {@code ensureRunningOnSameThread} (javap-verified on 26.1.2:
 *  {@code ClientCommonPacketListenerImpl.handlePing} calls it first, then sends the pong), so it runs on the client
 *  thread once per ping. Never cancels. {@code require = 0}. */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class WitherDragonsPingMixin {

    @Inject(method = "handlePing", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
            shift = At.Shift.AFTER), require = 0)
    private void killer560smod$witherDragons$onPing(ClientboundPingPacket packet, CallbackInfo ci) {
        WitherDragonsPackets.onPing(packet.getId());
    }
}
