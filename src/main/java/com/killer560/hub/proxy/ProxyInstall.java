package com.killer560.hub.proxy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.proxy.ProxyHandler;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/** Pipeline work for the Proxy Client; lives outside the mixin because a mixin class may not hold non-private static methods. */
public final class ProxyInstall {
    private ProxyInstall() {}

    /** The proxy handler is added only when connect() meets a public address: a ProxyHandler removed after it was added leaves its SOCKS codec in the pipeline and the join hangs. */
    public static void install(Channel channel, ProxyHandler handler) {
        channel.pipeline().addFirst("killer560smod:socksgate", new ChannelOutboundHandlerAdapter() {
            @Override
            public void connect(ChannelHandlerContext ctx, SocketAddress remote, SocketAddress local, ChannelPromise promise) {
                ctx.pipeline().remove(this);
                // Insert before Minecraft's own handlers so the SOCKS handshake runs first; the connect restarts from the tail.
                if (!isLocal(remote)) ctx.pipeline().addFirst("killer560smod:socks", handler);
                ctx.pipeline().connect(remote, local, promise);
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
