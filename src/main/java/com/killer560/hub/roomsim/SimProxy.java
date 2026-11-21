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
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Co-op game transport (coopproxy-1120, killer560 2026-11-20: no player may learn another's IP by default; "essentially
 * free to run"; Share My IP as an opt-in fallback). The HOST runs a gateway on its own port (the one the router maps,
 * {@link SimUpnp}); the relay's probe asks it {@code K5PROBE1} and it answers {@code K5KEY <key>}. A GUEST reaches it
 * through the relay's plain Worker proxy (relay /simproxy: WebSocket in, TCP out, nobody sees the other's address), or,
 * only in a Share My IP room after the guest confirmed it, by a direct TCP connection. Vanilla on both sides only ever
 * talks to 127.0.0.1: the guest connects to a loopback port here and the gateway splices each session to the LAN port,
 * so every server-side check (ticket, password, Max, hello) is unchanged.
 *
 * <p>Between guest and gateway the bytes are framed so a dropped connection (the proxy's per-connection budget, a deploy,
 * a Wi-Fi blip) RESUMES without dropping the game: hello {@code K5G1 <ticket> <session> <received>\n}, answer
 * {@code K5OK <received>\n}, then frames {@code [type u8][length u16][payload]} (DATA, ACK with the u64 bytes received,
 * CLOSE). Each side keeps what the other has not acknowledged and sends it again from the count the other reports.
 * Every thread is a daemon; nothing here runs on the render thread.
 */
public final class SimProxy {
    static final int NET = 3;
    private static final int DATA = 1, ACK = 2, CLOSE = 3;
    private static final int MAX_DATA = 16 * 1024, MAX_UNACKED = 8 << 20, ACK_EVERY = 32 * 1024, MAX_SESSIONS = 16;
    private static final long ACK_MS = 500, RESUME_MS = 60_000, HELLO_MS = 8_000, ACCEPT_MS = 30_000;
    private static final Pattern HEX32 = Pattern.compile("[0-9a-f]{32}");
    private static final SecureRandom RNG = new SecureRandom();
    private static HttpClient http;

    /** Why the relay path did not open. FULL and RELAY_DOWN may fall back to a Share My IP room's direct path. */
    enum Fail { FULL, UNREACHABLE, UPDATE, TICKET, BUSY, RELAY_DOWN, OTHER }

    static final class ProxyException extends IOException {
        final Fail fail;

        ProxyException(Fail fail, String msg) {
            super(msg);
            this.fail = fail;
        }
    }

    private SimProxy() {
    }

    static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** What a guest is told when the relay path did not open. */
    static String text(Fail f, String room) {
        return switch (f) {
            case FULL -> "The co-op relay is full for this month.";
            case UNREACHABLE -> "Can't reach " + room + ": the host's router blocks connections from outside. The host can turn on"
                    + " UPnP or forward their co-op TCP port, or a friend can host.";
            case UPDATE -> "Update the mod to play co-op: this version is too old for the co-op relay.";
            case TICKET -> "The room did not accept this join; try joining again.";
            case BUSY -> "Too many co-op connections from your network right now.";
            case RELAY_DOWN -> "Could not reach the co-op relay.";
            case OTHER -> "Could not connect to " + room + ".";
        };
    }

    static boolean fallsBack(Fail f) { return f == Fail.FULL || f == Fail.RELAY_DOWN; }

    static Fail failOf(Throwable err) {
        for (Throwable t = err; t != null; t = t.getCause()) {
            if (t instanceof ProxyException p) return p.fail;
            if (t instanceof WebSocketHandshakeException h) {
                return switch (h.getResponse().statusCode()) {
                    case 503 -> Fail.FULL;
                    case 502, 409 -> Fail.UNREACHABLE;
                    case 426 -> Fail.UPDATE;
                    case 401, 403, 404 -> Fail.TICKET;
                    case 429 -> Fail.BUSY;
                    default -> Fail.OTHER;
                };
            }
        }
        return Fail.RELAY_DOWN;
    }

    private static void tell(String line) {
        com.killer560.hub.util.ModChat.send("Sim", com.killer560.hub.util.ModChat.text(line));
    }

    // ---- the framed stream (both sides) ----

    /** One side of a link (relay WebSocket, direct TCP, or the gateway's accepted socket). */
    interface Link {
        void send(byte[] frame) throws IOException;

        void close();
    }

    /** One game connection: the local vanilla socket on this side and the framed link to the other side. */
    static final class Stream {
        final String sid, nonce;
        final boolean guest;
        private final Object lock = new Object();
        private final ArrayDeque<byte[]> unacked = new ArrayDeque<>();
        private volatile Socket local;
        private volatile Link link;
        private volatile boolean dead;
        private long sentTotal, ackedTotal, recvTotal, ackedAtRecv, ackedAtMs;
        private byte[] pending = new byte[0];
        /** Frames each way and the start, for the messages-per-second measurement (docs/SIM-COOP.md). */
        volatile long framesOut, framesIn, resumes;
        final long startedMs = System.currentTimeMillis();
        /** Guest only: dials a new link after a drop. */
        volatile Redial redial;

        Stream(String sid, String nonce, boolean guest) {
            this.sid = sid;
            this.nonce = nonce;
            this.guest = guest;
        }

        long received() {
            synchronized (lock) {
                return recvTotal;
            }
        }

        /** Attaches a link whose peer has received {@code peerRecv} bytes, and resends everything after that. */
        void attach(Link l, long peerRecv) throws IOException {
            synchronized (lock) {
                if (dead) throw new IOException("stream ended");
                if (peerRecv < ackedTotal || peerRecv > sentTotal) throw new IOException("resume out of range");
                trim(peerRecv);
                link = l;
                for (byte[] chunk : unacked) sendLocked(frame(DATA, chunk, 0, chunk.length));
            }
        }

        void setLocal(Socket s) {
            local = s;
            daemon("k560-simproxy-local", this::pumpLocal);
        }

        /** Local vanilla bytes -> DATA frames (each read, with what is already waiting, is one frame). */
        private void pumpLocal() {
            byte[] buf = new byte[MAX_DATA];
            try {
                InputStream in = local.getInputStream();
                while (!dead) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    while (n < buf.length && in.available() > 0) {
                        int m = in.read(buf, n, Math.min(in.available(), buf.length - n));
                        if (m <= 0) break;
                        n += m;
                    }
                    byte[] chunk = java.util.Arrays.copyOf(buf, n);
                    synchronized (lock) {
                        unacked.addLast(chunk);
                        sentTotal += n;
                        if (sentTotal - ackedTotal > MAX_UNACKED) throw new IOException("the other side stopped reading");
                        sendLocked(frame(DATA, chunk, 0, n));
                    }
                }
                synchronized (lock) {
                    sendLocked(frame(CLOSE, new byte[0], 0, 0));
                }
            } catch (IOException e) {
                // the local side closed
            }
            end("local closed");
        }

        /** Bytes from the link (any split): frames are parsed and handled in order. */
        void onBytes(byte[] b, int off, int len) {
            if (dead || len <= 0) return;
            byte[] all = new byte[pending.length + len];
            System.arraycopy(pending, 0, all, 0, pending.length);
            System.arraycopy(b, off, all, pending.length, len);
            int p = 0;
            try {
                while (all.length - p >= 3) {
                    int type = all[p] & 0xff, n = ((all[p + 1] & 0xff) << 8) | (all[p + 2] & 0xff);
                    if (all.length - p - 3 < n) break;
                    handle(type, all, p + 3, n);
                    p += 3 + n;
                }
            } catch (IOException e) {
                end("local write failed");
                return;
            }
            pending = java.util.Arrays.copyOfRange(all, p, all.length);
        }

        private void handle(int type, byte[] b, int off, int n) throws IOException {
            framesIn++;
            switch (type) {
                case DATA -> {
                    Socket s = local;
                    if (s == null) return;
                    s.getOutputStream().write(b, off, n);
                    synchronized (lock) {
                        recvTotal += n;
                        long now = System.currentTimeMillis();
                        if (recvTotal - ackedAtRecv >= ACK_EVERY || now - ackedAtMs >= ACK_MS) {
                            ackedAtRecv = recvTotal;
                            ackedAtMs = now;
                            sendLocked(frame(ACK, ByteBuffer.allocate(8).putLong(recvTotal).array(), 0, 8));
                        }
                    }
                }
                case ACK -> {
                    if (n == 8) {
                        synchronized (lock) {
                            trim(ByteBuffer.wrap(b, off, 8).getLong());
                        }
                    }
                }
                case CLOSE -> end("the other side closed");
                default -> end("bad frame");
            }
        }

        private void trim(long upTo) {
            while (!unacked.isEmpty() && ackedTotal + unacked.peekFirst().length <= upTo) ackedTotal += unacked.removeFirst().length;
            if (upTo > ackedTotal && !unacked.isEmpty()) {
                byte[] head = unacked.removeFirst();
                int cut = (int) (upTo - ackedTotal);
                unacked.addFirst(java.util.Arrays.copyOfRange(head, cut, head.length));
                ackedTotal = upTo;
            }
        }

        private void sendLocked(byte[] f) {
            Link l = link;
            if (l == null) return;   // kept in unacked; resent on the next attach
            try {
                l.send(f);
                framesOut++;
            } catch (IOException e) {
                linkLost(l, 0);
            }
        }

        /** The link dropped: a guest dials again (unless the relay said the month is full), a host waits for that. */
        void linkLost(Link l, int code) {
            synchronized (lock) {
                if (link != l || dead) return;
                link = null;
            }
            l.close();
            if (code == 4009) {
                if (guest) tell(text(Fail.FULL, ""));
                end("the co-op relay is full for this month");
                return;
            }
            Redial r = redial;
            if (guest && r != null) daemon("k560-simproxy-resume", () -> resume(r));
            else if (!guest) daemon("k560-simproxy-wait", () -> {
                try {
                    Thread.sleep(RESUME_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                if (link == null) end("the guest did not come back");
            });
        }

        private void resume(Redial r) {
            long until = System.currentTimeMillis() + RESUME_MS, wait = 1_000;
            while (!dead && System.currentTimeMillis() < until) {
                try {
                    Thread.sleep(wait);
                    r.dial(this);
                    resumes++;
                    SimCoop.LOGGER.info("[SimProxy] connection resumed");
                    tell("Co-op connection resumed.");
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException | RuntimeException e) {
                    if (failOf(e) == Fail.FULL) {
                        tell(text(Fail.FULL, ""));
                        break;
                    }
                    wait = Math.min(8_000, wait * 2);
                }
            }
            end("could not resume");
        }

        void end(String why) {
            Link l;
            synchronized (lock) {
                if (dead) return;
                dead = true;
                l = link;
                link = null;
                unacked.clear();
            }
            if (l != null) l.close();
            close(local);
            SESSIONS.remove(sid, this);
            long secs = Math.max(1, (System.currentTimeMillis() - startedMs) / 1000);
            SimCoop.LOGGER.info("[SimProxy] {} session ended ({}): {} frames out, {} in over {} s ({} / s), {} resumes",
                    guest ? "guest" : "host", why, framesOut, framesIn, secs, (framesOut + framesIn) / secs, resumes);
            lastStats = new long[]{framesOut, framesIn, secs, resumes};
        }

        boolean dead() { return dead; }
    }

    /** Guest: opens a new link for a stream (hello with its received count) and attaches it. */
    interface Redial {
        void dial(Stream s) throws IOException;
    }

    static byte[] frame(int type, byte[] b, int off, int n) {
        byte[] f = new byte[3 + n];
        f[0] = (byte) type;
        f[1] = (byte) (n >>> 8);
        f[2] = (byte) n;
        System.arraycopy(b, off, f, 3, n);
        return f;
    }

    // ---- guest ----

    /** For tests: {frames out, frames in, seconds, resumes} of the last ended session. */
    static volatile long[] lastStats;
    /** For tests: the guest's live stream. */
    static volatile Stream guestStream;

    /** For tests: {frames out, frames in, seconds, resumes} of the guest's live stream, else of the last ended session. */
    public static long[] statsForTest() {
        Stream s = guestStream;
        return s == null || s.dead() ? lastStats : new long[]{s.framesOut, s.framesIn, 0, s.resumes};
    }

    /** For tests: guest sessions the host gateway holds. */
    public static int hostSessionsForTest() { return SESSIONS.size(); }

    /**
     * Opens the link first (so a refusal is known before vanilla connects), then a loopback port for vanilla, and answers
     * "127.0.0.1:port". The future fails with a {@link ProxyException} or the relay's handshake error.
     */
    static CompletableFuture<String> guest(String nonce, Redial dial) {
        CompletableFuture<String> out = new CompletableFuture<>();
        daemon("k560-simproxy-guest", () -> {
            Stream s = new Stream(hex(16), nonce, true);
            try {
                dial.dial(s);
                s.redial = dial;
                guestStream = s;
                ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                out.complete("127.0.0.1:" + ss.getLocalPort());
                try (ss) {
                    ss.setSoTimeout((int) ACCEPT_MS);
                    Socket mc = ss.accept();
                    mc.setTcpNoDelay(true);
                    s.setLocal(mc);
                }
            } catch (Throwable e) {
                s.end("join failed");
                out.completeExceptionally(e);
            }
        });
        return out;
    }

    /** The relay path: one WebSocket to /simproxy per link (room id and ticket in headers, never the URL). */
    static Redial relay(String roomId) {
        return s -> {
            WsLink l = new WsLink(s);
            String base = RelayEndpoint.normalise(RelayEndpoint.DEFAULT_BASE_URL).replaceFirst("^http", "ws");
            try {
                String token = RelayCalls.bearer().get(15, TimeUnit.SECONDS);
                String url = ModNet.url("simrooms-relay", base + "/simproxy?net=" + NET + "&token="
                        + URLEncoder.encode(token, StandardCharsets.UTF_8));
                WebSocket.Builder b = client().newWebSocketBuilder().header("x-sim-room", roomId).header("x-sim-key", s.nonce);
                l.ws = ModNet.webSocket(b, URI.create(url), l).get(20, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                throw e.getCause() instanceof WebSocketHandshakeException h
                        ? new ProxyException(failOf(h), "relay answered " + h.getResponse().statusCode())
                        : new ProxyException(Fail.RELAY_DOWN, String.valueOf(e.getCause()));
            } catch (InterruptedException | java.util.concurrent.TimeoutException e) {
                throw new ProxyException(Fail.RELAY_DOWN, e.toString());
            }
            l.hello(s);
        };
    }

    /** The Share My IP path (only after the guest confirmed): a plain TCP connection to the host's gateway. */
    static Redial direct(String host, int port) {
        return s -> {
            Socket sock = new Socket();
            try {
                sock.connect(new InetSocketAddress(host, port), 5_000);
                sock.setTcpNoDelay(true);
            } catch (IOException e) {
                close(sock);
                throw new ProxyException(Fail.UNREACHABLE, e.toString());
            }
            SocketLink l = new SocketLink(sock);
            l.write(("K5G1 " + s.nonce + " " + s.sid + " " + s.received() + "\n").getBytes(StandardCharsets.US_ASCII));
            sock.setSoTimeout((int) HELLO_MS);
            String line = readLine(sock.getInputStream());
            sock.setSoTimeout(0);
            s.attach(l, okCount(line));
            l.pump(s);
        };
    }

    private static long okCount(String line) throws ProxyException {
        if (line == null || !line.startsWith("K5OK ")) throw new ProxyException(Fail.TICKET, "gateway refused: " + line);
        try {
            return Long.parseLong(line.substring(5).trim());
        } catch (NumberFormatException e) {
            throw new ProxyException(Fail.OTHER, "bad gateway answer");
        }
    }

    /** The relay WebSocket as a link; the first bytes back are the gateway's hello answer. */
    private static final class WsLink implements Link, WebSocket.Listener {
        private final Stream s;
        volatile WebSocket ws;
        private final CompletableFuture<Long> ok = new CompletableFuture<>();
        private final StringBuilder line = new StringBuilder();
        private volatile boolean attached, closed;

        WsLink(Stream s) {
            this.s = s;
        }

        void hello(Stream st) throws IOException {
            send(("K5G1 " + st.nonce + " " + st.sid + " " + st.received() + "\n").getBytes(StandardCharsets.US_ASCII));
            long peer;
            try {
                peer = ok.get(HELLO_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                close();
                throw e.getCause() instanceof ProxyException p ? p : new ProxyException(Fail.UNREACHABLE, "no answer from the host");
            }
            st.attach(this, peer);
            attached = true;
        }

        @Override
        public synchronized void send(byte[] frame) throws IOException {
            WebSocket w = ws;
            if (w == null || closed) throw new IOException("link closed");
            try {
                w.sendBinary(ByteBuffer.wrap(frame), true).get(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IOException(e);
            }
        }

        @Override
        public void close() {
            closed = true;
            WebSocket w = ws;
            if (w != null) w.abort();
        }

        @Override
        public void onOpen(WebSocket w) {
            ws = w;
            w.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket w, ByteBuffer data, boolean last) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            int off = 0;
            if (!ok.isDone()) {
                while (off < b.length && !ok.isDone()) {
                    char c = (char) b[off++];
                    if (c == '\n') {
                        try {
                            ok.complete(okCount(line.toString()));
                        } catch (ProxyException e) {
                            ok.completeExceptionally(e);
                        }
                    } else if (line.length() < 200) line.append(c);
                }
            }
            if (off < b.length) {
                // Frames that arrive before attach finishes wait for it (attach resends nothing the peer must answer first).
                while (!attached && !closed) Thread.onSpinWait();
                s.onBytes(b, off, b.length - off);
            }
            w.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
            ok.completeExceptionally(new ProxyException(code == 4009 ? Fail.FULL : Fail.UNREACHABLE, reason));
            if (attached) s.linkLost(this, code);
            return null;
        }

        @Override
        public void onError(WebSocket w, Throwable error) {
            ok.completeExceptionally(new ProxyException(Fail.RELAY_DOWN, String.valueOf(error)));
            if (attached) s.linkLost(this, 0);
        }
    }

    /** A TCP socket as a link (guest direct path, and the gateway's accepted connection). */
    private static final class SocketLink implements Link {
        private final Socket sock;

        SocketLink(Socket sock) {
            this.sock = sock;
        }

        synchronized void write(byte[] b) throws IOException {
            OutputStream o = sock.getOutputStream();
            o.write(b);
            o.flush();
        }

        @Override
        public void send(byte[] frame) throws IOException {
            write(frame);
        }

        @Override
        public void close() {
            SimProxy.close(sock);
        }

        void pump(Stream s) {
            daemon("k560-simproxy-link", () -> {
                byte[] buf = new byte[MAX_DATA + 3];
                try {
                    InputStream in = sock.getInputStream();
                    for (int n; (n = in.read(buf)) >= 0; ) s.onBytes(buf, 0, n);
                } catch (IOException ignored) {
                    // dropped
                }
                s.linkLost(this, 0);
            });
        }
    }

    // ---- host gateway ----

    private static final Map<String, Stream> SESSIONS = new ConcurrentHashMap<>();
    private static volatile ServerSocket gateway;
    private static volatile String probeKey;
    private static volatile int lanPort;

    /** Starts the host's gateway (or keeps it) for the LAN port; answers its port. The probe key is {@link #probeKey()}. */
    static synchronized int hostStart(int lan) throws IOException {
        lanPort = lan;
        if (gateway != null && !gateway.isClosed()) return gateway.getLocalPort();
        probeKey = hex(16);
        ServerSocket ss = new ServerSocket(0);
        gateway = ss;
        daemon("k560-simproxy-gateway", () -> {
            while (!ss.isClosed()) {
                try {
                    Socket c = ss.accept();
                    daemon("k560-simproxy-accept", () -> accepted(c));
                } catch (IOException e) {
                    break;
                }
            }
        });
        SimCoop.LOGGER.info("[SimProxy] gateway open on port {}", ss.getLocalPort());
        return ss.getLocalPort();
    }

    static String probeKey() { return probeKey; }

    static synchronized void hostStop() {
        close(gateway);
        gateway = null;
        for (Stream s : SESSIONS.values()) s.end("room closed");
        SESSIONS.clear();
    }

    private static void accepted(Socket c) {
        try {
            c.setSoTimeout((int) HELLO_MS);
            c.setTcpNoDelay(true);
            String line = readLine(c.getInputStream());
            if ("K5PROBE1".equals(line)) {
                SocketLink l = new SocketLink(c);
                l.write(("K5KEY " + probeKey + "\n").getBytes(StandardCharsets.US_ASCII));
                close(c);
                return;
            }
            String[] p = line == null ? new String[0] : line.split(" ");
            if (p.length != 4 || !"K5G1".equals(p[0]) || !HEX32.matcher(p[1]).matches() || !HEX32.matcher(p[2]).matches()
                    || !p[3].matches("\\d{1,15}")) {
                close(c);
                return;
            }
            c.setSoTimeout(0);
            SocketLink l = new SocketLink(c);
            Stream s = SESSIONS.get(p[2]);
            if (s != null && !s.nonce.equals(p[1])) {
                close(c);
                return;
            }
            if (s == null || s.dead()) {
                if (SESSIONS.size() >= MAX_SESSIONS) {
                    close(c);
                    return;
                }
                s = new Stream(p[2], p[1], false);
                Socket lan = new Socket();
                lan.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), lanPort), 5_000);
                lan.setTcpNoDelay(true);
                SESSIONS.put(s.sid, s);
                s.setLocal(lan);
                SimCoop.LOGGER.info("[SimProxy] guest session opened");
            } else {
                s.resumes++;
                SimCoop.LOGGER.info("[SimProxy] guest session resumed");
            }
            l.write(("K5OK " + s.received() + "\n").getBytes(StandardCharsets.US_ASCII));
            s.attach(l, Long.parseLong(p[3]));
            l.pump(s);
        } catch (IOException | RuntimeException e) {
            close(c);
        }
    }

    // ---- small helpers ----

    /** One ASCII line (at most 200 bytes), read a byte at a time so nothing after it is consumed. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int b; (b = in.read()) >= 0; ) {
            if (b == '\n') return sb.toString().trim();
            if (sb.length() >= 200) return null;
            sb.append((char) b);
        }
        return null;
    }

    static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // already closed
        }
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
