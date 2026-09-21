package com.killer560.hub.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The mod's connection to {@code killer560s-mod-relay} - a private channel between mod users that Hypixel never
 * sees, because the bytes never go through Hypixel. Built on {@link java.net.http.HttpClient}'s own WebSocket,
 * so there is no new dependency.
 * <p>
 * <b>Threading.</b> Nothing here ever runs on the render or client thread. One daemon scheduler thread
 * ({@code killer560smod-relay}) owns the whole state machine; {@link HttpClient} gets its own daemon pool for
 * the handshake and the socket. {@link #update} is the only method the game loop calls and it does nothing at
 * all unless something actually changed - no allocation, no locks, no network.
 * <p>
 * <b>When the relay is unreachable the mod behaves as if the feature were off.</b> Reconnects are backed off
 * (2s doubling to a 2-minute cap, with jitter) so it never spins, and an outage produces exactly one message to
 * the player and then silence - see {@code reportedFailure}.
 * <p>
 * <b>What leaves the client:</b> the handshake described in {@link RelayAuth}, and afterwards only the chat
 * lines the player typed into {@code /killer560 chat}, a keep-alive ping, and the room name (a hash - see
 * {@link RelayRoom}). No telemetry, no inventory, no position, no run data unless a feature explicitly calls
 * {@link #sendData}.
 */
public final class RelayClient {

    public enum State {
        /** Mod Chat is off, or you are not in a world. */
        OFF,
        /** On, but no deployed relay URL has been set yet - deliberately does not even try to connect. */
        NO_URL,
        /** On, with a URL, but the caller couldn't compute a room yet (Party mode while alone; Lobby mode
         *  before Hypixel's instance id is known) - deliberately does not connect anywhere else instead of
         *  guessing a wider room. See {@link com.killer560.hub.relay.RelayRoom}'s class doc. */
        NO_ROOM,
        CONNECTING,
        CONNECTED,
        /** Down, waiting out the backoff. */
        RETRYING
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-relay");

    /** The relay's own limit (Worker {@code room.ts}); truncating here keeps the reported length honest. */
    private static final int MAX_CHAT_CHARS = 512;
    private static final long KEEPALIVE_SECONDS = 30L;
    private static final long BACKOFF_BASE_MS = 2_000L;
    private static final long BACKOFF_CAP_MS = 120_000L;
    /** Re-authenticating costs two HTTP round trips, so a still-valid token is reused until it is nearly up. */
    private static final long TOKEN_REUSE_MARGIN_MS = 5 * 60_000L;

    private static final ScheduledExecutorService WORKER =
            Executors.newSingleThreadScheduledExecutor(daemon("killer560smod-relay"));
    private static final ExecutorService IO = Executors.newCachedThreadPool(daemon("killer560smod-relay-io"));
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(IO)
            .build();

    private static final RelayListener NO_LISTENER = new RelayListener() {
    };

    // Written by the client tick, read by WORKER.
    private static volatile boolean wantOn;
    private static volatile String wantUrl = "";
    /** Empty means "no room computed yet" - see {@link State#NO_ROOM}. There is no longer a default room to
     *  fall back to (killer560, 2026-09-20: no global option). */
    private static volatile String wantRoom = "";

    // Written by WORKER (and the socket callbacks), read from anywhere including the GUI.
    private static volatile State state = State.OFF;
    private static volatile String lastError = "";
    private static volatile List<String> online = List.of();
    private static volatile String activeRoom = "";
    private static volatile WebSocket socket;
    private static volatile RelayListener listener = NO_LISTENER;

    // WORKER thread only.
    private static String connectedUrl = "";
    private static String connectedRoom = "";
    private static RelayAuth.Token token;
    private static String tokenUrl = "";
    private static int attempt;
    /** Bumped on every (re)connect so a callback from a socket we already abandoned can be ignored. Volatile
     *  because the socket callbacks compare against it from the HTTP client's own threads. */
    private static volatile long generation;
    private static boolean reportedFailure;
    private static ScheduledFuture<?> retry;
    private static ScheduledFuture<?> keepAlive;
    private static CompletableFuture<Void> sendChain = CompletableFuture.completedFuture(null);

    private RelayClient() {
    }

    // ---------------------------------------------------------------- public API

    public static void setListener(RelayListener value) {
        listener = value == null ? NO_LISTENER : value;
    }

    /**
     * Tell the relay what it should be doing. Safe (and intended) to call from a client tick: it returns
     * immediately unless the answer actually changed, and all the real work happens on the relay's own thread.
     */
    public static void update(boolean on, String baseUrl, String room) {
        String url = RelayEndpoint.normalise(baseUrl);
        // null/blank means the caller (ModChatFeature) couldn't compute a room - go NO_ROOM in reconcile()
        // rather than inventing a fallback. See RelayRoom's class doc on the removed Global option.
        String target = room == null || room.isBlank() ? "" : room;
        if (on == wantOn && url.equals(wantUrl) && target.equals(wantRoom)) {
            return;
        }
        wantOn = on;
        wantUrl = url;
        wantRoom = target;
        WORKER.execute(RelayClient::reconcile);
    }

    /** @return false if nothing was sent (not connected) - the caller must say so rather than fall back. */
    public static boolean sendChat(String message) {
        if (message == null || message.isBlank() || state != State.CONNECTED || socket == null) {
            return false;
        }
        JsonObject packet = new JsonObject();
        packet.addProperty("type", "chat");
        packet.addProperty("message", message.length() > MAX_CHAT_CHARS
                ? message.substring(0, MAX_CHAT_CHARS)
                : message);
        return send(packet);
    }

    /** Opaque per-run data sharing. Nothing calls this yet; it exists so the groundwork is real, not implied. */
    public static boolean sendData(String key, JsonElement value) {
        if (key == null || state != State.CONNECTED || socket == null) {
            return false;
        }
        JsonObject packet = new JsonObject();
        packet.addProperty("type", "data");
        packet.addProperty("key", key);
        packet.add("value", value);
        return send(packet);
    }

    public static State state() {
        return state;
    }

    public static boolean isConnected() {
        return state == State.CONNECTED;
    }

    /** Everyone the relay currently has in your room, including you. Empty while disconnected. */
    public static List<String> online() {
        return online;
    }

    public static String room() {
        return activeRoom;
    }

    /** One short phrase for the settings tab and for {@code /killer560 chat}'s reply. */
    public static String statusText() {
        return switch (state) {
            case OFF -> "off";
            case NO_URL -> "no relay URL set";
            case NO_ROOM -> "no room yet";
            case CONNECTING -> "connecting";
            case CONNECTED -> "connected";
            case RETRYING -> lastError.isEmpty() ? "reconnecting" : "reconnecting - " + lastError;
        };
    }

    // ---------------------------------------------------------------- state machine (WORKER thread)

    private static void reconcile() {
        boolean on = wantOn;
        String url = wantUrl;
        String room = wantRoom;
        if (!on) {
            shutdown(State.OFF, "");
            return;
        }
        if (!RelayEndpoint.isUsable(url)) {
            // Nothing to connect to: stay completely idle rather than hammering DNS for a placeholder host.
            shutdown(State.NO_URL, "no relay URL set");
            return;
        }
        if (room.isEmpty()) {
            // The caller couldn't compute a room (not in a party; Hypixel's instance id not known yet) -
            // staying idle here instead of guessing something wider is the whole point of this feature
            // (killer560's "public party chat" leak report). See RelayRoom's class doc.
            shutdown(State.NO_ROOM, "no room selected");
            return;
        }
        boolean sameTarget = url.equals(connectedUrl) && room.equals(connectedRoom);
        if (sameTarget && (state == State.CONNECTED || state == State.CONNECTING || state == State.RETRYING)) {
            // Already there, on the way, or waiting out a backoff we must not short-circuit.
            return;
        }
        closeSocket();
        cancelRetry();
        cancelKeepAlive();
        attempt = 0;
        // Leave the old room's name behind immediately rather than let the tab keep showing it while the
        // socket is already gone and a different room is connecting - see killer560's "no global fallback"
        // report; misrepresenting which room you're actually in is the same class of mistake.
        activeRoom = "";
        connect(url, room);
    }

    private static void shutdown(State newState, String reason) {
        generation++;
        cancelRetry();
        cancelKeepAlive();
        closeSocket();
        connectedUrl = "";
        connectedRoom = "";
        token = null;
        tokenUrl = "";
        attempt = 0;
        reportedFailure = false;
        activeRoom = "";
        lastError = reason;
        state = newState;
    }

    private static void connect(String url, String room) {
        generation++;
        final long gen = generation;
        connectedUrl = url;
        connectedRoom = room;
        lastError = "";
        state = State.CONNECTING;
        tokenFor(url)
                .thenCompose(issued -> HTTP.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .buildAsync(RelayEndpoint.websocket(url, room, issued.value()), new Socket(gen)))
                .whenComplete((ws, error) -> WORKER.execute(() -> {
                    if (gen != generation) {
                        if (ws != null) {
                            ws.abort();
                        }
                        return;
                    }
                    if (error != null) {
                        failed(reason(error));
                        return;
                    }
                    socket = ws;
                    activeRoom = room;
                    state = State.CONNECTED;
                    attempt = 0;
                    reportedFailure = false;
                    lastError = "";
                    startKeepAlive(gen);
                    LOGGER.info("[Relay] Connected to {} (room {})", url, room);
                }));
    }

    private static CompletableFuture<RelayAuth.Token> tokenFor(String url) {
        RelayAuth.Token cached = token;
        if (cached != null && url.equals(tokenUrl)
                && cached.expiresAtMs() - System.currentTimeMillis() > TOKEN_REUSE_MARGIN_MS) {
            return CompletableFuture.completedFuture(cached);
        }
        return RelayAuth.authenticate(HTTP, url).thenApply(issued -> {
            WORKER.execute(() -> {
                token = issued;
                tokenUrl = url;
            });
            return issued;
        });
    }

    /** Called on WORKER for every failed connect and every dropped socket. */
    private static void failed(String why) {
        closeSocket();
        cancelKeepAlive();
        // A stale token looks exactly like an unreachable relay from here, so always re-authenticate.
        token = null;
        tokenUrl = "";
        lastError = why;
        state = State.RETRYING;
        attempt++;
        long delay = backoffMs(attempt);
        LOGGER.warn("[Relay] {} - retrying in {}s", why, delay / 1000);
        if (!reportedFailure) {
            // "One chat line at most, then silence": the listener is told once per outage, not once per retry.
            reportedFailure = true;
            listener.onDisconnected(why);
        }
        cancelRetry();
        retry = WORKER.schedule(RelayClient::retryNow, delay, TimeUnit.MILLISECONDS);
    }

    private static void retryNow() {
        if (!wantOn || !RelayEndpoint.isUsable(wantUrl)) {
            reconcile();
            return;
        }
        connect(wantUrl, wantRoom);
    }

    /** 2s, 4s, 8s ... capped at 2 minutes, with +/-20% jitter so a whole party doesn't reconnect in lockstep. */
    private static long backoffMs(int failures) {
        long base = Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS << Math.min(Math.max(failures - 1, 0), 6));
        long jitter = base / 5;
        return base + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
    }

    private static void startKeepAlive(long gen) {
        cancelKeepAlive();
        keepAlive = WORKER.scheduleAtFixedRate(() -> {
            if (gen != generation) {
                return;
            }
            send(ping());
        }, KEEPALIVE_SECONDS, KEEPALIVE_SECONDS, TimeUnit.SECONDS);
    }

    private static JsonObject ping() {
        JsonObject packet = new JsonObject();
        packet.addProperty("type", "ping");
        packet.addProperty("t", System.currentTimeMillis());
        return packet;
    }

    private static boolean send(JsonObject packet) {
        String json = packet.toString();
        WORKER.execute(() -> {
            WebSocket ws = socket;
            if (ws == null) {
                return;
            }
            // WebSocket forbids a second sendText before the previous one completes, so sends are chained.
            sendChain = sendChain
                    .thenCompose(ignored -> ws.sendText(json, true))
                    .<Void>handle((result, error) -> {
                        if (error != null) {
                            LOGGER.warn("[Relay] Send failed: {}", reason(error));
                        }
                        return null;
                    });
        });
        return true;
    }

    private static void closeSocket() {
        WebSocket ws = socket;
        socket = null;
        online = List.of();
        if (ws != null) {
            try {
                // abort(), not sendClose(): immediate and non-blocking, and we never care about the close frame.
                ws.abort();
            } catch (Exception ignored) {
                // Already gone.
            }
        }
    }

    private static void cancelRetry() {
        if (retry != null) {
            retry.cancel(false);
            retry = null;
        }
    }

    private static void cancelKeepAlive() {
        if (keepAlive != null) {
            keepAlive.cancel(false);
            keepAlive = null;
        }
    }

    // ---------------------------------------------------------------- socket

    private static final class Socket implements WebSocket.Listener {

        private final long gen;
        /** A text message can arrive in several frames; nothing may be parsed until {@code last}. */
        private final StringBuilder buffer = new StringBuilder();

        private Socket(long gen) {
            this.gen = gen;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                if (gen == generation) {
                    try {
                        handle(text);
                    } catch (RuntimeException e) {
                        LOGGER.warn("[Relay] Bad packet: {}", e.toString());
                    }
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String closeReason) {
            dropped(gen, closeReason == null || closeReason.isBlank()
                    ? "relay closed the connection"
                    : closeReason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            dropped(gen, reason(error));
        }
    }

    private static void dropped(long gen, String why) {
        WORKER.execute(() -> {
            if (gen != generation) {
                return;
            }
            if (!wantOn) {
                shutdown(State.OFF, "");
                return;
            }
            failed(why);
        });
    }

    private static void handle(String text) {
        JsonElement parsed = JsonParser.parseString(text);
        if (!parsed.isJsonObject()) {
            return;
        }
        JsonObject packet = parsed.getAsJsonObject();
        RelayListener target = listener;
        switch (str(packet, "type")) {
            case "welcome" -> {
                List<String> names = names(packet);
                online = names;
                target.onConnected(activeRoom, names);
            }
            case "chat" -> target.onChat(str(packet, "from"), str(packet, "message"));
            case "presence" -> {
                List<String> names = names(packet);
                online = names;
                target.onPresence(str(packet, "event"), str(packet, "name"), names);
            }
            case "data" -> target.onData(str(packet, "from"), str(packet, "key"), packet.get("value"));
            case "pong" -> {
                // Keep-alive answered; nothing to do.
            }
            case "error" -> LOGGER.warn("[Relay] Relay rejected a packet: {}", str(packet, "error"));
            default -> LOGGER.debug("[Relay] Ignoring unknown packet type {}", str(packet, "type"));
        }
    }

    private static List<String> names(JsonObject packet) {
        JsonElement element = packet.get("online");
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        JsonArray array = element.getAsJsonArray();
        List<String> out = new ArrayList<>(array.size());
        for (JsonElement entry : array) {
            if (entry != null && entry.isJsonPrimitive()) {
                out.add(entry.getAsString());
            }
        }
        return List.copyOf(out);
    }

    private static String str(JsonObject packet, String key) {
        JsonElement element = packet.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return "";
        }
        try {
            return element.getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    /** One short phrase a player can act on, instead of a stack trace or a class name. */
    private static String reason(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof RelayAuth.RelayAuthException) {
            return cause.getMessage();
        }
        if (cause instanceof UnknownHostException) {
            return "relay address not found";
        }
        if (cause instanceof ConnectException) {
            return "couldn't reach the relay";
        }
        if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) {
            return "relay timed out";
        }
        String message = cause == null ? null : cause.getMessage();
        return message == null || message.isBlank() ? "relay unavailable" : message;
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        };
    }
}
