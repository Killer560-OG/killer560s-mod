package com.killer560.hub.dungeonalerts.mixin;

import com.killer560.hub.dungeonalerts.DungeonAlertsPackets;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Read-only packet observation for the Dungeon Alerts pack. Every target was checked with javap against the
 *  26.1.2 merged jar: each handler starts with {@code PacketUtils.ensureRunningOnSameThread(packet, this,
 *  minecraft.packetProcessor())}, so (same reasoning as BloodCampPacketMixin) hooks sit right AFTER that call
 *  and only ever run on the main thread, before vanilla applies the packet. Never cancels. {@code require = 0}. */
@Mixin(ClientPacketListener.class)
public abstract class DungeonAlertsPacketMixin {

    private static final String ENSURE_SAME_THREAD =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleInitializeBorder", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER), require = 0)
    private void killer560smod$dungeonAlerts$onInitializeBorder(ClientboundInitializeBorderPacket packet, CallbackInfo ci) {
        DungeonAlertsPackets.onInitializeBorder(packet);
    }

    @Inject(method = "handleSoundEvent", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER), require = 0)
    private void killer560smod$dungeonAlerts$onSound(ClientboundSoundPacket packet, CallbackInfo ci) {
        DungeonAlertsPackets.onSound(packet);
    }

    @Inject(method = "handleTakeItemEntity", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER), require = 0)
    private void killer560smod$dungeonAlerts$onTakeItem(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
        DungeonAlertsPackets.onTakeItem(packet, ((ClientPacketListener) (Object) this).getLevel());
    }

    @Inject(method = "handleRemoveEntities", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER), require = 0)
    private void killer560smod$dungeonAlerts$onRemoveEntities(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
        DungeonAlertsPackets.onRemoveEntities(packet, ((ClientPacketListener) (Object) this).getLevel());
    }

    @Inject(method = "handleBlockUpdate", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER), require = 0)
    private void killer560smod$dungeonAlerts$onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        DungeonAlertsPackets.onBlockUpdate(packet);
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER), require = 0)
    private void killer560smod$dungeonAlerts$onSectionUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        DungeonAlertsPackets.onSectionUpdate(packet);
    }
}
