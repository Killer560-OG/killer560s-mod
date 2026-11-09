package com.killer560.hub.mobesp.mixin;

import com.killer560.hub.compat.McPackets;
import com.killer560.hub.mobesp.GhostMobs;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hide Ghost Mobs' packet clock ({@link GhostMobs}): every entity packet that proves the server still tracks the entity
 * for us. Each hook sits right after the thread hand-off (javap 26.3 and 26.2: all eight handlers start with
 * {@code ensureRunningOnSameThread}), so it runs once, on the client thread (see BloodCampPacketMixin for the HEAD bug).
 * {@link GhostMobs#heard} returns at once outside a dungeon; it never throws.
 */
@Mixin(ClientPacketListener.class)
public abstract class GhostMobPacketMixin {

    private static final String ENSURE = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread("
            + "Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;"
            + "Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleAddEntity", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER))
    private void killer560smod$ghostAdd(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        GhostMobs.heard(packet.getId());
    }

    @Inject(method = "handleMoveEntity", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER))
    private void killer560smod$ghostMove(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
        Entity e = packet.getEntity(((ClientPacketListener) (Object) this).getLevel());
        if (e != null) {
            GhostMobs.heard(e.getId());
        }
    }

    @Inject(method = "handleRotateMob", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER))
    private void killer560smod$ghostHead(ClientboundRotateHeadPacket packet, CallbackInfo ci) {
        Entity e = packet.getEntity(((ClientPacketListener) (Object) this).getLevel());
        if (e != null) {
            GhostMobs.heard(e.getId());
        }
    }

    @Inject(method = "handleTeleportEntity", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER))
    private void killer560smod$ghostTeleport(ClientboundTeleportEntityPacket packet, CallbackInfo ci) {
        GhostMobs.heard(packet.id());
    }

    @Inject(method = "handleEntityPositionSync", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER))
    private void killer560smod$ghostSync(ClientboundEntityPositionSyncPacket packet, CallbackInfo ci) {
        GhostMobs.heard(McPackets.positionSyncId(packet));
    }

    @Inject(method = "handleSetEntityData", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER))
    private void killer560smod$ghostData(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
        GhostMobs.heard(packet.id());
    }

    @Inject(method = "handleSetEntityMotion", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER))
    private void killer560smod$ghostMotion(ClientboundSetEntityMotionPacket packet, CallbackInfo ci) {
        GhostMobs.heard(packet.id());
    }

    @Inject(method = "handleRemoveEntities", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER))
    private void killer560smod$ghostRemove(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
        it.unimi.dsi.fastutil.ints.IntList ids = McPackets.removedEntityIds(packet);
        for (int i = 0; i < ids.size(); i++) {
            GhostMobs.removed(ids.getInt(i));
        }
    }
}
