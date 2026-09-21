package com.killer560.hub.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The connection state machine every Cross-Mod Bridge adapter shares: connect only while the adapter says the
 * grouping applies, polite reconnects, give up on repeated sign-in failures, a strict outbound rate cap, and
 * nothing sent until the connection counts as signed in.
 * <p>
 * <b>Threading.</b> Three kinds of thread, never mixed:
 * <ul>
 * <li>The client thread calls {@link #clientTick} (4 Hz, from {@link BridgeFeature}) with an immutable
 * {@link BridgeContext}. Subclasses decide what they want ({@link #decide}) and queue outbound text
 * ({@link #produceOutbound} -> {@link #offer}) there - cheap, no network, no locks beyond a concurrent queue.</li>
 * <li>One daemon worker thread per adapter owns the socket and every piece of state below marked "worker":
 * connecting, closing, flushing the outbox, backoff, keepalive. It never blocks on the network: every call is a
 * {@code CompletableFuture} continuation.</li>
 * <li>{@link #HTTP}'s own default executor runs the socket callbacks, which do nothing but hand the event to
 * the worker. Two bugs earlier today came from giving an {@code HttpClient} an executor whose threads then
 * blocked in {@code send()} (every request timed out) - so this client keeps the JDK's default executor and
 * only ever uses {@code sendAsync}/{@code buildAsync}. Mojang's blocking {@code joinServer} runs on
 * {@link #AUTH_POOL}, a separate pool that is never handed to an {@code HttpClient}.</li>
 * </ul>
 * Inbound messages are decoded on the worker, and whatever survives validation is applied to game-side state
 * on the client thread via {@code Minecraft.execute} (see {@link BridgeFeature}).
 */
abstract class SocketAdapter {

    /** Spec-independent politeness limits (the real mods retry every 30-60 s with no backoff at all). */
    static final long BACKOFF_MIN_MS = 30_000L;
    static final long BACKOFF_MAX_MS = 10 * 60_000L;
    /** Consecutive sign-in failures before giving up for the session. */
    static final int MAX_AUTH_FAILURES = 3;
    /** Outbound cap: 2 messages/s steady, burst of 8. The real clients have no cap at all - NoammAddons sends
     *  one message per scanned map tile, i.e. up to ~100 in a single burst when the map loads. */
    static final double BURST = 8.0;
    static final double REFILL_PER_SEC = 2.0;
    static final int MAX_OUTBOX = 128;
    static final int MAX_FRAME_CHARS = 16_384;
    /** When the grouping stops applying (a tab-list flicker, a party list briefly unknown) keep an established
     *  connection this long before closing, rather than churning a fresh sign-in. */
    static final long LINGER_MS = 20_000L;
    private static final long PUMP_MS = 250L;

    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    static final ExecutorService AUTH_POOL = Executors.newCachedThreadPool(daemon("killer560smod-bridge-auth"));

    /** What the client thread last asked for. {@code target} identifies WHICH group (party hash, server code);
     *  a change while connected goes to {@link #onTargetChanged}. */
    record Desire(boolean connect, String target, BridgeStatus idleStatus, String idleDetail, boolean runOver) {
        static Desire off(BridgeStatus status, String detail) {
            return new Desire(false, null, status, detail, false);
        }

        static Desire runFinished(String detail) {
            return new Desire(false, null, BridgeStatus.WAITING, detail, true);
        }

        static Desire connect(String target) {
            return new Desire(true, target, BridgeStatus.CONNECTED, "", false);
        }
    }

    /** Thrown (possibly wrapped) by {@link #openSocket} for a sign-in failure, as opposed to a network one.
     *  {@code fatal} = no point retrying this session (e.g. Minecraft never issued a chat key). */
    static final class BridgeAuthException extends RuntimeException {
        final boolean fatal;

        BridgeAuthException(String message, boolean fatal) {
            super(message);
            this.fatal = fatal;
        }
    }

    protected final Logger log;
    private final String displayName;
    private final String modId;
    private final ScheduledExecutorService worker;

    // ---- client thread -> worker
    private volatile Desire desire = Desire.off(BridgeStatus.OFF, "");
    private final ConcurrentLinkedQueue<String> outbox = new ConcurrentLinkedQueue<>();
    private final AtomicInteger outboxSize = new AtomicInteger();
    private volatile boolean resetRequested;

    // ---- worker -> anyone (GUI, client thread)
    private volatile BridgeStatus status = BridgeStatus.OFF;
    private volatile String detail = "";
    private volatile long nextAttemptAtMs;
    /** Bumps every time a connection becomes ready or is told to start over - the client thread compares it
     *  to reset its own "already sent" bookkeeping, so a reconnect re-sends the current run's facts. */
    private volatile int outboundEpoch;

    // ---- worker only
    private WebSocket socket;
    private String connectedTarget;
    private boolean connecting;
    private boolean ready;
    private volatile long generation;
    private int failures;
    private int authFailures;
    private boolean gaveUp;
    private String lastAuthError = "";
    private long desireOffSinceMs;
    private CompletableFuture<Void> sendChain = CompletableFuture.completedFuture(null);
    private double tokens = BURST;
    private long lastRefillMs = System.currentTimeMillis();

    // ---- client thread only
    private int seenOutboundEpoch = -1;

    SocketAdapter(String displayName, String modId) {
        this.displayName = displayName;
        this.modId = modId;
        this.log = LoggerFactory.getLogger("killer560smod-bridge");
        this.worker = Executors.newSingleThreadScheduledExecutor(daemon("killer560smod-bridge-" + modId));
        worker.scheduleWithFixedDelay(this::pumpSafely, PUMP_MS, PUMP_MS, TimeUnit.MILLISECONDS);
    }

    // ================================================================== subclass contract

    /** Client thread: what should this adapter be doing, given the context? */
    protected abstract Desire decide(BridgeContext ctx);

    /** Client thread, only while {@link #isReady()}: queue this run's facts with {@link #offer}. */
    protected abstract void produceOutbound(BridgeContext ctx);

    /** Client thread: the connection was (re)established or told to start over - forget what was sent. */
    protected abstract void resetOutbound();

    /** Worker: sign in if the protocol needs it and open the socket. Must not block. */
    protected abstract CompletableFuture<WebSocket> openSocket(String target, WebSocket.Listener listener);

    /** Worker: the socket opened. Send any sign-in frame via {@link #sendNow}. @return how long to wait before
     *  treating the connection as signed in (0 = right away). A close inside that window counts as a sign-in
     *  failure. */
    protected long onOpened(String target) {
        return 0L;
    }

    /** Worker: the connection now counts as signed in. */
    protected void onReady(String target) {
    }

    /** Worker: the group changed while connected. @return false to reconnect instead. */
    protected boolean onTargetChanged(String oldTarget, String newTarget) {
        return false;
    }

    /** Worker: a complete text frame arrived on the current connection. */
    protected abstract void onMessage(String text);

    /** Worker: frames to send before an intentional close. */
    protected List<String> goodbye(Desire why) {
        return List.of();
    }

    protected String closeReason() {
        return "";
    }

    /** Worker: every pump while connected and ready (keepalive, etc.). */
    protected void onPump(long nowMs) {
    }

    /** Worker: does this error mean the server refused who we are (vs. a network problem)? */
    protected boolean isAuthRejection(Throwable error) {
        return false;
    }

    /** Worker: forget any cached credential (e.g. a bearer token the server just refused). */
    protected void dropCachedCredential() {
    }

    /** Worker: a short live detail for the CONNECTED status line. */
    protected String connectedDetail() {
        return "";
    }

    // ================================================================== client thread API

    public final String displayName() {
        return displayName;
    }

    public final String modId() {
        return modId;
    }

    final void clientTick(BridgeContext ctx) {
        Desire d;
        try {
            d = decide(ctx);
        } catch (Throwable t) {
            d = Desire.off(BridgeStatus.WAITING, "internal error");
        }
        desire = d;
        int epoch = outboundEpoch;
        if (epoch != seenOutboundEpoch) {
            seenOutboundEpoch = epoch;
            resetOutbound();
        }
        if (d.connect() && isReady()) {
            try {
                produceOutbound(ctx);
            } catch (Throwable t) {
                log.debug("[Bridge] {} outbound failed: {}", displayName, t.toString());
            }
        }
    }

    /** Client thread: queue one outbound frame. @return false if it was not queued (null, or the outbox is
     *  full) - callers only mark a fact "sent" on true. */
    protected final boolean offer(String frame) {
        if (frame == null || !isReady() || outboxSize.get() >= MAX_OUTBOX) {
            return false;
        }
        outbox.add(frame);
        outboxSize.incrementAndGet();
        return true;
    }

    /** Settings toggled: forget a "gave up" state and the backoff, so the player can retry on demand. */
    final void resetSession() {
        resetRequested = true;
    }

    public final boolean isReady() {
        return status == BridgeStatus.CONNECTED;
    }

    public final BridgeStatus status() {
        return status;
    }

    public final String statusDetail() {
        if (status == BridgeStatus.RETRYING) {
            long secs = Math.max(0L, (nextAttemptAtMs - System.currentTimeMillis() + 999L) / 1000L);
            return "in " + secs + "s" + (detail.isEmpty() ? "" : " (" + detail + ")");
        }
        return detail;
    }

    // ================================================================== worker

    private void pumpSafely() {
        try {
            pump();
        } catch (Throwable t) {
            log.warn("[Bridge] {} worker error: {}", displayName, t.toString());
        }
    }

    private void pump() {
        long now = System.currentTimeMillis();
        if (resetRequested) {
            resetRequested = false;
            gaveUp = false;
            authFailures = 0;
            failures = 0;
            nextAttemptAtMs = 0L;
        }
        Desire d = desire;
        if (!d.connect()) {
            if (d.idleStatus() == BridgeStatus.OFF) {
                // Switched off: a later switch-on is a deliberate retry, so forget the session give-up too.
                gaveUp = false;
                authFailures = 0;
            }
            if (desireOffSinceMs == 0L) {
                desireOffSinceMs = now;
            }
            boolean linger = ready && socket != null && d.idleStatus() == BridgeStatus.WAITING && !d.runOver()
                    && now - desireOffSinceMs < LINGER_MS;
            if (linger) {
                flush(now); // only what was already queued while the grouping still applied
                return;
            }
            if (socket != null || connecting) {
                closeGracefully(d);
            }
            clearOutbox();
            if (gaveUp && d.idleStatus() != BridgeStatus.OFF) {
                setStatus(BridgeStatus.AUTH_FAILED, lastAuthError);
            } else {
                setStatus(d.idleStatus(), d.idleDetail());
            }
            return;
        }
        desireOffSinceMs = 0L;
        if (gaveUp) {
            setStatus(BridgeStatus.AUTH_FAILED, lastAuthError);
            return;
        }
        if (socket != null && ready) {
            if (!d.target().equals(connectedTarget)) {
                String old = connectedTarget;
                if (onTargetChanged(old, d.target())) {
                    connectedTarget = d.target();
                    clearOutbox();
                    outboundEpoch++;
                } else {
                    closeGracefully(d);
                    return; // next pump reconnects to the new target (no backoff: it was not a failure)
                }
            }
            onPump(now);
            flush(now);
            return;
        }
        if (socket != null || connecting) {
            return; // opening, or inside the sign-in verification window
        }
        if (now < nextAttemptAtMs) {
            setStatus(BridgeStatus.RETRYING, detail);
            return;
        }
        startConnect(d.target());
    }

    private void startConnect(String target) {
        connecting = true;
        long gen = ++generation;
        setStatus(BridgeStatus.CONNECTING, "");
        CompletableFuture<WebSocket> opening;
        try {
            opening = openSocket(target, new Listener(gen));
        } catch (Throwable t) {
            opening = CompletableFuture.failedFuture(t);
        }
        opening.whenComplete((ws, err) -> worker.execute(() -> onOpenResult(gen, target, ws, err)));
    }

    /** Worker: the group the current connection is announced to (null when not connected). */
    protected final String currentTarget() {
        return connectedTarget;
    }

    /** For subclasses to show "signing in" while their auth step runs (called from inside openSocket). */
    protected final void markAuthenticating() {
        setStatus(BridgeStatus.AUTHENTICATING, "");
    }

    private void onOpenResult(long gen, String target, WebSocket ws, Throwable err) {
        if (gen != generation) {
            if (ws != null) {
                ws.abort(); // superseded (switched off or retargeted while opening)
            }
            return;
        }
        connecting = false;
        if (err != null || ws == null) {
            Throwable cause = unwrap(err);
            boolean auth = cause instanceof BridgeAuthException || isAuthRejection(cause);
            if (auth && !(cause instanceof BridgeAuthException)) {
                dropCachedCredential();
            }
            fail(describe(cause), auth, cause instanceof BridgeAuthException bae && bae.fatal);
            return;
        }
        socket = ws;
        connectedTarget = target;
        ready = false;
        long verifyMs = onOpened(target);
        if (verifyMs <= 0L) {
            markReady(target);
        } else {
            setStatus(BridgeStatus.AUTHENTICATING, "");
            worker.schedule(() -> {
                if (gen == generation && socket == ws && !ready) {
                    markReady(target);
                }
            }, verifyMs, TimeUnit.MILLISECONDS);
        }
    }

    private void markReady(String target) {
        ready = true;
        failures = 0;
        authFailures = 0;
        tokens = BURST;
        lastRefillMs = System.currentTimeMillis();
        outboundEpoch++;
        setStatus(BridgeStatus.CONNECTED, "");
        log.info("[Bridge] {} connected", displayName);
        onReady(target);
    }

    private void onDropped(long gen, String why) {
        if (gen != generation) {
            return;
        }
        boolean duringVerify = socket != null && !ready;
        socket = null;
        ready = false;
        connecting = false;
        connectedTarget = null;
        // A close before the connection counted as signed in is the only "you are not who you say" signal a
        // server with no explicit ack can give (Devonian, spec 1.2 step 7).
        fail(why, duringVerify, false);
    }

    private void fail(String why, boolean auth, boolean fatal) {
        socket = null;
        ready = false;
        connecting = false;
        connectedTarget = null;
        clearOutbox();
        ++generation;
        if (auth) {
            authFailures++;
            lastAuthError = why;
            if (fatal || authFailures >= MAX_AUTH_FAILURES) {
                gaveUp = true;
                setStatus(BridgeStatus.AUTH_FAILED, why);
                log.warn("[Bridge] {} sign-in failed ({}), giving up for this session: {}", displayName, authFailures, why);
                return;
            }
        }
        failures++;
        long base = Math.min(BACKOFF_MAX_MS, BACKOFF_MIN_MS << Math.min(failures - 1, 5));
        long jitter = (long) (base * 0.25 * ThreadLocalRandom.current().nextDouble());
        long delay = Math.max(BACKOFF_MIN_MS, Math.min(BACKOFF_MAX_MS, base + jitter));
        nextAttemptAtMs = System.currentTimeMillis() + delay;
        setStatus(BridgeStatus.RETRYING, why);
        log.info("[Bridge] {} down ({}), retry in {}s", displayName, why, delay / 1000L);
    }

    private void closeGracefully(Desire why) {
        WebSocket ws = socket;
        boolean wasReady = ready;
        ++generation; // every callback from this socket is now ignored
        socket = null;
        ready = false;
        connecting = false;
        connectedTarget = null;
        clearOutbox();
        if (ws == null) {
            return;
        }
        if (wasReady) {
            for (String frame : goodbye(why)) {
                chainSend(ws, frame);
            }
        }
        CompletableFuture<Void> closing = sendChain.thenCompose(v -> ws.sendClose(WebSocket.NORMAL_CLOSURE, closeReason())
                .thenAccept(w -> { }));
        sendChain = CompletableFuture.completedFuture(null);
        closing.exceptionally(e -> null);
        // A server that never answers the close must not keep the TCP connection around.
        worker.schedule(ws::abort, 3, TimeUnit.SECONDS);
        log.info("[Bridge] {} disconnected", displayName);
    }

    /** Worker: send a frame now, outside the rate bucket - only for protocol control frames (sign-in, party
     *  announce, goodbye), never for data. Serialised: the JDK forbids overlapping sendText calls. */
    protected final void sendNow(String frame) {
        WebSocket ws = socket;
        if (ws != null && frame != null) {
            chainSend(ws, frame);
        }
    }

    /** Worker: WebSocket ping, chained behind any pending text. */
    protected final void sendPing() {
        WebSocket ws = socket;
        if (ws != null) {
            sendChain = sendChain.thenCompose(v -> ws.sendPing(ByteBuffer.allocate(0)).thenAccept(w -> { }))
                    .exceptionally(e -> null);
        }
    }

    private void chainSend(WebSocket ws, String frame) {
        sendChain = sendChain.thenCompose(v -> ws.sendText(frame, true).thenAccept(w -> { }))
                .exceptionally(e -> {
                    log.debug("[Bridge] {} send failed: {}", displayName, e.toString());
                    return null;
                });
    }

    private void flush(long now) {
        if (outbox.isEmpty() || socket == null || !ready) {
            return;
        }
        double elapsed = Math.max(0L, now - lastRefillMs) / 1000.0;
        lastRefillMs = now;
        tokens = Math.min(BURST, tokens + elapsed * REFILL_PER_SEC);
        while (tokens >= 1.0) {
            String frame = outbox.poll();
            if (frame == null) {
                break;
            }
            outboxSize.decrementAndGet();
            chainSend(socket, frame);
            tokens -= 1.0;
        }
    }

    private void clearOutbox() {
        while (outbox.poll() != null) {
            outboxSize.decrementAndGet();
        }
    }

    private void setStatus(BridgeStatus s, String d) {
        status = s;
        detail = d == null ? "" : d;
        if (s == BridgeStatus.CONNECTED) {
            String live = connectedDetail();
            detail = live == null ? "" : live;
        }
    }

    /** Worker: refresh the CONNECTED detail (e.g. NoammAddons' user counts arrived). */
    protected final void refreshConnectedDetail() {
        if (status == BridgeStatus.CONNECTED) {
            setStatus(BridgeStatus.CONNECTED, "");
        }
    }

    private static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        if (t instanceof BridgeAuthException) {
            return t.getMessage();
        }
        if (t instanceof WebSocketHandshakeException hs) {
            return "server refused the connection (HTTP " + hs.getResponse().statusCode() + ")";
        }
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + shorten(msg));
    }

    static String shorten(String s) {
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 80 ? one.substring(0, 80) + "..." : one;
    }

    static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    /** Socket callbacks: hand everything to the worker, tagged with the connection it belongs to. */
    private final class Listener implements WebSocket.Listener {
        private final long gen;
        private final StringBuilder buffer = new StringBuilder();
        private boolean overflow;

        private Listener(long gen) {
            this.gen = gen;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (!overflow) {
                if (buffer.length() + data.length() > MAX_FRAME_CHARS) {
                    overflow = true; // oversized message: dropped whole, never partially parsed
                    buffer.setLength(0);
                } else {
                    buffer.append(data);
                }
            }
            if (last) {
                if (!overflow) {
                    String text = buffer.toString();
                    worker.execute(() -> {
                        if (gen == generation && socket != null) {
                            try {
                                onMessage(text);
                            } catch (Throwable t) {
                                log.debug("[Bridge] {} bad message: {}", displayName, t.toString());
                            }
                        }
                    });
                }
                buffer.setLength(0);
                overflow = false;
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1); // none of the three protocols uses binary frames
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            String why = "closed " + statusCode + (reason == null || reason.isBlank() ? "" : " " + shorten(reason));
            worker.execute(() -> onDropped(gen, why));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            worker.execute(() -> onDropped(gen, describe(error)));
        }
    }
}
