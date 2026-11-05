package com.killer560.hub.roomsim;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The sim player's velocity, server side (simitems-1105, killer560 2026-11-05: "make sure you cannot take knockback in
 * the sim"). Every packet the sim's integrated server sends him passes {@link #filter} ({@code mixin/SimNoKnockbackMixin},
 * on {@code ServerCommonPacketListenerImpl.send}): a motion packet for his own entity is dropped unless it is one of the
 * launches Hypixel gives on purpose, sent through {@link #push}; an explosion loses its player knockback; and the hurt
 * tilt packet (sent with no health lost, the boss lava's) is dropped. Real damage keeps its tilt: that is
 * {@code ClientboundDamageEventPacket}, which passes. Only on the sim's own server ({@link SimAbilities#simServer}).
 *
 * <p>The launches on purpose: Bonzo's Staff, the Jerry-chine Gun and Spring Boots ({@link SimLaunchItems}) and the F7
 * boss lava bounce ({@code bosssim.BossLava}).
 */
public final class SimLaunch {

    /** True only while {@link #push} sends, on the server thread. */
    private static boolean launching;
    private static int dropped;

    private SimLaunch() {
    }

    /** Knockback packets kept from the sim player since launch - read by the testkit. */
    public static int dropped() {
        return dropped;
    }

    /** Sets his motion on the server and sends it, as Hypixel's server does (the only motion the sim gives him). */
    public static void push(ServerPlayer sp, Vec3 v) {
        sp.setDeltaMovement(v);
        launching = true;
        try {
            sp.connection.send(new ClientboundSetEntityMotionPacket(sp.getId(), v));
        } finally {
            launching = false;
        }
    }

    /**
     * What goes out instead of {@code packet}: the same packet, a copy without knockback, or null for nothing. Server
     * thread; the type checks come first, so every other packet costs three instanceof tests.
     */
    public static Packet<?> filter(ServerPlayer sp, Packet<?> packet) {
        boolean motion = packet instanceof ClientboundSetEntityMotionPacket;
        boolean tilt = packet instanceof ClientboundHurtAnimationPacket;
        boolean boom = packet instanceof ClientboundExplodePacket;
        if (!(motion || tilt || boom) || launching || sp == null || !SimState.isActive() || !simServer(sp)) {
            return packet;
        }
        if (motion && ((ClientboundSetEntityMotionPacket) packet).id() != sp.getId()
                || tilt && ((ClientboundHurtAnimationPacket) packet).id() != sp.getId()) {
            return packet;
        }
        if (boom) {
            ClientboundExplodePacket e = (ClientboundExplodePacket) packet;
            if (e.playerKnockback().isEmpty()) {
                return packet;
            }
            dropped++;
            return com.killer560.hub.compat.McPackets.withoutKnockback(e);
        }
        dropped++;
        return null;
    }

    private static boolean simServer(ServerPlayer sp) {
        Minecraft client = Minecraft.getInstance();
        return client != null && client.getSingleplayerServer() == sp.level().getServer() && SimState.canAct(client);
    }
}
