package com.killer560.hub.bugreport;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.banreport.BanReportFeature;
import com.killer560.hub.relay.RelayAuth;
import com.killer560.hub.relay.RelayEndpoint;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModNet;
import org.slf4j.Logger;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The bug report screen's "Save and Post": sends the saved zip, the title, the description and the build to the relay
 * ({@code POST /bug-report}, ModNet service {@code bugreport-relay}), signed in the same way as crash reports
 * ({@link RelayAuth}); the relay makes the post in the Discord's bug report forum with the bot. No token or webhook is in
 * the mod. A zip has no size limit and is never trimmed: one up to {@link #PART} bytes goes in one request, a bigger one
 * is streamed from the file in parts of at most that size, never more than one part in memory. Request 1 declares the
 * parts, the total and the file names and carries part 1 (the relay makes the post with it as bug-report.zip.001 and
 * answers a session id); each later part is {@code POST /bug-report/part?session=ID&n=N} with the raw bytes. A failed
 * request is tried once more. Runs on the caller's worker thread; the result goes to {@code done} (ok, thread link or message).
 */
public final class BugReportPost {

    private static final Logger LOGGER = ModLog.get("killer560smod-bugreport");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    /** The most raw zip bytes one request carries (the relay's MAX_PART; Discord refuses an attachment over 10 MB). */
    static final int PART = 7_500_000;
    /** The relay takes at most this many names for a multi-part report. */
    private static final int MAX_NAMES = 400;
    /** A gametest's bearer token instead of the Mojang chat-key login (only honored inside a client gametest). */
    private static volatile String testToken;
    /** The last request-1 body sent, for the testkit (null until one was sent). */
    private static volatile String lastBodyForTest;
    /** The part size the testkit asks for instead of {@link #PART} (0 = normal); only read inside a client gametest. */
    private static volatile int partForTest;

    private BugReportPost() {
    }

    private record Answer(int status, JsonObject json) {
        String error() {
            return json.has("error") ? json.get("error").getAsString() : "the relay answered HTTP " + status + ".";
        }
    }

    /** Never throws; every outcome goes to {@code done}, each part to {@code progress}. */
    static void send(Path zip, List<String> names, String title, String description, Consumer<String> progress,
                     BiConsumer<Boolean, String> done) {
        String base = RelayEndpoint.normalise(RelayEndpoint.DEFAULT_BASE_URL);
        if (!RelayEndpoint.isUsable(base)) {
            done.accept(false, "the relay is not available.");
            return;
        }
        boolean test = System.getProperty("fabric.client.gametest") != null;
        int part = test && partForTest > 0 ? partForTest : PART;
        int sent = 0;
        int parts = 1;
        try (InputStream in = Files.newInputStream(zip)) {
            long total = Files.size(zip);
            parts = (int) Math.max(1, (total + part - 1) / part);
            String[] token = {test && testToken != null ? testToken : RelayAuth.authenticate(HTTP, base).thenApply(RelayAuth.Token::value).join()};
            JsonObject o = new JsonObject();
            o.addProperty("title", cap(title.strip(), 100));
            o.addProperty("description", cap(description.strip(), 3000));
            BanReportFeature.addBuild(o);
            o.addProperty("zip", Base64.getEncoder().encodeToString(in.readNBytes((int) Math.min(part, total))));
            if (parts > 1) {
                o.addProperty("parts", parts);
                o.addProperty("total", total);
                o.addProperty("partSize", part);
                JsonArray list = new JsonArray();
                names.stream().limit(MAX_NAMES).forEach(list::add);
                o.add("names", list);
            }
            String body = o.toString();
            lastBodyForTest = body;
            progress.accept(parts > 1 ? "Posting part 1 of " + parts + "..." : "Posting to the Discord...");
            String url = ModNet.url("bugreport-relay", base + "/bug-report");
            Answer first = call(url, token, base, test, "application/json", () -> HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (first.status != 200) {
                done.accept(false, first.error());
                return;
            }
            String link = first.json.has("url") ? first.json.get("url").getAsString() : null;
            boolean safeLink = link != null && link.startsWith("https://discord.com/channels/");
            String session = first.json.has("session") ? first.json.get("session").getAsString() : null;
            if (parts > 1 && session == null) {
                done.accept(false, first.json.has("duplicate") ? "it was already posted." : "the relay did not start a multi-part post.");
                return;
            }
            sent = 1;
            for (int n = 2; n <= parts; n++) {
                progress.accept("Posting part " + n + " of " + parts + "...");
                byte[] chunk = in.readNBytes(part);
                String partUrl = ModNet.url("bugreport-relay", base + "/bug-report/part?session=" + session + "&n=" + n);
                Answer a = call(partUrl, token, base, test, "application/octet-stream", () -> HttpRequest.BodyPublishers.ofByteArray(chunk));
                if (a.status != 200) {
                    done.accept(false, "stopped at part " + n + " of " + parts + " (" + sent + " are in the thread"
                            + (safeLink ? ", " + link : "") + "): " + a.error());
                    return;
                }
                sent = n;
            }
            done.accept(true, first.json.has("duplicate") ? "it was already posted." : safeLink ? link : "it is in the bug report forum.");
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            LOGGER.info("[BugReport] post failed: {}", c.toString());
            String why = c instanceof RelayAuth.RelayAuthException ? c.getMessage() : "could not reach the relay.";
            done.accept(false, parts > 1 && sent > 0 ? "stopped after part " + sent + " of " + parts + ": " + why : why);
        }
    }

    /** One request, tried once more after a network error, a 5xx or an expired token (then signed in again). */
    private static Answer call(String url, String[] token, String base, boolean test, String type, Supplier<HttpRequest.BodyPublisher> body)
            throws Exception {
        Answer last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpResponse<String> res = ModNet.sendAsync(HTTP, HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(120))
                                .header("Authorization", "Bearer " + token[0])
                                .header("Content-Type", type)
                                .POST(body.get())
                                .build(),
                        HttpResponse.BodyHandlers.ofString()).join();
                JsonObject j = new JsonObject();
                try {
                    j = JsonParser.parseString(res.body()).getAsJsonObject();
                } catch (RuntimeException ignored) {
                    // no JSON body
                }
                LOGGER.info("[BugReport] relay answered HTTP {}", res.statusCode());
                last = new Answer(res.statusCode(), j);
                if (res.statusCode() == 401 && !test) {
                    token[0] = RelayAuth.authenticate(HTTP, base).thenApply(RelayAuth.Token::value).join();
                } else if (res.statusCode() < 500) {
                    return last;
                }
            } catch (RuntimeException e) {
                if (attempt == 1) {
                    throw e;
                }
            }
        }
        return last;
    }

    private static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    public static void setTokenForTest(String token) {
        testToken = token;
    }

    public static void setPartSizeForTest(int bytes) {
        partForTest = bytes;
    }

    public static String lastBodyForTest() {
        return lastBodyForTest;
    }
}
