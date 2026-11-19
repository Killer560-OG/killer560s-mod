package com.killer560.hub.dungeonqol.mixin;

import com.killer560.hub.dungeonqol.IcedMobsFeature;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Iced Mob Boxes reads an entity's head slot once vanilla applied the server's equipment (RETURN: only the client-thread
 *  pass gets there, the netty pass throws out of ensureRunningOnSameThread first). javap-checked on 26.3 and 26.1.2
 *  (handleSetEquipment(ClientboundSetEquipmentPacket), getEntity()I). Guarded: a throw here would disconnect him. */
@Mixin(ClientPacketListener.class)
public abstract class DungeonQolEquipmentMixin {

    @Inject(method = "handleSetEquipment", at = @At("RETURN"))
    private void killer560smod$icedEquipment(ClientboundSetEquipmentPacket packet, CallbackInfo ci) {
        try {
            var level = ((ClientPacketListener) (Object) this).getLevel();
            Entity e = level == null ? null : level.getEntity(packet.getEntity());
            if (e != null) {
                IcedMobsFeature.onEquipment(e);
            }
        } catch (RuntimeException ex) {
            ModLog.get("killer560smod-icedmobs").error("[IcedMobs] equipment hook threw", ex);
        }
    }
}
