package com.killer560.hub.lfg;

import com.google.gson.JsonObject;
import com.killer560.hub.relay.RelayCalls;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The relay's {@code /lfg/*} endpoints (killer560s-mod-relay PROTOCOL.md "Custom Party Finder"), authenticated the way
 * Supporters self-service is ({@link RelayAuth}: the Mojang chat key signs a relay challenge; no password, no API key).
 * The relay stamps the caller's name and uuid from that token, so nothing sent here can claim to be someone else.
 * <p>
 * The call itself is {@link RelayCalls#call} (shared client, token reuse, every future completes with a Reply).
 * {@link #calls()} is the testkit's record of what was attempted, written before the network is touched, so it reads the
 * same offline.
 * <p>
 * Service key {@code lfg-relay} (read on every call) points just these endpoints at a fake, on top of {@code relay}.
 */
public final class LfgRelay {

    private static final ArrayDeque<String> CALLS = new ArrayDeque<>();

    private LfgRelay() {
    }

    /** Test hook: "METHOD /path {body}" for the last 50 calls attempted, oldest first. */
    public static synchronized List<String> calls() {
        return new ArrayList<>(CALLS);
    }

    public static synchronized void clearCallsForTest() {
        CALLS.clear();
    }

    private static synchronized void record(String line) {
        CALLS.addLast(line);
        while (CALLS.size() > 50) {
            CALLS.removeFirst();
        }
    }

    public static CompletableFuture<RelayCalls.Reply> listings(LfgCategory category) {
        return call("GET", "/lfg/listings" + (category == null ? "" : "?category=" + category.id), null);
    }

    public static CompletableFuture<RelayCalls.Reply> me() {
        return call("GET", "/lfg/me", null);
    }

    public static CompletableFuture<RelayCalls.Reply> upsert(JsonObject listing) {
        return call("POST", "/lfg/listing", listing);
    }

    public static CompletableFuture<RelayCalls.Reply> delete() {
        return call("DELETE", "/lfg/listing", null);
    }

    public static CompletableFuture<RelayCalls.Reply> join(String listingId) {
        JsonObject b = new JsonObject();
        b.addProperty("listingId", listingId);
        return call("POST", "/lfg/join", b);
    }

    public static CompletableFuture<RelayCalls.Reply> respond(String requestId, boolean accept) {
        JsonObject b = new JsonObject();
        b.addProperty("requestId", requestId);
        b.addProperty("accept", accept);
        return call("POST", "/lfg/respond", b);
    }

    private static CompletableFuture<RelayCalls.Reply> call(String method, String path, JsonObject body) {
        record(method + " " + path + (body == null ? "" : " " + body));
        return RelayCalls.call("lfg-relay", method, path, body);
    }
}
