package com.killer560.hub.bugreport;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.banreport.BanReportFeature;
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
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * The bug report screen's "Save and Post" (bugreportmenu-1122): sends the saved report's zip, its title and description and
 * the build to the relay ({@code POST /bug-report}, ModNet service {@code bugreport-relay}), signed in the same way as
 * crash reports ({@link RelayAuth}), and the relay posts it in the Discord's bug report forum with the bot. No token or
 * webhook is in the mod. A zip over the relay's cap is rebuilt with the logs cut to their newest lines until it fits,
 * and the answer says so. Runs off the client thread; the result goes to {@code done} (ok, thread link or message).
 */
public final class BugReportPost {

    private static final Logger LOGGER = ModLog.get("killer560smod-bugreport");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    /** A gametest's bearer token instead of the Mojang chat-key login (only honored inside a client gametest). */
    private static volatile String testToken;
    /** The last request body sent, for the testkit (null until one was sent). */
    private static volatile String lastBodyForTest;

    private BugReportPost() {
    }

    static void send(Map<String, String> files, String title, String description, BiConsumer<Boolean, String> done) {
        byte[] zip;
        boolean trimmed = false;
        try {
            zip = BugReportFeature.zip(files, Integer.MAX_VALUE);
            for (int budget = 1_000_000; zip.length > BugReportFeature.POST_LIMIT && budget >= 0; budget = budget == 0 ? -1 : budget / 4) {
                zip = BugReportFeature.zip(files, budget);
                trimmed = true;
            }
        } catch (Exception e) {
            done.accept(false, "could not build the zip: " + e.getMessage());
            return;
        }
        if (zip.length > BugReportFeature.POST_LIMIT) {
            done.accept(false, "the report is too big to post even without its logs; drag the zip into Discord.");
            return;
        }
        String base = RelayEndpoint.normalise(RelayEndpoint.DEFAULT_BASE_URL);
        if (!RelayEndpoint.isUsable(base)) {
            done.accept(false, "the relay is not available.");
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("title", cap(title.strip(), 100));
        o.addProperty("description", cap(description.strip(), 3000));
        BanReportFeature.addBuild(o);
        o.addProperty("zip", Base64.getEncoder().encodeToString(zip));
        String body = o.toString();
        lastBodyForTest = body;
        String note = trimmed ? " (logs cut to their newest lines to fit the upload)" : "";
        String url = ModNet.url("bugreport-relay", base + "/bug-report");
        String forTest = System.getProperty("fabric.client.gametest") != null ? testToken : null;
        CompletableFuture<String> token = forTest != null
                ? CompletableFuture.completedFuture(forTest)
                : RelayAuth.authenticate(HTTP, base).thenApply(RelayAuth.Token::value);
        token.thenCompose(t -> ModNet.sendAsync(HTTP, HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(60))
                                .header("Authorization", "Bearer " + t)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                                .build(),
                        HttpResponse.BodyHandlers.ofString()))
                .whenComplete((res, err) -> {
                    if (err != null) {
                        Throwable c = err.getCause() != null ? err.getCause() : err;
                        LOGGER.info("[BugReport] post failed: {}", c.toString());
                        done.accept(false, c instanceof RelayAuth.RelayAuthException ? c.getMessage() : "could not reach the relay.");
                        return;
                    }
                    String error = null;
                    String link = null;
                    boolean duplicate = false;
                    try {
                        JsonObject j = JsonParser.parseString(res.body()).getAsJsonObject();
                        error = j.has("error") ? j.get("error").getAsString() : null;
                        link = j.has("url") ? j.get("url").getAsString() : null;
                        duplicate = j.has("duplicate") && j.get("duplicate").getAsBoolean();
                    } catch (RuntimeException ignored) {
                        // no JSON body
                    }
                    LOGGER.info("[BugReport] relay answered HTTP {}{}", res.statusCode(), error == null ? "" : " " + error);
                    if (res.statusCode() == 200) {
                        boolean safeLink = link != null && link.startsWith("https://discord.com/channels/");
                        done.accept(true, duplicate ? "it was already posted." : (safeLink ? link : "it is in the bug report forum.") + note);
                    } else {
                        done.accept(false, error == null ? "the relay answered HTTP " + res.statusCode() + "." : error);
                    }
                });
    }

    private static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    public static void setTokenForTest(String token) {
        testToken = token;
    }

    public static String lastBodyForTest() {
        return lastBodyForTest;
    }
}
