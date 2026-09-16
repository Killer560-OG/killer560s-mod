package com.killer560.hub.witherdragons.mixin;

import com.killer560.hub.witherdragons.WitherDragonsPackets;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Read-only packet observation for M7 Phase 5 (Wither Dragons / King Relics). Every target was checked with javap
 *  against the 26.1.2 merged jar: each handler starts with {@code PacketUtils.ensureRunningOnSameThread}, which
 *  throws on the netty thread, so hooks placed after it (or at RETURN) only ever run on the client thread - same
 *  reasoning as DungeonAlertsPacketMixin. Add-entity / entity-data hooks sit at RETURN so the entity exists and its
 *  synched health is already applied. Never cancels. {@code require = 0}. */
@Mixin(ClientPacketListener.class)
public abstract class WitherDragonsPacketMixin {

    private static final String ENSURE_SAME_THREAD =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleParticleEvent", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER), require = 0)
    private void killer560smod$witherDragons$onParticles(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        WitherDragonsPackets.onParticles(packet);
    }

    @Inject(method = "handleSetEquipment", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER), require = 0)
    private void killer560smod$witherDragons$onEquipment(ClientboundSetEquipmentPacket packet, CallbackInfo ci) {
        WitherDragonsPackets.onEquipment(packet, ((ClientPacketListener) (Object) this).getLevel());
    }

    @Inject(method = "handleSoundEvent", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER), require = 0)
    private void killer560smod$witherDragons$onSound(ClientboundSoundPacket packet, CallbackInfo ci) {
        WitherDragonsPackets.onSound(packet);
    }

    @Inject(method = "handleTabListCustomisation", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER), require = 0)
    private void killer560smod$witherDragons$onTabList(ClientboundTabListPacket packet, CallbackInfo ci) {
        WitherDragonsPackets.onTabList(packet);
    }

    @Inject(method = "handleAddEntity", at = @At("RETURN"), require = 0)
    private void killer560smod$witherDragons$onAddEntity(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        WitherDragonsPackets.onAddEntity(packet, ((ClientPacketListener) (Object) this).getLevel());
    }

    @Inject(method = "handleSetEntityData", at = @At("RETURN"), require = 0)
    private void killer560smod$witherDragons$onEntityData(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
        WitherDragonsPackets.onEntityData(packet, ((ClientPacketListener) (Object) this).getLevel());
    }
}
