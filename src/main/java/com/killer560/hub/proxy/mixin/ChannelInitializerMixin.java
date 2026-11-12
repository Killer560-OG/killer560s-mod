package com.killer560.hub.proxy.mixin;

import com.killer560.hub.proxy.config.ProxyConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.proxy.ProxyHandler;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects a SOCKS {@link ProxyHandler} at the head of the client's outbound
 * channel pipeline, so Minecraft's server connection is tunneled through the
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
        // Universal proxy (shared by every instance) wins when it's on; otherwise this instance's own proxy.
        ProxyConfig config = ProxyConfig.effective();
        if (!config.isEnabled()) return;
        ProxyHandler handler = config.createHandler();
        if (handler == null) return;
        install(channel, handler);
    }

    public static void install(Channel channel, ProxyHandler handler) {
        // Insert before Minecraft's own handlers so the SOCKS handshake runs first.
        channel.pipeline().addFirst("killer560smod:socks", handler);
        // Loopback and LAN servers are connected to directly: the gate sees connect() first and drops the proxy handler.
        channel.pipeline().addAfter("killer560smod:socks", "killer560smod:socksgate", new ChannelOutboundHandlerAdapter() {
            @Override
            public void connect(ChannelHandlerContext ctx, SocketAddress remote, SocketAddress local, ChannelPromise promise) throws Exception {
                if (isLocal(remote)) ctx.pipeline().remove("killer560smod:socks");
                ctx.pipeline().remove(this);
                super.connect(ctx, remote, local, promise);
            }
        });
    }

    /** True for localhost, loopback, any-local, link-local and private (10/8, 172.16/12, 192.168/16, fc00::/7) addresses. */
    public static boolean isLocal(SocketAddress remote) {
        if (!(remote instanceof InetSocketAddress isa)) return false;
        InetAddress a = isa.getAddress();
        if (a == null) return "localhost".equalsIgnoreCase(isa.getHostString());
        return a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || (a.getAddress().length == 16 && (a.getAddress()[0] & 0xFE) == 0xFC);
    }
}
