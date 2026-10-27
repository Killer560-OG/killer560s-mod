package com.killer560.hub.blink.mixin;

import com.killer560.hub.blink.BlinkFeature;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The server packets that end or interrupt a Blink hold, each right after {@code PacketUtils.ensureRunningOnSameThread}
 * (render thread, before vanilla acts on the packet - so whatever vanilla sends in reply goes out after what was held):
 * {@code handleMovePlayer} (a teleport: release, honor it), {@code handleLogin} / {@code handleRespawn} (a new world: the
 * held moves are dropped), {@code handleConfigurationStart} (release everything). All four begin with that call on 26.1.2
 * and 26.2 (javap, 2026-10-25), the point {@code smoothtp/mixin/SmoothTpPositionMixin} uses. Nothing is changed or
 * canceled, and a throw is swallowed: an exception out of a packet handler disconnects the client.
 */
@Mixin(ClientPacketListener.class)
public abstract class BlinkPacketListenerMixin {

    @Unique
    private static final String ENSURE_SAME_THREAD =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
    private void killer560smod$blinkPosition(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        try {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || packet == null || packet.change() == null) {
                BlinkFeature.onServerPosition(null, null);
                return;
            }
            PositionMoveRotation target = PositionMoveRotation.calculateAbsolute(
                    PositionMoveRotation.of(player), packet.change(), packet.relatives());
            BlinkFeature.onServerPosition(player.position(), target.position());
        } catch (Throwable t) {
            killer560smod$blink$threw(t);
        }
    }

    @Inject(method = "handleLogin", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
    private void killer560smod$blinkLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        try {
            BlinkFeature.onWorldChange();
        } catch (Throwable t) {
            killer560smod$blink$threw(t);
        }
    }

    @Inject(method = "handleRespawn", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
    private void killer560smod$blinkRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        try {
            BlinkFeature.onWorldChange();
        } catch (Throwable t) {
            killer560smod$blink$threw(t);
        }
    }

    @Inject(method = "handleConfigurationStart", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD,
            shift = At.Shift.AFTER))
    private void killer560smod$blinkConfiguration(ClientboundStartConfigurationPacket packet, CallbackInfo ci) {
        try {
            BlinkFeature.onConfigurationStart();
        } catch (Throwable t) {
            killer560smod$blink$threw(t);
        }
    }

    /** An explosion that pushes him (javap 26.1.2 and 26.2: same head; {@code playerKnockback()} is an Optional). */
    @Inject(method = "handleExplosion", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
    private void killer560smod$blinkExplosion(net.minecraft.network.protocol.game.ClientboundExplodePacket packet,
            CallbackInfo ci) {
        try {
            if (packet != null && packet.playerKnockback().isPresent()
                    && packet.playerKnockback().get().lengthSqr() > 0.01) {
                com.killer560.hub.blink.BlinkCharge.onServerPush("explosion " + packet.playerKnockback().get());
            }
        } catch (Throwable t) {
            killer560smod$blink$threw(t);
        }
    }

    /** A velocity for the local player (record accessors {@code id()}, as PathMotionPacketMixin reads them). */
    @Inject(method = "handleSetEntityMotion", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD,
            shift = At.Shift.AFTER))
    private void killer560smod$blinkMotion(net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket packet,
            CallbackInfo ci) {
        try {
            LocalPlayer player = Minecraft.getInstance().player;
            // A velocity under 0.1 blocks a tick (the server sends one after a teleport; gravity is 0.08) moves him no more than his own physics; releasing the answers for it only
            // cost the bank its cover (testkit 1027-blink-c-keep-delay on 26.2, ts1).
            if (player != null && packet != null && packet.id() == player.getId()
                    && packet.movement().lengthSqr() > 0.01) {
                com.killer560.hub.blink.BlinkCharge.onServerPush("velocity " + packet.movement());
            }
        } catch (Throwable t) {
            killer560smod$blink$threw(t);
        }
    }

    /**
     * A block near him changed in a way he could bump into (GrimAC moves its world only at the ping after the update, which
     * Charge holds a second): the answers go through that ping at once (BlinkCharge.onServerPush). Both versions: both
     * handlers begin with ensureRunningOnSameThread (javap 26.1.2 / 26.2); this runs before vanilla applies the change.
     */
    @Inject(method = "handleBlockUpdate", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER),
            cancellable = true)
    private void killer560smod$blinkBlock(net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket packet,
            CallbackInfo ci) {
        try {
            if (packet != null && !com.killer560.hub.blink.BlinkCharge.applyingDeferred()
                    && com.killer560.hub.blink.BlinkCharge.deferBlocks()
                    && killer560smod$blink$bumps(packet.getPos(), packet.getBlockState())) {
                ClientPacketListener self = (ClientPacketListener) (Object) this;
                com.killer560.hub.blink.BlinkCharge.deferBlockUpdate(() -> self.handleBlockUpdate(packet));
                ci.cancel();
            }
        } catch (Throwable t) {
            killer560smod$blink$threw(t);
        }
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD,
            shift = At.Shift.AFTER), cancellable = true)
    private void killer560smod$blinkBlocks(net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket packet,
            CallbackInfo ci) {
        try {
            if (packet == null || com.killer560.hub.blink.BlinkCharge.applyingDeferred()
                    || !com.killer560.hub.blink.BlinkCharge.deferBlocks()) {
                return;
            }
            boolean[] hit = {false};
            packet.runUpdates((pos, state) -> {
                if (!hit[0] && killer560smod$blink$bumps(pos, state)) {
                    hit[0] = true;
                }
            });
            if (hit[0]) {
                ClientPacketListener self = (ClientPacketListener) (Object) this;
                com.killer560.hub.blink.BlinkCharge.deferBlockUpdate(() -> self.handleChunkBlocksUpdate(packet));
                ci.cancel();
            }
        } catch (Throwable t) {
            killer560smod$blink$threw(t);
        }
    }

    /** Within 3 blocks of his box, and its collision changes from what the client has there now. */
    @Unique
    private static boolean killer560smod$blink$bumps(net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState next) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || pos == null || next == null || player.level() == null) {
            return false;
        }
        if (!player.getBoundingBox().inflate(3.0).intersects(new net.minecraft.world.phys.AABB(pos))) {
            return false;
        }
        var level = player.level();
        var now = level.getBlockState(pos);
        return now.getCollisionShape(level, pos).isEmpty() != next.getCollisionShape(level, pos).isEmpty();
    }

    @Unique
    private static void killer560smod$blink$threw(Throwable t) {
        ModLog.get("killer560smod-blink").error("[Blink] packet hook threw", t);
    }
}
