package com.killer560.hub.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.slf4j.Logger;

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
 * One authenticated JSON call to the relay (bearer token from {@link RelayAuth}), shared by the Custom Party Finder
 * ({@code lfg/LfgRelay}) and Discord Link ({@code discordlink/DiscordLink}). Its own client and daemon pool,
 * {@code sendAsync} only, so nothing here ever runs on the render thread; every future completes normally with a
 * {@link Reply} (status -1 when no HTTP answer came). The token is reused until five minutes before it expires, for the
 * same account only (an Account Switcher swap gets a new one).
 */
public final class RelayCalls {

    private static final Logger LOGGER = ModLog.get("killer560smod-relay");
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final long TOKEN_REUSE_MARGIN_MS = 5 * 60_000L;

    private static final ExecutorService IO = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "killer560smod-relay-call");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .executor(IO)
            .build();

    private static volatile RelayAuth.Token cachedToken;
    private static volatile String cachedTokenKey = "";
    /** A gametest's bearer token instead of the Mojang chat-key login (only honored inside a client gametest). */
    private static volatile String testToken;

    /** One relay answer. {@code body} is the parsed object (an empty one when there was none). */
    public record Reply(int status, JsonObject body, String error) {
        public boolean ok() {
            return status == 200;
        }
    }

    private RelayCalls() {
    }

    /** {@code method path} on the relay, {@code service} being the ModNet key a harness may point at a fake. */
    public static CompletableFuture<Reply> call(String service, String method, String path, JsonObject body) {
        String base = RelayEndpoint.normalise(RelayEndpoint.DEFAULT_BASE_URL);
        if (!RelayEndpoint.isUsable(base)) {
            return CompletableFuture.completedFuture(new Reply(-1, new JsonObject(), "relay unavailable"));
        }
        String url = ModNet.url(service, base + path);
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8);
        return token(base)
                .thenCompose(t -> ModNet.sendAsync(HTTP, HttpRequest.newBuilder(URI.create(url))
                                .timeout(TIMEOUT)
                                .header("Authorization", "Bearer " + t)
                                .header("Content-Type", "application/json")
                                .method(method, publisher)
                                .build(),
                        HttpResponse.BodyHandlers.ofString()))
                .thenApply(RelayCalls::parse)
                .exceptionally(e -> new Reply(-1, new JsonObject(), reason(e)));
    }

    public static Reply parse(HttpResponse<String> response) {
        JsonObject body;
        try {
            body = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            body = new JsonObject();
        }
        String error = response.statusCode() == 200 ? null
                : body.has("error") && body.get("error").isJsonPrimitive() ? body.get("error").getAsString()
                : "relay returned HTTP " + response.statusCode();
        return new Reply(response.statusCode(), body, error);
    }

    /** The relay base URL and a bearer token for it (the same cached token {@link #call} uses), for a socket. */
    public static CompletableFuture<String> bearer() {
        String base = RelayEndpoint.normalise(RelayEndpoint.DEFAULT_BASE_URL);
        if (!RelayEndpoint.isUsable(base)) return CompletableFuture.failedFuture(new IllegalStateException("relay unavailable"));
        return token(base);
    }

    private static CompletableFuture<String> token(String base) {
        String forTest = tokenForTest();
        if (forTest != null) {
            return CompletableFuture.completedFuture(forTest);
        }
        Minecraft mc = Minecraft.getInstance();
        User user = mc == null ? null : mc.getUser();
        String key = base + "|" + (user == null ? "" : user.getProfileId());
        RelayAuth.Token cached = cachedToken;
        if (cached != null && key.equals(cachedTokenKey)
                && cached.expiresAtMs() - System.currentTimeMillis() > TOKEN_REUSE_MARGIN_MS) {
            return CompletableFuture.completedFuture(cached.value());
        }
        return RelayAuth.authenticate(HTTP, base).thenApply(issued -> {
            cachedToken = issued;
            cachedTokenKey = key;
            return issued.value();
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
        LOGGER.info("[Relay] call failed: {}", cause == null ? error : cause.toString());
        String message = cause == null ? null : cause.getMessage();
        return message == null || message.isBlank() ? "couldn't reach the relay" : message;
    }

    /** Test hook: the bearer token sent instead of the chat-key login; null for the real login. Also the relay sockets'. */
    public static void setTokenForTest(String token) {
        testToken = token;
    }

    /** The test token while a client game test runs, else null. */
    static String tokenForTest() {
        return System.getProperty("fabric.client.gametest") != null ? testToken : null;
    }
}
