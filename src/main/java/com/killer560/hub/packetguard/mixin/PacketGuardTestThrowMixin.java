package com.killer560.hub.packetguard.mixin;

import com.killer560.hub.packetguard.PacketGuard;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Testkit hook (dev builds only, folded out of a release): {@link PacketGuard#armTestThrow} makes the next time packets
 *  throw from this handler, the shape of a bug in one of our packet hooks, so a scenario can prove the guard keeps the
 *  connection. Unarmed it is one static read. */
@Mixin(ClientPacketListener.class)
public abstract class PacketGuardTestThrowMixin {

    // After ensureRunningOnSameThread, like every real hook: a HEAD inject also runs once on the netty thread first,
    // where a throw goes to Connection.exceptionCaught instead of onPacketError.
    @Inject(method = "handleSetTime", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
            shift = At.Shift.AFTER), require = 0)
    private void killer560smod$packetGuardTestThrow(ClientboundSetTimePacket packet, CallbackInfo ci) {
        if (com.killer560.hub.BuildVariant.DEV_TOOLS && PacketGuard.consumeTestThrow()) {
            throw new IllegalStateException("killer560smod packet guard test throw");
        }
    }
}
