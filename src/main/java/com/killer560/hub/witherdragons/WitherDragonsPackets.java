package com.killer560.hub.witherdragons;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Public bridge from {@code WitherDragonsPacketMixin} (client thread) into the package. Every call is guarded so
 *  a feature bug can never break vanilla packet handling; Skyblock Only is honored through each feature's
 *  gated config getters.
 *  <p>The handlers are passed as non-capturing method references with their arguments, not as capturing lambdas: these
 *  run for every particle, sound, equipment and ping packet in every world, and a capturing lambda is an object per
 *  packet (mixin FPS pass, 2026-10-15). What runs, and inside which guard, is unchanged. */
public final class WitherDragonsPackets {

    private WitherDragonsPackets() {
    }

    public static void onParticles(ClientboundLevelParticlesPacket packet) {
        guard("particles", WitherDragonsFeature::onParticles, packet);
    }

    public static void onEquipment(ClientboundSetEquipmentPacket packet, ClientLevel level) {
        guard("equipment/spray", WitherDragonsFeature::onEquipment, packet, level);
        guard("equipment/relic", KingRelicsFeature::onEquipment, packet);
    }

    public static void onSound(ClientboundSoundPacket packet) {
        guard("sound", WitherDragonsFeature::onSound, packet);
    }

    public static void onTabList(ClientboundTabListPacket packet) {
        guard("tablist", WitherDragonsPackets::p5TabFooter, packet);
        guard("tablist/blessings", WitherDragonsPackets::blessingsTabFooter, packet);
    }

    public static void onAddEntity(ClientboundAddEntityPacket packet, ClientLevel level) {
        // Both handlers return at once unless dragon tracking is on; asking first spares every entity packet in every
        // world an entity lookup (FPS sweep, 2026-10-07).
        if (level == null || !WitherDragonsFeature.trackingActive()) {
            return;
        }
        guard("addEntity", WitherDragonsPackets::addEntity, packet, level);
    }

    public static void onEntityData(ClientboundSetEntityDataPacket packet, ClientLevel level) {
        if (level == null || !WitherDragonsFeature.trackingActive()) {
            return;
        }
        guard("entityData", WitherDragonsPackets::entityData, packet, level);
    }

    public static void onPing(int id) {
        try {
            ServerTickClock.onPing(id);
        } catch (RuntimeException e) {
            WitherDragonsFeature.LOGGER.error("[WitherDragons] {} handler threw", "ping", e);
        }
    }

    private static void p5TabFooter(ClientboundTabListPacket packet) {
        // Only dragon priority reads the blessings P5State parses; skip the footer string and two regex scans otherwise.
        if (WitherDragonsFeature.trackingActive()) {
            P5State.onTabFooter(packet.footer());
        }
    }

    private static void blessingsTabFooter(ClientboundTabListPacket packet) {
        com.killer560.hub.blessings.BlessingTracker.onTabFooter(packet.footer());
    }

    private static void addEntity(ClientboundAddEntityPacket packet, ClientLevel level) {
        WitherDragonsFeature.onAddEntity(level.getEntity(packet.getId()));
    }

    private static void entityData(ClientboundSetEntityDataPacket packet, ClientLevel level) {
        WitherDragonsFeature.onEntityData(level.getEntity(packet.id()));
    }

    private static <T> void guard(String what, Consumer<T> action, T arg) {
        try {
            action.accept(arg);
        } catch (RuntimeException e) {
            WitherDragonsFeature.LOGGER.error("[WitherDragons] {} handler threw", what, e);
        }
    }

    private static <A, B> void guard(String what, BiConsumer<A, B> action, A a, B b) {
        try {
            action.accept(a, b);
        } catch (RuntimeException e) {
            WitherDragonsFeature.LOGGER.error("[WitherDragons] {} handler threw", what, e);
        }
    }
}
