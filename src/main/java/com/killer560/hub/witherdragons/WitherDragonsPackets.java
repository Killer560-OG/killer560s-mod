package com.killer560.hub.witherdragons;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;

/** Public bridge from {@code WitherDragonsPacketMixin} (client thread) into the package. Every call is guarded so
 *  a feature bug can never break vanilla packet handling; Skyblock Only is honoured through each feature's
 *  gated config getters. */
public final class WitherDragonsPackets {

    private WitherDragonsPackets() {
    }

    public static void onParticles(ClientboundLevelParticlesPacket packet) {
        guard("particles", () -> WitherDragonsFeature.onParticles(packet));
    }

    public static void onEquipment(ClientboundSetEquipmentPacket packet, ClientLevel level) {
        guard("equipment/spray", () -> WitherDragonsFeature.onEquipment(packet, level));
        guard("equipment/relic", () -> KingRelicsFeature.onEquipment(packet));
    }

    public static void onSound(ClientboundSoundPacket packet) {
        guard("sound", () -> WitherDragonsFeature.onSound(packet));
    }

    public static void onTabList(ClientboundTabListPacket packet) {
        guard("tablist", () -> P5State.onTabFooter(packet.footer()));
        guard("tablist/blessings", () -> com.killer560.hub.blessings.BlessingTracker.onTabFooter(packet.footer()));
    }

    public static void onAddEntity(ClientboundAddEntityPacket packet, ClientLevel level) {
        if (level == null) {
            return;
        }
        guard("addEntity", () -> WitherDragonsFeature.onAddEntity(level.getEntity(packet.getId())));
    }

    public static void onEntityData(ClientboundSetEntityDataPacket packet, ClientLevel level) {
        if (level == null) {
            return;
        }
        guard("entityData", () -> WitherDragonsFeature.onEntityData(level.getEntity(packet.id())));
    }

    public static void onPing(int id) {
        guard("ping", () -> ServerTickClock.onPing(id));
    }

    private static void guard(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            WitherDragonsFeature.LOGGER.error("[WitherDragons] {} handler threw", what, e);
        }
    }
}
