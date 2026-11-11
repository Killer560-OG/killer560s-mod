package com.killer560.hub.roomsim;

import com.killer560.hub.relay.RelayCalls;
import com.killer560.hub.relay.RelayEndpoint;
import com.killer560.hub.util.ModNet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The co-op sim's relay tunnel (relay src/simtunnel.ts), for a room no guest can reach directly (UPnP failed, double NAT,
 * a firewall). The host keeps one standing socket while its room is listed; each guest opens its own with the ticket nonce
 * of its join answer, listens on a loopback port and points Minecraft at it. The relay only carries bytes: Minecraft's own
 * login (Mojang session + encryption) runs end to end, and the host still admits only ticketed guests.
 * Wire: host side [type][conn u16][payload], guest side [type][payload]; types DATA, OPEN (to the host), CLOSE, ACK (u32
 * bytes the guest took since its last ACK). The host stops reading a connection while WINDOW bytes are unacknowledged.
 * Every thread is a daemon of its own; nothing here runs on the render thread.
 */
final class SimTunnel {
    static final int DATA = 0, OPEN = 1, CLOSE = 2, ACK = 3;
    private static final int CHUNK = 32 * 1024, WINDOW = 1 << 20, ACK_EVERY = 256 * 1024, MAX_FRAME = 64 * 1024 + 3;
    private static final int MAX_CONNS = 8;
    private static final long MIN_BACKOFF_MS = 5_000, MAX_BACKOFF_MS = 60_000;
    private static HttpClient http;
    private static volatile Host host;

    private SimTunnel() {}

    // ---- host ----

    /** The host's standing socket for this room (opened, or kept if it is already for this room). Any thread. */
    static synchronized void hostOpen(String roomId, String secret, int port) {
        Host h = host;
        if (h != null && h.roomId.equals(roomId)) return;
        if (h != null) h.close();
        host = new Host(roomId, secret, port);
        host.connect();
    }

    /** Client tick while a room is open: reconnects a dropped host socket after its backoff. A few field reads. */
    static void hostTick() {
        Host h = host;
        if (h != null && h.ws == null && !h.connecting && System.currentTimeMillis() >= h.nextTryMs) h.connect();
    }

    static synchronized void hostClose() {
        if (host != null) host.close();
        host = null;
    }

    /** True while the host's tunnel socket is up (Session page line, testkit). */
    static boolean hostConnected() {
        Host h = host;
        return h != null && h.ws != null;
    }

    private static final class Host {
        final String roomId, secret;
        final int port;
        final Map<Integer, Conn> conns = new ConcurrentHashMap<>();
        final Sender sender = new Sender();
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
            open("host", roomId, secret, new Listener() {
                @Override
                void opened(WebSocket w) {
                    if (closed) {
                        w.abort();
                        return;
                    }
                    ws = w;
                    sender.ws = w;
                    backoffMs = MIN_BACKOFF_MS;
                    SimCoop.LOGGER.info("[SimTunnel] host socket up (room reachable through the relay)");
                }

                @Override
                void frame(byte[] f) {
                    if (f.length < 3 || closed) return;
                    int conn = ((f[1] & 0xff) << 8) | (f[2] & 0xff);
                    Conn c = conns.get(conn);
                    switch (f[0]) {
                        case OPEN -> Host.this.opened(conn);
                        case DATA -> {
                            if (c != null) c.write(f, 3);
                            else sender.send(SimTunnel.frame(CLOSE, conn, null, 0));
                        }
                        case CLOSE -> {
                            if (c != null) c.close(false);
                        }
                        case ACK -> {
                            if (c != null && f.length >= 7) c.acked(ByteBuffer.wrap(f, 3, 4).getInt());
                        }
                        default -> { }
                    }
                }

                @Override
                void ended(String why) {
                    dropped(why);
                }
            }).whenComplete((w, err) -> {
                connecting = false;
                if (err != null) dropped(reason(err));
            });
        }

        private void opened(int conn) {
            if (conns.size() >= MAX_CONNS) {
                sender.send(frame(CLOSE, conn, null, 0));
                return;
            }
            Conn c = new Conn(this, conn);
            conns.put(conn, c);
            daemon("k560-simtunnel-host-" + conn, c::pump);
        }

        void dropped(String why) {
            ws = null;
            sender.ws = null;
            for (Conn c : conns.values()) c.close(false);
            if (closed) return;
            nextTryMs = System.currentTimeMillis() + backoffMs;
            SimCoop.LOGGER.info("[SimTunnel] host socket down ({}); next try in {} s", why, backoffMs / 1000);
            backoffMs = Math.min(MAX_BACKOFF_MS, backoffMs * 2);
        }

        void close() {
            closed = true;
            WebSocket w = ws;
            ws = null;
            if (w != null) sender.close("room closed");
            for (Conn c : conns.values()) c.close(false);
        }
    }

    /** One guest's connection on the host: a loopback socket to the published sim server. */
    private static final class Conn {
        final Host host;
        final int id;
        private Socket socket;
        private OutputStream out;
        private long unacked;
        private volatile boolean done;

        Conn(Host host, int id) {
            this.host = host;
            this.id = id;
        }

        void pump() {
            try (Socket s = new Socket()) {
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), host.port), 5_000);
                synchronized (this) {
                    if (done) return;
                    socket = s;
                    out = s.getOutputStream();
                    notifyAll();
                }
                InputStream in = s.getInputStream();
                byte[] buf = new byte[CHUNK];
                for (int n; (n = in.read(buf)) >= 0; ) {
                    if (n == 0) continue;
                    synchronized (this) {
                        while (unacked > WINDOW && !done) wait(1_000);
                        if (done) return;
                        unacked += n;
                    }
                    host.sender.send(frame(DATA, id, buf, n));
                }
            } catch (IOException | InterruptedException e) {
                // the guest or the server went; told below
            } finally {
                close(true);
            }
        }

        /** Relay -> local server. Waits (bounded) for the local socket on the first frame. */
        void write(byte[] f, int from) {
            try {
                OutputStream o;
                synchronized (this) {
                    long until = System.currentTimeMillis() + 5_000;
                    while (out == null && !done && System.currentTimeMillis() < until) wait(250);
                    o = out;
                }
                if (o != null) o.write(f, from, f.length - from);
            } catch (IOException | InterruptedException e) {
                close(true);
            }
        }

        synchronized void acked(int n) {
            unacked = Math.max(0, unacked - Math.max(0, n));
            notifyAll();
        }

        void close(boolean tell) {
            Socket s;
            synchronized (this) {
                if (done) return;
                done = true;
                s = socket;
                notifyAll();
            }
            host.conns.remove(id, this);
            if (tell) host.sender.send(frame(CLOSE, id, null, 0));
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closing
                }
            }
        }
    }

    // ---- guest ----

    /**
     * Opens a guest tunnel for a room and answers the loopback address Minecraft should connect to, once the relay took the
     * socket; fails with a sentence for the player. Any thread; the accept and pipe run on daemon threads.
     */
    static CompletableFuture<String> guest(String roomId, String nonce) {
        ServerSocket server;
        try {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            server.setSoTimeout(60_000);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(new IOException("could not open a local port: " + e.getMessage()));
        }
        Guest g = new Guest(server);
        CompletableFuture<String> address = new CompletableFuture<>();
        open("guest", roomId, nonce, new Listener() {
            @Override
            void opened(WebSocket w) {
                g.sender.ws = w;
                g.ws = w;
                daemon("k560-simtunnel-guest", g::pump);
                address.complete("127.0.0.1:" + server.getLocalPort());
            }

            @Override
            void frame(byte[] f) {
                if (f.length == 0) return;
                if (f[0] == DATA) g.write(f);
                else if (f[0] == CLOSE) g.close(false);
            }

            @Override
            void ended(String why) {
                SimCoop.LOGGER.info("[SimTunnel] guest socket closed ({})", why);
                g.close(false);
            }
        }).whenComplete((w, err) -> {
            if (err == null) return;
            g.close(false);
            address.completeExceptionally(new IOException(reason(err)));
        });
        return address;
    }

    private static final class Guest {
        final ServerSocket server;
        final Sender sender = new Sender();
        volatile WebSocket ws;
        private Socket socket;
        private OutputStream out;
        private long received;
        private volatile boolean done;

        Guest(ServerSocket server) {
            this.server = server;
        }

        void pump() {
            try (ServerSocket ss = server; Socket s = ss.accept()) {
                s.setTcpNoDelay(true);
                synchronized (this) {
                    if (done) return;
                    socket = s;
                    out = s.getOutputStream();
                    notifyAll();
                }
                InputStream in = s.getInputStream();
                byte[] buf = new byte[CHUNK];
                for (int n; (n = in.read(buf)) >= 0; ) {
                    if (n > 0 && !sender.sendBounded(guestFrame(DATA, buf, n))) return;
                }
            } catch (IOException | InterruptedException e) {
                // Minecraft or the tunnel went
            } finally {
                close(true);
            }
        }

        void write(byte[] f) {
            int ack;
            try {
                OutputStream o;
                synchronized (this) {
                    long until = System.currentTimeMillis() + 5_000;
                    while (out == null && !done && System.currentTimeMillis() < until) wait(250);
                    o = out;
                    received += f.length - 1;
                    ack = received >= ACK_EVERY ? (int) received : 0;
                    if (ack > 0) received = 0;
                }
                if (o == null) return;
                o.write(f, 1, f.length - 1);
            } catch (IOException | InterruptedException e) {
                close(true);
                return;
            }
            if (ack > 0) sender.send(guestFrame(ACK, ByteBuffer.allocate(4).putInt(ack).array(), 4));
        }

        void close(boolean tell) {
            Socket s;
            synchronized (this) {
                if (done) return;
                done = true;
                s = socket;
                notifyAll();
            }
            WebSocket w = ws;
            if (w != null) {
                if (tell) sender.send(guestFrame(CLOSE, null, 0));
                sender.close("left");
            }
            try {
                server.close();
                if (s != null) s.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    // ---- shared ----

    /** One socket's outgoing frames, in order, one send outstanding at a time (WebSocket allows no more). */
    private static final class Sender {
        volatile WebSocket ws;
        private CompletableFuture<?> tail = CompletableFuture.completedFuture(null);
        private long pending;

        synchronized void send(ByteBuffer b) {
            WebSocket w = ws;
            if (w == null) return;
            int n = b.remaining();
            pending += n;
            tail = tail.handle((x, t) -> null).thenCompose(x -> w.sendBinary(b, true))
                    .whenComplete((x, t) -> done(n));
        }

        /** Closes the socket after every queued frame went. */
        synchronized void close(String why) {
            WebSocket w = ws;
            if (w == null) return;
            tail = tail.handle((x, t) -> null).thenCompose(x -> w.sendClose(WebSocket.NORMAL_CLOSURE, why)).exceptionally(t -> null);
        }

        private synchronized void done(int n) {
            pending -= n;
            notifyAll();
        }

        /** send, first waiting while a window's worth is still queued (a guest's upload). False once the socket is gone. */
        synchronized boolean sendBounded(ByteBuffer b) throws InterruptedException {
            while (pending > WINDOW && ws != null) wait(1_000);
            if (ws == null) return false;
            send(b);
            return true;
        }
    }

    private abstract static class Listener implements WebSocket.Listener {
        private byte[] buf = new byte[0];
        private int len;
        private boolean over;

        abstract void opened(WebSocket ws);

        abstract void frame(byte[] f);

        abstract void ended(String why);

        @Override
        public void onOpen(WebSocket ws) {
            opened(ws);
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            int n = data.remaining();
            if (len + n > MAX_FRAME) over = true;
            if (!over) {
                if (buf.length < len + n) buf = Arrays.copyOf(buf, Math.max(len + n, Math.min(MAX_FRAME, buf.length * 2 + 1024)));
                data.get(buf, len, n);
                len += n;
            }
            if (last) {
                if (!over) frame(Arrays.copyOf(buf, len));
                len = 0;
                over = false;
                if (buf.length > CHUNK * 2) buf = new byte[0];
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            ended(code + " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            ended(String.valueOf(error));
        }
    }

    private static CompletableFuture<WebSocket> open(String role, String roomId, String key, Listener listener) {
        String base = RelayEndpoint.normalise(RelayEndpoint.DEFAULT_BASE_URL).replaceFirst("^http", "ws");
        return RelayCalls.bearer().thenCompose(token -> {
            String url = ModNet.url("simrooms-relay", base + "/simtunnel?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                    + "&role=" + role);
            WebSocket.Builder b = client().newWebSocketBuilder().header("x-sim-room", roomId).header("x-sim-key", key);
            return ModNet.webSocket(b, URI.create(url), listener);
        });
    }

    /** The relay's refusal as words: 503 no host / no capacity, 403 no ticket, 404 the room is gone. */
    static String reason(Throwable err) {
        for (Throwable t = err; t != null; t = t.getCause()) {
            if (t instanceof WebSocketHandshakeException h) {
                return switch (h.getResponse().statusCode()) {
                    case 403 -> "the relay has no ticket for you (try Join again)";
                    case 404 -> "the room is gone";
                    case 409 -> "the room is full";
                    case 503 -> "the owner's relay link is not up, or the relay is out of capacity for today";
                    default -> "relay answered " + h.getResponse().statusCode();
                };
            }
        }
        Throwable root = err;
        while (root.getCause() != null) root = root.getCause();
        return String.valueOf(root.getMessage() != null ? root.getMessage() : root);
    }

    private static ByteBuffer frame(int type, int conn, byte[] payload, int n) {
        ByteBuffer b = ByteBuffer.allocate(3 + n).put((byte) type).putShort((short) conn);
        if (n > 0) b.put(payload, 0, n);
        return b.flip();
    }

    private static ByteBuffer guestFrame(int type, byte[] payload, int n) {
        ByteBuffer b = ByteBuffer.allocate(1 + n).put((byte) type);
        if (n > 0) b.put(payload, 0, n);
        return b.flip();
    }

    private static void daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private static synchronized HttpClient client() {
        if (http == null) http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return http;
    }
}
