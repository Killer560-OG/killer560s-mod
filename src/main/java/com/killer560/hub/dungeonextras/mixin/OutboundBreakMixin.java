package com.killer560.hub.dungeonextras.mixin;

import com.killer560.hub.dungeonextras.ForeignBreakerProbe;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * READ-ONLY tap on everything this client sends, so {@link ForeignBreakerProbe} can describe another mod's breaker
 * aura (killer560, 2026-09-27: "put loggers into my mod to see how the rsa one acts").
 * <p>
 * Target verified with javap against the 26.1.2 mapped jar
 * ({@code .gradle/loom-cache/.../minecraft-merged-043a8b3edf-26.1.2.jar}):
 * {@code public void send(net.minecraft.network.protocol.Packet<?>)} on
 * {@code net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl}, which {@code ClientPacketListener}
 * inherits - so every {@code player.connection.send(...)} in the game and in every other mod passes through here.
 * That verification is the point: a hook written against a guessed signature fails SILENTLY under this project's
 * mixin config ({@code defaultRequire: 0}), and a probe that quietly counts nothing is worse than no probe, because
 * its zeroes read as findings. The same trap already cost a wrong conclusion about
 * {@code continueDestroyBlock} on 2026-09-24.
 * <p>
 * At HEAD, nothing is cancelled and no packet is touched or copied - it reads three getters and returns. Any throw
 * is swallowed: this sits on the send path of every packet the client produces, so a fault here must never be able
 * to take the connection down.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class OutboundBreakMixin {

    @Inject(method = "send", at = @At("HEAD"), require = 0)
    private void killer560smod$tapOutbound(Packet<?> packet, CallbackInfo ci) {
        try {
            if (!ForeignBreakerProbe.active()) {
                return;
            }
            if (packet instanceof ServerboundPlayerActionPacket action) {
                switch (action.getAction()) {
                    case START_DESTROY_BLOCK ->
                            ForeignBreakerProbe.onStartDestroy(action.getPos(), action.getDirection(),
                                    action.getSequence());
                    case ABORT_DESTROY_BLOCK -> ForeignBreakerProbe.onAbortDestroy();
                    case STOP_DESTROY_BLOCK -> ForeignBreakerProbe.onStopDestroy();
                    default -> {
                        // every other action (drops, offhand swap, STAB) is not a break - ignore it
                    }
                }
            } else if (packet instanceof ServerboundSwingPacket) {
                ForeignBreakerProbe.onSwing();
            } else if (packet instanceof ServerboundSetCarriedItemPacket) {
                ForeignBreakerProbe.onHotbarSwap();
            }
        } catch (Throwable t) {
            killer560smod$outbound$threw(t);
        }
    }

    @Unique
    private static void killer560smod$outbound$threw(Throwable t) {
        LoggerFactory.getLogger("killer560smod-dungeonextras")
                .error("[DungeonExtras] outbound packet probe threw - it is read-only, so the packet still went", t);
    }
}
