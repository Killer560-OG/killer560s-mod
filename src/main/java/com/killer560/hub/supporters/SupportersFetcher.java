package com.killer560.hub.supporters;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.relay.RelayEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Fetches {@code GET /supporters} - see {@code SUPPORTERS-CONTRACT.md}. Public and unauthenticated, so this
 * never touches {@code com.killer560.hub.relay.RelayAuth}'s handshake; it only reuses {@link
 * RelayEndpoint#DEFAULT_BASE_URL} (the same deployed Worker Mod Chat talks to) and the same "one daemon
 * executor, never the client thread" HTTP style {@code com.killer560.hub.players.PlayerNames} and {@code
 * RelayClient} already use.
 * <p>
 * Runs once immediately, then every {@link #REFRESH_MS} (the contract's "at most every 5 minutes"). A failed
 * fetch (relay down, DNS, timeout, bad JSON) is logged once and otherwise silent - the caller ({@link
 * SupportersFeature}) simply keeps whatever it already had, in memory and on disk, per the contract's "if
 * the request fails, keep the last list".
 */
final class SupportersFetcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-supporters");
    private static final long REFRESH_MS = 5 * 60 * 1000L;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-supporters");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            // No .executor(EXECUTOR): fetchOnce blocks EXECUTOR's only thread in send(), so handing the client that
            // same thread for its own connection work meant the connect could never finish - every fetch timed out.
            .build();
    private static final long RETRY_AFTER_FAILURE_MS = 30_000L;

    private SupportersFetcher() {
    }

    /** {@code onFetched} runs on {@link #EXECUTOR} (never the client thread), only on a successful fetch. */
    static void start(BiConsumer<Integer, List<SupporterEntry>> onFetched) {
        EXECUTOR.execute(() -> runAndReschedule(onFetched));
    }

    /** One-off fetch outside the normal 5-minute cadence - used by {@link SupportersFeature#refreshNow()} so
     *  a player's own self-service save/clear (SUPPORTERS-CONTRACT-V2.md) shows up on this client right away
     *  instead of waiting for the next scheduled poll. Does not touch the schedule {@link #start} already set
     *  up - {@code runAndReschedule}'s own next {@code EXECUTOR.schedule} call is untouched, so this can only
     *  ever add one extra fetch, never duplicate or drop the recurring one. */
    static void fetchNow(BiConsumer<Integer, List<SupporterEntry>> onFetched) {
        EXECUTOR.execute(() -> fetchOnce(onFetched));
    }

    /** Every 5 minutes after a good fetch, but after 30 seconds when one fails, so a startup hiccup does not leave
     *  everyone without names for five minutes. */
    private static void runAndReschedule(BiConsumer<Integer, List<SupporterEntry>> onFetched) {
        boolean ok = fetchOnce(onFetched);
        EXECUTOR.schedule(() -> runAndReschedule(onFetched), ok ? REFRESH_MS : RETRY_AFTER_FAILURE_MS, TimeUnit.MILLISECONDS);
    }

    private static boolean fetchOnce(BiConsumer<Integer, List<SupporterEntry>> onFetched) {
        try {
            String base = RelayEndpoint.normalise(RelayEndpoint.DEFAULT_BASE_URL);
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/supporters"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", "Killer560sMod-Supporters/1.0")
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                LOGGER.info("[Supporters] Fetch failed: HTTP {}", resp.statusCode());
                return false;
            }
            JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
            int version = body.has("version") ? body.get("version").getAsInt() : -1;
            List<SupporterEntry> entries = parseSupporters(body.get("supporters"));
            onFetched.accept(version, entries);
            return true;
        } catch (Exception e) {
            LOGGER.info("[Supporters] Fetch error: {}", e.toString());
            return false;
        }
    }

    private static List<SupporterEntry> parseSupporters(JsonElement el) {
        List<SupporterEntry> out = new ArrayList<>();
        if (el == null || !el.isJsonArray()) {
            return out;
        }
        for (JsonElement entryEl : el.getAsJsonArray()) {
            try {
                if (entryEl == null || !entryEl.isJsonObject()) {
                    continue;
                }
                JsonObject o = entryEl.getAsJsonObject();
                UUID uuid = UUID.fromString(o.get("uuid").getAsString());
                String name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : "";
                float scale = o.has("scale") && !o.get("scale").isJsonNull() ? o.get("scale").getAsFloat() : 1.0f;
                out.add(new SupporterEntry(uuid, name, scale));
            } catch (Exception badEntry) {
                // one malformed entry from the relay must not drop the rest of the list
            }
        }
        return out;
    }
}
