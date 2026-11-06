package com.killer560.hub.relay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.modusers.ModUserConfig;
import com.killer560.hub.spotify.RemoteSongs;
import com.killer560.hub.spotify.SongShare;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModNet;
import org.slf4j.Logger;

import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Song Tag's second socket, into the LOBBY room while {@link RelayClient} sits in a party room (killer560, 2026-11-06:
 * "it should work for people not in the party too. anyone in the same lobby should be able to see it"). The relay keeps
 * one room per socket, and Mod Chat in Party mode (its default) or a dungeon's party data keeps the main socket in the
 * party room, so a partied player never heard, or reached, the unpartied mod users standing next to him.
 * <p>
 * It carries only Song Tag: a hello (so the lobby sees this player as a mod user, as he chose to show himself), the
 * {@code np.v1} packets both ways, and a keep-alive. No chat ever goes out on it, and its join/leave never reaches Mod
 * Chat. Runs on {@link RelayClient}'s thread and shares its token; {@link #update} is free unless the room changed.
 */
public final class RelayTap {

    private static final Logger LOGGER = ModLog.get("killer560smod-relay");
    /** Who the relay has in the lobby room right now (from its welcome and presence lists). */
    private static volatile List<String> online = List.of();

    private static volatile String wantUrl = "";
    private static volatile String wantRoom = "";
    private static volatile WebSocket socket;
    private static volatile String activeRoom = "";
    private static volatile long generation;
    private static String connectedRoom = "";
    private static int attempt;
    private static ScheduledFuture<?> retry;
    private static ScheduledFuture<?> keepAlive;
    private static CompletableFuture<Void> sendChain = CompletableFuture.completedFuture(null);

    private RelayTap() {
    }

    /** {@code room} null or blank: no tap. Called from ModChatFeature's room check. */
    public static void update(String baseUrl, String room) {
        String url = RelayEndpoint.normalise(baseUrl);
        String target = room == null || room.isBlank() || !RelayEndpoint.isUsable(url) ? "" : room;
        if (url.equals(wantUrl) && target.equals(wantRoom)) return;
        wantUrl = url;
        wantRoom = target;
        RelayClient.WORKER.execute(RelayTap::reconcile);
    }

    public static boolean isConnected() { return socket != null; }

    public static String room() { return activeRoom; }

    /** Everyone in the lobby room, you included; empty while not connected. */
    public static List<String> online() { return online; }

    /** Whether {@code name} still has a socket in either relay room (a leave of one of his two sockets is not him leaving). */
    public static boolean stillHere(String name) { return online.contains(name) || RelayClient.online().contains(name); }

    public static boolean sendData(String key, JsonElement value) {
        if (socket == null) return false;
        JsonObject packet = new JsonObject();
        packet.addProperty("type", "data");
        packet.addProperty("key", key);
        packet.add("value", value);
        return send(packet);
    }

    /** WORKER thread: drops the socket and connects again (the account changed; see RelayClient.forgetToken). */
    static void reconnect() {
        connectedRoom = "";
        reconcile();
    }

    private static void reconcile() {
        String room = wantRoom;
        if (room.equals(connectedRoom) && (socket != null || retry != null || attempt > 0)) return;
        shutdown();
        if (room.isEmpty()) return;
        attempt = 0;
        connect(wantUrl, room);
    }

    private static void shutdown() {
        generation++;
        if (retry != null) {
            retry.cancel(false);
            retry = null;
        }
        if (keepAlive != null) {
            keepAlive.cancel(false);
            keepAlive = null;
        }
        closeSocket();
        connectedRoom = "";
        attempt = 0;
    }

    private static void closeSocket() {
        WebSocket ws = socket;
        socket = null;
        activeRoom = "";
        online = List.of();
        if (ws != null) {
            try {
                ws.abort();
            } catch (Exception ignored) {
                // Already gone.
            }
        }
    }

    private static void connect(String url, String room) {
        final long gen = ++generation;
        connectedRoom = room;
        attempt++;
        Socket listener = new Socket(gen);
        RelayClient.tokenFor(url)
                .thenCompose(issued -> ModNet.webSocket(RelayClient.HTTP.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(15)),
                        RelayEndpoint.websocket(url, room, issued.value()), listener))
                .whenComplete((ws, error) -> RelayClient.WORKER.execute(() -> {
                    if (gen != generation) {
                        if (ws != null) ws.abort();
                        return;
                    }
                    if (error != null) {
                        failed(gen, RelayClient.reason(error));
                        return;
                    }
                    socket = ws;
                    activeRoom = room;
                    attempt = 0;
                    keepAlive = RelayClient.WORKER.scheduleAtFixedRate(() -> {
                        if (gen == generation) send(RelayClient.ping());
                    }, RelayClient.KEEPALIVE_SECONDS, RelayClient.KEEPALIVE_SECONDS, TimeUnit.SECONDS);
                    LOGGER.info("[Relay] Song Tag lobby link connected (room {})", room);
                    listener.live.complete(null);
                }));
    }

    /** On WORKER: a failed connect or a dropped socket. Backs off like the main socket, quietly (no chat line). */
    private static void failed(long gen, String why) {
        if (gen != generation || wantRoom.isEmpty()) return;
        String room = connectedRoom;
        closeSocket();
        if (keepAlive != null) {
            keepAlive.cancel(false);
            keepAlive = null;
        }
        long delay = RelayClient.backoffMs(attempt);
        LOGGER.warn("[Relay] Song Tag lobby link: {} - retrying in {}s", why, delay / 1000);
        retry = RelayClient.WORKER.schedule(() -> {
            retry = null;
            if (gen == generation && room.equals(wantRoom)) connect(wantUrl, room);
        }, delay, TimeUnit.MILLISECONDS);
    }

    private static boolean send(JsonObject packet) {
        String json = packet.toString();
        RelayClient.WORKER.execute(() -> {
            WebSocket ws = socket;
            if (ws == null) return;
            sendChain = sendChain.thenCompose(ignored -> ws.sendText(json, true)).<Void>handle((r, e) -> null);
        });
        return true;
    }

    /** Runs on the socket's network thread, like {@code RelayClient.handle}. */
    static void handle(String text) {
        JsonObject packet;
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (parsed == null || !parsed.isJsonObject()) return;
            packet = parsed.getAsJsonObject();
        } catch (RuntimeException | StackOverflowError e) {
            return;
        }
        String name = RelayClient.str(packet, "name");
        switch (RelayClient.str(packet, "type")) {
            case "welcome" -> {
                online = RelayClient.names(packet);
                ModUserConfig cfg = ModUserConfig.getInstance();
                JsonObject hello = new JsonObject();
                hello.addProperty("type", "hello");
                hello.addProperty("variant", ModUserConfig.ownVariant());
                hello.addProperty("share", cfg.isShare());
                hello.addProperty("shareAs", cfg.getShareAs());
                send(hello);
                SongShare.resendSoon();
            }
            case "presence" -> {
                String event = RelayClient.str(packet, "event");
                if (packet.has("online")) {
                    online = RelayClient.names(packet);
                }
                if ("join".equals(event)) {
                    SongShare.resendSoon();
                } else if ("leave".equals(event) && !stillHere(name)) {
                    RemoteSongs.forget(name);
                }
            }
            case "data" -> {
                if (RemoteSongs.KEY.equals(RelayClient.str(packet, "key"))) {
                    RemoteSongs.onRelayData(RelayClient.str(packet, "from"), RelayClient.str(packet, "uuid"),
                            packet.get("value"));
                }
            }
            default -> {
                // pong, error: nothing to do.
            }
        }
    }

    private static final class Socket implements WebSocket.Listener {
        private final long gen;
        private final StringBuilder buffer = new StringBuilder();

        /** Completed once {@link #connect} set {@link #socket}: see RelayClient's Socket.live (the hello was lost). */
        final CompletableFuture<Void> live = new CompletableFuture<>();

        private Socket(long gen) { this.gen = gen; }

        @Override
        public void onOpen(WebSocket webSocket) { webSocket.request(1); }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (!last) {
                webSocket.request(1);
                return null;
            }
            String text = buffer.toString();
            buffer.setLength(0);
            live.thenRun(() -> {
                if (gen == generation) handle(text);
                webSocket.request(1);
            });
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            RelayClient.WORKER.execute(() -> failed(gen, "relay closed the connection"));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            RelayClient.WORKER.execute(() -> failed(gen, RelayClient.reason(error)));
        }
    }
}
