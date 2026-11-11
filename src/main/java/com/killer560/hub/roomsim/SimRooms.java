package com.killer560.hub.roomsim;

import com.google.gson.JsonObject;
import com.killer560.hub.relay.RelayCalls;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The relay's co-op sim room list ({@code /simrooms/*}, relay src/simrooms.ts; contract in docs/SIM-COOP.md "Room list").
 * Only room metadata goes through the relay, never game traffic: the guest connects straight to the host. The call is
 * {@link RelayCalls#call} (bearer token, the relay takes name and uuid from it). Service key {@code simrooms-relay}
 * points just these at a fake.
 */
public final class SimRooms {

    private static final ArrayDeque<String> CALLS = new ArrayDeque<>();

    private SimRooms() {
    }

    /** Test hook: "METHOD /path" for the last 50 calls attempted, oldest first (no bodies: a password may be in one). */
    public static synchronized List<String> calls() { return new ArrayList<>(CALLS); }

    public static synchronized void clearCallsForTest() { CALLS.clear(); }

    public static CompletableFuture<RelayCalls.Reply> list(String mc) {
        return call("GET", "/simrooms/list?mc=" + URLEncoder.encode(mc, StandardCharsets.UTF_8) + "&all=1", null);
    }

    public static CompletableFuture<RelayCalls.Reply> create(String name, String password, int max, String mode,
                                                             String host, int port) {
        JsonObject b = new JsonObject();
        b.addProperty("name", name);
        if (password != null && !password.isEmpty()) b.addProperty("password", password);
        b.addProperty("max", max);
        b.addProperty("mc", SimGuest.mc());
        b.addProperty("modVersion", SimGuest.modVersion());
        b.addProperty("protocol", SimCoop.PROTOCOL);
        b.addProperty("mode", mode);
        b.add("endpoint", endpoint(host, port));
        return call("POST", "/simrooms/create", b);
    }

    public static CompletableFuture<RelayCalls.Reply> refresh(String roomId, String secret, int players, String mode,
                                                              String host, int port) {
        JsonObject b = owner(roomId, secret);
        b.addProperty("players", players);
        b.addProperty("mode", mode);
        b.add("endpoint", endpoint(host, port));
        return call("POST", "/simrooms/refresh", b);
    }

    public static CompletableFuture<RelayCalls.Reply> delete(String roomId, String secret) {
        return call("POST", "/simrooms/delete", owner(roomId, secret));
    }

    public static CompletableFuture<RelayCalls.Reply> join(String roomId, String password) {
        return join(roomId, password, null);
    }

    /** Phase 3: an invite key (bound to this account) skips the password. */
    public static CompletableFuture<RelayCalls.Reply> join(String roomId, String password, String invite) {
        JsonObject b = new JsonObject();
        b.addProperty("roomId", roomId);
        if (password != null && !password.isEmpty()) b.addProperty("password", password);
        if (invite != null && !invite.isEmpty()) b.addProperty("invite", invite);
        return call("POST", "/simrooms/join", b);
    }

    /** Which of these names (at most 50) could take an invite to the owner's room right now. */
    public static CompletableFuture<RelayCalls.Reply> reach(String roomId, String secret, java.util.Collection<String> names) {
        JsonObject b = owner(roomId, secret);
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        names.stream().limit(50).forEach(a::add);
        b.add("names", a);
        return call("POST", "/simrooms/reach", b);
    }

    /** Sends one player (name or uuid) an invite to the owner's room: 200, 404 "not reachable" or 429. */
    public static CompletableFuture<RelayCalls.Reply> invite(String roomId, String secret, String target) {
        JsonObject b = owner(roomId, secret);
        b.addProperty("target", target);
        return call("POST", "/simrooms/invite", b);
    }

    /** What a join refusal means to the player. */
    public static String refusal(RelayCalls.Reply r) {
        return switch (r.status()) {
            case 403 -> "Wrong password.";
            case 404 -> "That room is gone.";
            case 409 -> "That room is full.";
            case 429 -> "Too many tries, wait a minute.";
            case -1 -> "Could not reach the room list: " + r.error();
            default -> r.error() == null ? "The room list said no (" + r.status() + ")." : r.error();
        };
    }

    private static JsonObject owner(String roomId, String secret) {
        JsonObject b = new JsonObject();
        b.addProperty("roomId", roomId);
        b.addProperty("hostSecret", secret);
        return b;
    }

    private static com.google.gson.JsonElement endpoint(String host, int port) {
        if (host == null) return com.google.gson.JsonNull.INSTANCE;
        JsonObject e = new JsonObject();
        e.addProperty("host", host);
        e.addProperty("port", port);
        return e;
    }

    private static synchronized void record(String line) {
        CALLS.addLast(line);
        while (CALLS.size() > 50) CALLS.removeFirst();
    }

    private static CompletableFuture<RelayCalls.Reply> call(String method, String path, JsonObject body) {
        record(method + " " + path);
        return RelayCalls.call("simrooms-relay", method, path, body);
    }
}
