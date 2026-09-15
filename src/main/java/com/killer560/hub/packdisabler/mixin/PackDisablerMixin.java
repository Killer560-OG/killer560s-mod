package com.killer560.hub.packdisabler.mixin;

import com.killer560.hub.packdisabler.PackDisablerFeature;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Verified with javap (26.1.2): {@code handleResourcePackPush} first calls
 * {@code PacketUtils.ensureRunningOnSameThread(Packet, PacketListener, PacketProcessor)} (which re-queues the packet
 * on the client thread and throws on the netty thread), then parses the URL and either prompts or calls
 * {@code DownloadedPackSource.pushPack}. Injecting right AFTER that call means this only ever runs on the client
 * thread, once per push. {@code require = 0}: a wrong target just leaves vanilla behaviour in place.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class PackDisablerMixin {

    // The listener's own server entry (verified: protected final ServerData serverData). Minecraft#getCurrentServer()
    // reads it through player.connection, which is null during the configuration phase (first login / proxy server
    // switch) - exactly when Hypixel pushes its pack - so the "hypixel.net only" check must read it here instead.
    @Shadow @Final protected ServerData serverData;

    @Inject(method = "handleResourcePackPush(Lnet/minecraft/network/protocol/common/ClientboundResourcePackPushPacket;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
                    shift = At.Shift.AFTER),
            cancellable = true, require = 0)
    private void killer560smod$packDisablerOnPush(ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
        if (PackDisablerFeature.onResourcePackPush((ClientCommonPacketListenerImpl) (Object) this, packet, serverData)) {
            ci.cancel();
        }
    }
}
