package com.killer560.hub.lfg;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.relay.RelayAuth;
import com.killer560.hub.relay.RelayEndpoint;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModNet;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The relay's {@code /lfg/*} endpoints (killer560s-mod-relay PROTOCOL.md "Custom Party Finder"), authenticated the way
 * Supporters self-service is ({@link RelayAuth}: the Mojang chat key signs a relay challenge; no password, no API key).
 * The relay stamps the caller's name and uuid from that token, so nothing sent here can claim to be someone else.
 * <p>
 * Same shape as {@code SupportersSelfService}: its own client and daemon pool, {@code sendAsync} only, and every future
 * completes normally with a {@link Reply} (status -1 when no HTTP answer came). {@link #calls()} is the testkit's record
 * of what was attempted, written before the network is touched, so it reads the same offline.
 * <p>
 * Service key {@code lfg-relay} (read on every call) points just these endpoints at a fake, on top of {@code relay}.
 */
public final class LfgRelay {

    private static final Logger LOGGER = ModLog.get("killer560smod-lfg");
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final long TOKEN_REUSE_MARGIN_MS = 5 * 60_000L;

    private static final ExecutorService IO = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "killer560smod-lfg");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .executor(IO)
            .build();

    private static volatile RelayAuth.Token cachedToken;
    private static volatile String cachedTokenBase = "";

    private static final ArrayDeque<String> CALLS = new ArrayDeque<>();

    /** One relay answer. {@code body} is the parsed object (an empty one when there was none). */
    public record Reply(int status, JsonObject body, String error) {
        public boolean ok() {
            return status == 200;
        }
    }

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

    public static CompletableFuture<Reply> listings(LfgCategory category) {
        return call("GET", "/lfg/listings" + (category == null ? "" : "?category=" + category.id), null);
    }

    public static CompletableFuture<Reply> me() {
        return call("GET", "/lfg/me", null);
    }

    public static CompletableFuture<Reply> upsert(JsonObject listing) {
        return call("POST", "/lfg/listing", listing);
    }

    public static CompletableFuture<Reply> delete() {
        return call("DELETE", "/lfg/listing", null);
    }

    public static CompletableFuture<Reply> join(String listingId) {
        JsonObject b = new JsonObject();
        b.addProperty("listingId", listingId);
        return call("POST", "/lfg/join", b);
    }

    public static CompletableFuture<Reply> respond(String requestId, boolean accept) {
        JsonObject b = new JsonObject();
        b.addProperty("requestId", requestId);
        b.addProperty("accept", accept);
        return call("POST", "/lfg/respond", b);
    }

    private static CompletableFuture<Reply> call(String method, String path, JsonObject body) {
        record(method + " " + path + (body == null ? "" : " " + body));
        String base = RelayEndpoint.normalise(RelayEndpoint.DEFAULT_BASE_URL);
        if (!RelayEndpoint.isUsable(base)) {
            return CompletableFuture.completedFuture(new Reply(-1, new JsonObject(), "relay unavailable"));
        }
        String url = ModNet.url("lfg-relay", base + path);
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8);
        return token(base)
                .thenCompose(t -> ModNet.sendAsync(HTTP, HttpRequest.newBuilder(URI.create(url))
                                .timeout(TIMEOUT)
                                .header("Authorization", "Bearer " + t.value())
                                .header("Content-Type", "application/json")
                                .method(method, publisher)
                                .build(),
                        HttpResponse.BodyHandlers.ofString()))
                .thenApply(LfgRelay::parse)
                .exceptionally(e -> new Reply(-1, new JsonObject(), reason(e)));
    }

    static Reply parse(HttpResponse<String> response) {
        JsonObject body;
        try {
            body = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (Exception e) {
            body = new JsonObject();
        }
        String error = response.statusCode() == 200 ? null
                : body.has("error") && body.get("error").isJsonPrimitive() ? body.get("error").getAsString()
                : "relay returned HTTP " + response.statusCode();
        return new Reply(response.statusCode(), body, error);
    }

    private static CompletableFuture<RelayAuth.Token> token(String base) {
        RelayAuth.Token cached = cachedToken;
        if (cached != null && base.equals(cachedTokenBase)
                && cached.expiresAtMs() - System.currentTimeMillis() > TOKEN_REUSE_MARGIN_MS) {
            return CompletableFuture.completedFuture(cached);
        }
        return RelayAuth.authenticate(HTTP, base).thenApply(issued -> {
            cachedToken = issued;
            cachedTokenBase = base;
            return issued;
        });
    }

    private static String reason(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof RelayAuth.RelayAuthException) {
            return cause.getMessage();
        }
        LOGGER.info("[PartyFinder] relay call failed: {}", cause == null ? error : cause.toString());
        String message = cause == null ? null : cause.getMessage();
        return message == null || message.isBlank() ? "couldn't reach the relay" : message;
    }
}
