package com.killer560.hub.proxy.mixin;

import com.killer560.hub.proxy.config.ProxyConfig;
import io.netty.channel.Channel;
import io.netty.handler.proxy.ProxyHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects a SOCKS {@link ProxyHandler} at the head of the client's outbound
 * channel pipeline, so Minecraft's server connection is tunnelled through the
 * configured proxy. The handler performs the SOCKS handshake first, then all
 * subsequent traffic flows through the proxy to the real destination.
 *
 * <p>Targets the anonymous {@code ChannelInitializer} created inside
 * {@code net.minecraft.network.Connection#connect(...)}. The trailing
 * {@code $1} is the anonymous-class ordinal - if {@code Connection} ever
 * declares another anonymous class earlier in the file, this needs to become
 * {@code $2} (etc.), so re-verify against decompiled sources if it stops
 * finding its target after a Minecraft update.
 *
 * <p>{@code remap = false} is required because {@code initChannel} is a Netty
 * method, not a Minecraft method, so it must not be run through the mapping
 * service.
 */
@Mixin(targets = "net.minecraft.network.Connection$1")
public class ChannelInitializerMixin {

    @Inject(method = "initChannel", at = @At("HEAD"), remap = false)
    private void killer560smod$injectProxy(Channel channel, CallbackInfo ci) {
        ProxyConfig config = ProxyConfig.getInstance();
        if (!config.isEnabled()) {
            return;
        }
        ProxyHandler handler = config.createHandler();
        if (handler == null) {
            return;
        }
        // Insert before Minecraft's own handlers so the SOCKS handshake runs first.
        channel.pipeline().addFirst("killer560smod:socks", handler);
    }
}
