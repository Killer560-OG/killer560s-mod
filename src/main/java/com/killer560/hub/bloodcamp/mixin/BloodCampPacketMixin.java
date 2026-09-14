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
 *  for the real mechanic these feed. Injected at HEAD, before vanilla's own handler updates anything, so
 *  entity position reads inside {@link BloodCampFeature} still see the OLD (pre-packet) state, matching
 *  Noamm's own real reference (which reads the entity's current position and adds the packet's own delta
 *  on top, rather than reading an already-updated position). Never cancels anything. */
@Mixin(ClientPacketListener.class)
public abstract class BloodCampPacketMixin {

    @Inject(method = "handleSetEquipment", at = @At("HEAD"))
    private void killer560smod$onSetEquipment(ClientboundSetEquipmentPacket packet, CallbackInfo ci) {
        BloodCampFeature.onSetEquipment(packet, ((ClientPacketListener) (Object) this).getLevel());
    }

    @Inject(method = "handleMoveEntity", at = @At("HEAD"))
    private void killer560smod$onMoveEntity(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
        BloodCampFeature.onMoveEntity(packet, ((ClientPacketListener) (Object) this).getLevel());
    }

    @Inject(method = "handleRemoveEntities", at = @At("HEAD"))
    private void killer560smod$onRemoveEntities(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
        BloodCampFeature.onRemoveEntities(packet, ((ClientPacketListener) (Object) this).getLevel());
    }
}
