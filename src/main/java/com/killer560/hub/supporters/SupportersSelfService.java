package com.killer560.hub.supporters;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.relay.RelayAuth;
import com.killer560.hub.relay.RelayEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The player's own half of SUPPORTERS-CONTRACT-V2.md's "Supporter self-service" section - {@code GET}/
 * {@code POST}/{@code DELETE /supporters/me}, authenticated exactly the way {@link
 * com.killer560.hub.relay.RelayClient} already authenticates Mod Chat: {@link RelayAuth#authenticate}
 * (Mojang profile keypair signing a relay-issued challenge - no password, API key or Hypixel key). Used by
 * {@code com.killer560.hub.gui.tab.SupportersTab}'s "My Supporter Name" section.
 * <p>
 * <b>Own {@link HttpClient} and own daemon pool</b>, entirely separate from {@link SupportersFetcher}'s
 * client. {@code SupportersFetcher} deliberately builds its client with NO executor because it calls the
 * blocking {@code HttpClient.send()} - handing that same pool to a client here that also called blocking
 * {@code send()} would be exactly the "executor thread stuck waiting inside its own client's blocking call"
 * deadlock that has already hit this codebase twice. This class avoids the whole class of bug the other way
 * around instead: it has its own pool AND only ever calls {@code sendAsync}, never the blocking overload -
 * same shape as {@code RelayClient}'s own {@code HTTP} field.
 * <p>
 * Every public method returns a {@link CompletableFuture} that never completes exceptionally: any failure
 * (timeout, DNS, a relay error, unparsable JSON) is turned into a result record with {@code ok=false} and a
 * short human-readable reason, so {@code SupportersTab} never needs a {@code catch} of its own. Nothing here
 * ever runs on the render thread.
 */
public final class SupportersSelfService {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-supporters");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    /** Same reuse margin {@code RelayClient} uses before it bothers re-authenticating a still-valid token. */
    private static final long TOKEN_REUSE_MARGIN_MS = 5 * 60_000L;

    private static final ExecutorService IO = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "killer560smod-supporters-me");
        thread.setDaemon(true);
        return thread;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(IO)
            .build();

    // Read/written from whichever IO thread a request happens to land on; a redundant re-auth in the rare
    // race is harmless (just an extra handshake), so plain volatiles are enough here.
    private static volatile RelayAuth.Token cachedToken;
    private static volatile String cachedTokenBaseUrl = "";

    private SupportersSelfService() {
    }

    /** {@code GET /supporters/me} response. {@code name} is {@code null} when the account is whitelisted but
     *  has no custom name set. */
    public record MeResult(boolean ok, boolean whitelisted, String name, double scale, String error) {
        static MeResult failure(String error) {
            return new MeResult(false, false, null, 1.0, error);
        }
    }

    /** {@code POST}/{@code DELETE /supporters/me} response. {@code httpStatus} is {@code -1} when the request
     *  never got an HTTP response at all (network/auth failure rather than a relay-issued error). */
    public record SaveResult(boolean ok, String name, double scale, String error, int httpStatus) {
        static SaveResult failure(String error, int httpStatus) {
            return new SaveResult(false, null, 1.0, error, httpStatus);
        }
    }

    public static CompletableFuture<MeResult> fetchMe() {
        String base = RelayEndpoint.normalise(RelayEndpoint.DEFAULT_BASE_URL);
        if (!RelayEndpoint.isUsable(base)) {
            return CompletableFuture.completedFuture(MeResult.failure("relay unavailable"));
        }
        return token(base)
                .thenCompose(t -> HTTP.sendAsync(
                        HttpRequest.newBuilder(URI.create(base + "/supporters/me"))
                                .timeout(REQUEST_TIMEOUT)
                                .header("Authorization", "Bearer " + t.value())
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString()))
                .thenApply(SupportersSelfService::parseMe)
                .exceptionally(e -> MeResult.failure(reason(e)));
    }

    public static CompletableFuture<SaveResult> save(String rawName, double scale) {
        JsonObject body = new JsonObject();
        body.addProperty("name", rawName == null ? "" : rawName);
        body.addProperty("scale", scale);
        return request("POST", HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
    }

    public static CompletableFuture<SaveResult> clear() {
        return request("DELETE", HttpRequest.BodyPublishers.noBody());
    }

    private static CompletableFuture<SaveResult> request(String method, HttpRequest.BodyPublisher body) {
        String base = RelayEndpoint.normalise(RelayEndpoint.DEFAULT_BASE_URL);
        if (!RelayEndpoint.isUsable(base)) {
            return CompletableFuture.completedFuture(SaveResult.failure("relay unavailable", -1));
        }
        return token(base)
                .thenCompose(t -> HTTP.sendAsync(
                        HttpRequest.newBuilder(URI.create(base + "/supporters/me"))
                                .timeout(REQUEST_TIMEOUT)
                                .header("Authorization", "Bearer " + t.value())
                                .header("Content-Type", "application/json")
                                .method(method, body)
                                .build(),
                        HttpResponse.BodyHandlers.ofString()))
                .thenApply(SupportersSelfService::parseSave)
                .exceptionally(e -> SaveResult.failure(reason(e), -1));
    }

    private static MeResult parseMe(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            return MeResult.failure(errorOr(response, "relay returned HTTP " + response.statusCode()));
        }
        try {
            JsonObject obj = JsonParser.parseString(response.body()).getAsJsonObject();
            boolean whitelisted = obj.has("whitelisted") && !obj.get("whitelisted").isJsonNull()
                    && obj.get("whitelisted").getAsBoolean();
            String name = obj.has("name") && !obj.get("name").isJsonNull() ? obj.get("name").getAsString() : null;
            double scale = obj.has("scale") && !obj.get("scale").isJsonNull() ? obj.get("scale").getAsDouble() : 1.0;
            return new MeResult(true, whitelisted, name, scale, null);
        } catch (Exception e) {
            return MeResult.failure("relay sent a bad response");
        }
    }

    /** 429/403 are given fixed, friendlier wording per SUPPORTERS-CONTRACT-V2.md's own phrasing ("wait a few
     *  seconds", "not whitelisted") rather than whatever the relay's JSON body happens to say. */
    private static SaveResult parseSave(HttpResponse<String> response) {
        int code = response.statusCode();
        if (code == 429) {
            return SaveResult.failure("You changed this too recently - wait a few seconds and try again.", code);
        }
        if (code == 403) {
            return SaveResult.failure("not whitelisted", code);
        }
        if (code != 200) {
            return SaveResult.failure(errorOr(response, "relay returned HTTP " + code), code);
        }
        try {
            JsonObject obj = JsonParser.parseString(response.body()).getAsJsonObject();
            String name = obj.has("name") && !obj.get("name").isJsonNull() ? obj.get("name").getAsString() : "";
            double scale = obj.has("scale") && !obj.get("scale").isJsonNull() ? obj.get("scale").getAsDouble() : 1.0;
            return new SaveResult(true, name, scale, null, code);
        } catch (Exception e) {
            return SaveResult.failure("relay sent a bad response", code);
        }
    }

    private static String errorOr(HttpResponse<String> response, String fallback) {
        try {
            JsonObject obj = JsonParser.parseString(response.body()).getAsJsonObject();
            if (obj.has("error") && obj.get("error").isJsonPrimitive()) {
                return obj.get("error").getAsString();
            }
        } catch (Exception ignored) {
            // fall through to the fallback below
        }
        return fallback;
    }

    private static CompletableFuture<RelayAuth.Token> token(String base) {
        RelayAuth.Token cached = cachedToken;
        if (cached != null && base.equals(cachedTokenBaseUrl)
                && cached.expiresAtMs() - System.currentTimeMillis() > TOKEN_REUSE_MARGIN_MS) {
            return CompletableFuture.completedFuture(cached);
        }
        return RelayAuth.authenticate(HTTP, base).thenApply(issued -> {
            cachedToken = issued;
            cachedTokenBaseUrl = base;
            return issued;
        });
    }

    /** One short phrase a player can act on, same idea as {@code RelayClient}'s own {@code reason(Throwable)}. */
    private static String reason(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof RelayAuth.RelayAuthException) {
            return cause.getMessage();
        }
        LOGGER.info("[Supporters] /supporters/me request failed: {}", cause == null ? error : cause.toString());
        String message = cause == null ? null : cause.getMessage();
        return message == null || message.isBlank() ? "couldn't reach the relay" : message;
    }
}
