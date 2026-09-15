package com.killer560.hub.bloodcamp.mixin;

import com.killer560.hub.bloodcamp.BloodCampFeature;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Read-only observation of three real incoming packets - see {@link BloodCampFeature}'s own class doc
 *  for the real mechanic these feed. Injected right AFTER the thread hand-off call, but before vanilla's
 *  own handler updates anything, so entity position reads inside {@link BloodCampFeature} still see the
 *  OLD (pre-packet) state, matching Noamm's own real reference (which reads the entity's current position
 *  and adds the packet's own delta on top, rather than reading an already-updated position). Never
 *  cancels anything.
 *  <p>
 *  Real bug found and fixed (2026-09-14): these were injected at HEAD. In the 26.1.2 bytecode every one of
 *  these three handlers starts with {@code PacketUtils.ensureRunningOnSameThread(packet, this,
 *  minecraft.packetProcessor())}, which on the Netty thread schedules the packet onto the main thread and
 *  throws RunningOnDifferentThreadException - so a HEAD hook ran TWICE per packet: once on the network
 *  thread and again on the main thread, mutating BloodCampFeature's plain HashMap from two threads (and
 *  double-counting every move delta). Shifting to AFTER that call means the hook only ever runs on the
 *  main thread (the network-thread pass throws before reaching it). */
@Mixin(ClientPacketListener.class)
public abstract class BloodCampPacketMixin {

    private static final String ENSURE_SAME_THREAD =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleSetEquipment", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
    private void killer560smod$onSetEquipment(ClientboundSetEquipmentPacket packet, CallbackInfo ci) {
        BloodCampFeature.onSetEquipment(packet, ((ClientPacketListener) (Object) this).getLevel());
    }

    @Inject(method = "handleMoveEntity", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
    private void killer560smod$onMoveEntity(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
        BloodCampFeature.onMoveEntity(packet, ((ClientPacketListener) (Object) this).getLevel());
    }

    @Inject(method = "handleRemoveEntities", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
    private void killer560smod$onRemoveEntities(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
        BloodCampFeature.onRemoveEntities(packet, ((ClientPacketListener) (Object) this).getLevel());
    }
}
