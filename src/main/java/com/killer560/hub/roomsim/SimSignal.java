package com.killer560.hub.roomsim;

import com.killer560.hub.relay.RelayCalls;
import com.killer560.hub.relay.RelayEndpoint;
import com.killer560.hub.util.ModNet;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * The co-op host's signal socket (relay src/simtunnel.ts, coopfree-1111): one standing socket while the room is listed, on
 * which the relay sends PUNCH frames ([4][conn 0][UTF-8 JSON {ip, port, key, delayMs}]) for a guest's TCP hole punch
 * ({@link SimPunch}). It carries no game bytes and the host never sends on it: co-op games never go through the relay
 * (killer560, 2026-11-11: "it has to work with something that doesn't go through that relay"), so a busy co-op day cannot
 * use up the relay the rest of the mod needs. Idle it hibernates on the relay and costs nothing. Every thread is a daemon;
 * nothing here runs on the render thread.
 */
final class SimSignal {
    static final int PUNCH = 4;
    private static final int MAX_FRAME = 4096;
    private static final long MIN_BACKOFF_MS = 5_000, MAX_BACKOFF_MS = 60_000;
    private static HttpClient http;
    private static volatile Host host;

    private SimSignal() {}

    /** The host's signal socket for this room (opened, or kept if it is already for this room). Any thread. */
    static synchronized void hostOpen(String roomId, String secret, int port) {
        Host h = host;
        if (h != null && h.roomId.equals(roomId)) return;
        if (h != null) h.close();
        host = new Host(roomId, secret, port);
        host.connect();
    }

    /** Client tick while a room is open: reconnects a dropped signal socket after its backoff. A few field reads. */
    static void hostTick() {
        Host h = host;
        if (h != null && h.ws == null && !h.connecting && !h.closed && System.currentTimeMillis() >= h.nextTryMs) h.connect();
    }

    static synchronized void hostClose() {
        if (host != null) host.close();
        host = null;
    }

    private static final class Host implements WebSocket.Listener {
        final String roomId, secret;
        final int port;
        private byte[] buf = new byte[0];
        volatile WebSocket ws;
        volatile boolean connecting, closed;
        volatile long nextTryMs;
        long backoffMs = MIN_BACKOFF_MS;

        Host(String roomId, String secret, int port) {
            this.roomId = roomId;
            this.secret = secret;
            this.port = port;
        }

        void connect() {
            connecting = true;
            String base = RelayEndpoint.normalise(RelayEndpoint.DEFAULT_BASE_URL).replaceFirst("^http", "ws");
            RelayCalls.bearer().thenCompose(token -> {
                String url = ModNet.url("simrooms-relay", base + "/simtunnel?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                        + "&role=host");
                WebSocket.Builder b = client().newWebSocketBuilder().header("x-sim-room", roomId).header("x-sim-key", secret);
                return ModNet.webSocket(b, URI.create(url), this);
            }).whenComplete((w, err) -> {
                connecting = false;
                if (err != null) dropped(reason(err));
            });
        }

        @Override
        public void onOpen(WebSocket w) {
            if (closed) {
                w.abort();
                return;
            }
            ws = w;
            backoffMs = MIN_BACKOFF_MS;
            SimCoop.LOGGER.info("[SimSignal] host signal socket up (guests can hole punch)");
            w.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket w, ByteBuffer data, boolean last) {
            int n = data.remaining();
            if (buf.length + n <= MAX_FRAME) {
                byte[] next = new byte[buf.length + n];
                System.arraycopy(buf, 0, next, 0, buf.length);
                data.get(next, buf.length, n);
                buf = next;
            }
            if (last) {
                byte[] f = buf;
                buf = new byte[0];
                if (f.length > 3 && f[0] == PUNCH && !closed) {
                    SimPunch.onHostFrame(new String(f, 3, f.length - 3, StandardCharsets.UTF_8), SimHost.punchPort(), port);
                }
            }
            w.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
            dropped(code + " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket w, Throwable error) {
            dropped(String.valueOf(error));
        }

        void dropped(String why) {
            ws = null;
            if (closed) return;
            nextTryMs = System.currentTimeMillis() + backoffMs;
            SimCoop.LOGGER.info("[SimSignal] host signal socket down ({}); next try in {} s", why, backoffMs / 1000);
            backoffMs = Math.min(MAX_BACKOFF_MS, backoffMs * 2);
        }

        void close() {
            closed = true;
            WebSocket w = ws;
            ws = null;
            if (w != null) w.sendClose(WebSocket.NORMAL_CLOSURE, "room closed");
        }
    }

    /** The relay's refusal as words. */
    static String reason(Throwable err) {
        for (Throwable t = err; t != null; t = t.getCause()) {
            if (t instanceof WebSocketHandshakeException h) {
                return switch (h.getResponse().statusCode()) {
                    case 404 -> "the room is gone";
                    case 429 -> "too many co-op calls, wait a minute";
                    case 503 -> "co-op rooms are closed for today on the relay";
                    default -> "relay answered " + h.getResponse().statusCode();
                };
            }
        }
        Throwable root = err;
        while (root.getCause() != null) root = root.getCause();
        return String.valueOf(root.getMessage() != null ? root.getMessage() : root);
    }

    static void daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private static synchronized HttpClient client() {
        if (http == null) http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return http;
    }
}
