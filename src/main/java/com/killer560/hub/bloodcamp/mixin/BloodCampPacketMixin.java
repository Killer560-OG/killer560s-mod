package com.killer560.hub.bloodcamp.mixin;

import com.killer560.hub.bloodcamp.BloodCampFeature;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.world.entity.Entity;
import com.killer560.hub.util.ModLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
 *  main thread (the network-thread pass throws before reaching it).
 *  <p>
 *  Real bug found and fixed (2026-09-20): an exception thrown out of any of these hooks does not merely get
 *  logged - {@code ClientCommonPacketListenerImpl.onPacketError} disconnects the client outright with
 *  {@code disconnect.packetError} ("Network Protocol Error"). One NPE in
 *  {@link BloodCampFeature#onSetEquipment} was kicking killer560 off p3sim within seconds of joining. Each
 *  call is now guarded the same way {@code WitherDragonsPackets}/{@code DungeonAlertsPackets} already are, so
 *  a feature bug can never take the connection down with it. */
@Mixin(ClientPacketListener.class)
public abstract class BloodCampPacketMixin {

    private static final String ENSURE_SAME_THREAD =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleSetEquipment", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
    private void killer560smod$onSetEquipment(ClientboundSetEquipmentPacket packet, CallbackInfo ci) {
        try {
            BloodCampFeature.onSetEquipment(packet, ((ClientPacketListener) (Object) this).getLevel());
        } catch (RuntimeException e) {
            killer560smod$bloodCampHandlerThrew("setEquipment", e);
        }
    }

    @Inject(method = "handleMoveEntity", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
    private void killer560smod$onMoveEntity(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
        try {
            BloodCampFeature.onMoveEntity(packet, ((ClientPacketListener) (Object) this).getLevel());
        } catch (RuntimeException e) {
            killer560smod$bloodCampHandlerThrew("moveEntity", e);
        }
    }

    /** A blood mob's skull stand moved by an absolute position (doc-blood-1101: a trip whose first movement came this way
     *  was never tracked). Same hook point as the move packet: after the thread hand-off, before vanilla moves it. Target
     *  javap-checked on 26.3 and 26.2: both handlers start with ensureRunningOnSameThread. */
    @Inject(method = "handleEntityPositionSync", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
    private void killer560smod$onPositionSync(ClientboundEntityPositionSyncPacket packet, CallbackInfo ci) {
        try {
            BloodCampFeature.onAbsoluteMove(com.killer560.hub.compat.McPackets.positionSyncId(packet),
                    com.killer560.hub.compat.McPackets.positionSyncPos(packet), ((ClientPacketListener) (Object) this).getLevel(),
                    "position sync");
        } catch (RuntimeException e) {
            killer560smod$bloodCampHandlerThrew("positionSync", e);
        }
    }

    @Inject(method = "handleTeleportEntity", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
    private void killer560smod$onTeleportEntity(ClientboundTeleportEntityPacket packet, CallbackInfo ci) {
        try {
            // A teleport relative on any axis is a delta, not a place: left to the move model's next packet.
            if (packet.relatives().contains(net.minecraft.world.entity.Relative.X)
                    || packet.relatives().contains(net.minecraft.world.entity.Relative.Y)
                    || packet.relatives().contains(net.minecraft.world.entity.Relative.Z)) {
                return;
            }
            BloodCampFeature.onAbsoluteMove(packet.id(), packet.change().position(),
                    ((ClientPacketListener) (Object) this).getLevel(), "teleport");
        } catch (RuntimeException e) {
            killer560smod$bloodCampHandlerThrew("teleportEntity", e);
        }
    }

    @Inject(method = "handleRemoveEntities", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
    private void killer560smod$onRemoveEntities(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
        try {
            BloodCampFeature.onRemoveEntities(packet, ((ClientPacketListener) (Object) this).getLevel());
        } catch (RuntimeException e) {
            killer560smod$bloodCampHandlerThrew("removeEntities", e);
        }
    }

    /** A death event (id 3) for a blood mob: a player-model NPC stays alive on the client after it (vanilla sets health 0
     *  only for a non-Player), see {@code BloodCampFeature.DEAD_IDS}. Same hook point as the others; javap-checked on 26.3
     *  and 26.2: handleEntityEvent starts with ensureRunningOnSameThread. */
    @Inject(method = "handleEntityEvent", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
    private void killer560smod$onEntityEvent(net.minecraft.network.protocol.game.ClientboundEntityEventPacket packet, CallbackInfo ci) {
        try {
            ClientPacketListener self = (ClientPacketListener) (Object) this;
            Entity entity = packet.getEntity(self.getLevel());
            if (entity != null) {
                BloodCampFeature.onEntityEvent(entity.getId(), packet.getEventId(), self.getLevel());
            }
        } catch (RuntimeException e) {
            killer560smod$bloodCampHandlerThrew("entityEvent", e);
        }
    }

    @Unique
    private static void killer560smod$bloodCampHandlerThrew(String what, RuntimeException e) {
        ModLog.get("killer560smod-bloodcamp").error("[BloodCamp] {} handler threw", what, e);
    }
}
