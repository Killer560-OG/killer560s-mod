package com.killer560.hub.roomsim;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The co-op sim's TCP hole punch (coopfree-1111), tried after the direct address (the relay never carries the game), so a room behind
 * a router with no UPnP costs the relay nothing but two small calls. The host advertises a source port when it
 * lists the room; a guest with a ticket asks the relay ({@code /simrooms/punch}), which tells the host over its standing
 * signal socket ({@link SimSignal}, PUNCH frame); after {@code delayMs} both connect to each other from those ports at once (TCP
 * simultaneous open). The guest sends the relay's key first and the host joins only a socket that sent it to its LAN game
 * port, so the server's own checks (ticket, password, Max) stay the same. Windows cannot share a port between a listening
 * and a connecting socket, so neither side listens: both only connect. Every thread is a daemon; never the render thread.
 * {@code -Dkiller560.simcoop.punch=off} advertises nothing and skips the punch, {@code drop} advertises but ignores the
 * host's PUNCH frames (the testkit's failure path).
 */
final class SimPunch {
    private static final int TRY_MS = 6_000, CONNECT_MS = 300, KEY_WAIT_MS = 3_000;
    /** One punch at a time on the host; a second while one runs is dropped (that guest is told it cannot connect). */
    private static final ThreadPoolExecutor HOST = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), r -> {
                Thread t = new Thread(r, "k560-simpunch-host");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.DiscardPolicy());

    private SimPunch() {}

    private static String mode() { return System.getProperty("killer560.simcoop.punch", "on"); }

    /** A free local port the host punches from (0: punching is off). Picked once per published room. */
    static int pickHostPort() {
        return "off".equals(mode()) ? 0 : freePort();
    }

    /** The host's signal socket got a PUNCH frame ({@code json} = {ip, port, key, delayMs}). Any thread. */
    static void onHostFrame(String json, int punchPort, int lanPort) {
        if (punchPort <= 0 || "drop".equals(mode())) {
            SimCoop.LOGGER.info("[SimPunch] punch request ignored ({})", punchPort <= 0 ? "no punch port" : "drop mode");
            return;
        }
        HOST.execute(() -> {
            try {
                JsonObject o = JsonParser.parseString(json).getAsJsonObject();
                String key = o.get("key").getAsString();
                Socket s = punch(punchPort, o.get("ip").getAsString(), o.get("port").getAsInt(), o.get("delayMs").getAsLong());
                if (s == null) {
                    SimCoop.LOGGER.info("[SimPunch] host punch failed (no answer within {} s)", TRY_MS / 1000);
                    return;
                }
                s.setSoTimeout(KEY_WAIT_MS);
                byte[] got = s.getInputStream().readNBytes(key.length());
                s.setSoTimeout(0);
                if (!key.equals(new String(got, StandardCharsets.US_ASCII))) {
                    SimCoop.LOGGER.info("[SimPunch] host punch refused (wrong key)");
                    s.close();
                    return;
                }
                Socket lan = new Socket();
                lan.setTcpNoDelay(true);
                lan.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), lanPort), 5_000);
                splice(s, lan);
                SimCoop.LOGGER.info("[SimPunch] host joined a punched guest to the game");
            } catch (Exception e) {
                SimCoop.LOGGER.info("[SimPunch] host punch failed ({})", e.toString());
            }
        });
    }

    /**
     * Punches to the host of a room this guest holds a ticket for and answers the loopback address Minecraft should connect
     * to; fails with a sentence. The first answer can take delayMs plus up to 6 s.
     */
    static CompletableFuture<String> guest(String roomId, String nonce) {
        if ("off".equals(mode())) return CompletableFuture.failedFuture(new IOException("hole punching is off"));
        int local = freePort();
        if (local <= 0) return CompletableFuture.failedFuture(new IOException("no free local port"));
        return SimRooms.punch(roomId, nonce, local).thenApplyAsync(rep -> {
            if (rep.status() != 200 || rep.body() == null || !rep.body().has("key")) {
                throw new IllegalStateException(rep.status() == 409 ? "the owner's game cannot be punched"
                        : "the relay said no (" + (rep.error() == null ? rep.status() : rep.error()) + ")");
            }
            JsonObject b = rep.body();
            String key = b.get("key").getAsString();
            ServerSocket loop = null;
            Socket s = null;
            try {
                s = punch(local, b.get("host").getAsString(), b.get("port").getAsInt(), b.get("delayMs").getAsLong());
                if (s == null) throw new IllegalStateException("no answer from the owner's game");
                s.getOutputStream().write(key.getBytes(StandardCharsets.US_ASCII));
                s.getOutputStream().flush();
                loop = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                loop.setSoTimeout(60_000);
                ServerSocket ss = loop;
                Socket peer = s;
                SimSignal.daemon("k560-simpunch-guest", () -> {
                    try (ServerSocket server = ss) {
                        Socket mc = server.accept();
                        mc.setTcpNoDelay(true);
                        splice(peer, mc);
                    } catch (IOException e) {
                        close(peer);
                    }
                });
                SimCoop.LOGGER.info("[SimPunch] guest punched through to the owner");
                return "127.0.0.1:" + loop.getLocalPort();
            } catch (IOException | InterruptedException e) {
                close(s);
                close(loop);
                throw new IllegalStateException(String.valueOf(e.getMessage()), e);
            }
        });
    }

    /** Connects from {@code localPort} to the peer again and again until one connect lands or 6 s pass; null if none. */
    private static Socket punch(int localPort, String ip, int port, long delayMs) throws InterruptedException {
        Thread.sleep(Math.max(0, Math.min(delayMs, 5_000)));
        InetSocketAddress to = new InetSocketAddress(ip, port);
        long end = System.currentTimeMillis() + TRY_MS;
        while (System.currentTimeMillis() < end) {
            Socket s = new Socket();
            try {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(localPort));
                s.connect(to, CONNECT_MS);
                s.setTcpNoDelay(true);
                return s;
            } catch (IOException e) {
                close(s);
                Thread.sleep(50);
            }
        }
        return null;
    }

    /** Pipes both ways until either side ends, then closes both. */
    private static void splice(Socket a, Socket b) {
        SimSignal.daemon("k560-simpunch-pipe", () -> pipe(a, b));
        SimSignal.daemon("k560-simpunch-pipe", () -> pipe(b, a));
    }

    private static void pipe(Socket from, Socket to) {
        byte[] buf = new byte[32 * 1024];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            for (int n; (n = in.read(buf)) >= 0; ) out.write(buf, 0, n);
        } catch (IOException ignored) {
            // either side went
        } finally {
            close(from);
            close(to);
        }
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            return 0;
        }
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // closing
        }
    }
}
