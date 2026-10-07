package com.killer560.hub.util;

import org.slf4j.Logger;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * The one place every external service's address is resolved, so a test harness can point the mod at local
 * fakes or cut it off from the internet. With none of the properties below set, {@link #url} returns its
 * default argument unchanged and the send helpers just call through, so normal play is byte-for-byte what it
 * was before this class existed.
 *
 * <h2>Properties</h2>
 * <ul>
 *   <li>{@code -Dkiller560.net.<service>=http://127.0.0.1:PORT} replaces the scheme and host (and port) of every
 *       URL that service uses, keeping the path and query. A path on the override is prepended, so
 *       {@code http://127.0.0.1:8080/hypixel} turns {@code https://api.hypixel.net/v2/skyblock/bazaar} into
 *       {@code http://127.0.0.1:8080/hypixel/v2/skyblock/bazaar}. A {@code ws://}/{@code wss://} default given an
 *       {@code http://} override (or the reverse) keeps its own socket-ness: {@code http} becomes {@code ws}.</li>
 *   <li>{@code -Dkiller560.net.offline=true} makes every call through {@link #send}, {@link #sendAsync},
 *       {@link #open} and {@link #webSocket} fail at once with a {@link ConnectException}, the same error a dead
 *       network gives, so each caller's existing failure path runs. A URL that was redirected by a service
 *       override is still allowed through, so a harness can run offline with only its own fakes reachable.</li>
 * </ul>
 *
 * <h2>Service keys</h2>
 * <ul>
 *   <li>{@code hypixel} - api.hypixel.net: auctions, bazaar, items, collections, skills, election, profiles,
 *       museum, garden, player</li>
 *   <li>{@code noamm} - api.noamm.org: room database version/download and the bridge auth</li>
 *   <li>{@code noamm-ws} - ws.noamm.org: the NoammAddons bridge socket</li>
 *   <li>{@code odin-ws} - ws.odtheking.com: the Odin bridge socket</li>
 *   <li>{@code devonian-ws} - wss.docilelm.top: the Devonian bridge socket</li>
 *   <li>{@code pv-backend} - skyblock-pv.thatgravyboat.tech: the Profile Viewer's keyless backend,
 *       also the Party Finder Overlay's member stats</li>
 *   <li>{@code mojang} - api.mojang.com and sessionserver.mojang.com: name and profile lookups</li>
 *   <li>{@code minecraftservices} - api.minecraftservices.com: name lookups, account login and profile</li>
 *   <li>{@code microsoft} - login.microsoftonline.com: the account switcher's token step</li>
 *   <li>{@code xboxlive} - user.auth.xboxlive.com and xsts.auth.xboxlive.com: the account switcher's Xbox steps</li>
 *   <li>{@code github-raw} - raw.githubusercontent.com: NEU/meowdding repo files, SkyHanni graph</li>
 *   <li>{@code jsdelivr} - cdn.jsdelivr.net: SkyHanni graph fallback</li>
 *   <li>{@code coflnet} - sky.coflnet.com: lowest-BIN prices</li>
 *   <li>{@code github-api} - api.github.com: the update check</li>
 *   <li>{@code relay} - the relay worker (and the Supporters endpoints on it); overrides the configured base</li>
 *   <li>{@code pf-relay} - the relay's {@code /pf/stats} only (Party Finder stats cache), applied on top of
 *       {@code relay}, so a harness can fake it without touching Mod Chat or Supporters</li>
 *   <li>{@code lrclib} - lrclib.net: Spotify lyrics</li>
 *   <li>{@code google-translate} - translate.googleapis.com</li>
 *   <li>{@code mymemory} - api.mymemory.translated.net: the translate fallback</li>
 *   <li>{@code vosk} - alphacephei.com: the speech model download</li>
 *   <li>{@code maven-central} - repo1.maven.org: the Vosk speech engine jar, fetched on first use of Voice To Text</li>
 * </ul>
 *
 * <p>Not covered, deliberately: the Shorts player's DevTools client talks to 127.0.0.1 (a browser this mod
 * launched), Discord RPC is a local pipe, and the Proxy Client carries the game connection itself.
 */
public final class ModNet {

    private static final Logger LOGGER = ModLog.get("killer560smod-net");

    /** Prefix of every per-service override property. */
    public static final String PROPERTY_PREFIX = "killer560.net.";
    public static final String OFFLINE_PROPERTY = "killer560.net.offline";

    private ModNet() {
    }

    /** True when {@code -Dkiller560.net.offline=true}. Read on every call, so a harness can flip it mid-run. */
    public static boolean offline() {
        return Boolean.getBoolean(OFFLINE_PROPERTY);
    }

    /** The override for {@code service}, without a trailing slash, or {@code null} when none is set. */
    public static String override(String service) {
        String v = System.getProperty(PROPERTY_PREFIX + service);
        if (v == null || v.isBlank()) return null;
        v = v.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    /**
     * {@code defaultUrl} with its origin replaced by the {@code service} override, or {@code defaultUrl} itself
     * (the same string instance) when no override is set.
     */
    public static String url(String service, String defaultUrl) {
        String base = override(service);
        if (base == null) return defaultUrl;
        int schemeEnd = defaultUrl.indexOf("://");
        if (schemeEnd < 0) return defaultUrl;
        int pathStart = defaultUrl.indexOf('/', schemeEnd + 3);
        String rest = pathStart < 0 ? "" : defaultUrl.substring(pathStart);
        String defScheme = defaultUrl.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        boolean wantSocket = defScheme.startsWith("ws");
        String lower = base.toLowerCase(Locale.ROOT);
        if (wantSocket && lower.startsWith("http")) base = "ws" + base.substring(4);
        else if (!wantSocket && lower.startsWith("ws")) base = "http" + base.substring(2);
        return base + rest;
    }

    /** {@link #url} as a {@link URI}. */
    public static URI uri(String service, String defaultUrl) {
        return URI.create(url(service, defaultUrl));
    }

    private static boolean blocked(URI target) {
        if (!offline()) return false;
        String s = target.toString().toLowerCase(Locale.ROOT);
        for (String key : System.getProperties().stringPropertyNames()) {
            if (!key.startsWith(PROPERTY_PREFIX) || key.equals(OFFLINE_PROPERTY)) continue;
            String base = override(key.substring(PROPERTY_PREFIX.length()));
            if (base == null) continue;
            String origin = origin(base);
            // Compare host and port only: url() may have swapped http for ws.
            String hostPart = origin.substring(origin.indexOf("://") + 3);
            String targetHost = origin(s);
            targetHost = targetHost.substring(targetHost.indexOf("://") + 3);
            if (hostPart.equals(targetHost)) return false;
        }
        return true;
    }

    private static String origin(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        int schemeEnd = lower.indexOf("://");
        if (schemeEnd < 0) return "://" + lower;
        int end = lower.length();
        for (int i = schemeEnd + 3; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        return lower.substring(0, end);
    }

    private static ConnectException offlineError(URI target) {
        LOGGER.info("[TestHook] offline: refused {}", target);
        return new ConnectException("killer560.net.offline is set; refused " + target);
    }

    /** {@link HttpClient#send}, refused with a {@link ConnectException} when offline. */
    public static <T> HttpResponse<T> send(HttpClient client, HttpRequest request,
                                           HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        if (blocked(request.uri())) throw offlineError(request.uri());
        return client.send(request, handler);
    }

    /** {@link HttpClient#sendAsync}, completed exceptionally with a {@link ConnectException} when offline. */
    public static <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpClient client, HttpRequest request,
                                                                   HttpResponse.BodyHandler<T> handler) {
        if (blocked(request.uri())) return CompletableFuture.failedFuture(offlineError(request.uri()));
        return client.sendAsync(request, handler);
    }

    /** {@code URI.create(url).toURL().openConnection()}, refused with a {@link ConnectException} when offline. */
    public static URLConnection open(String url) throws IOException {
        URI target = URI.create(url);
        if (blocked(target)) throw offlineError(target);
        return target.toURL().openConnection();
    }

    /** {@link WebSocket.Builder#buildAsync}, completed exceptionally with a {@link ConnectException} when offline. */
    public static CompletableFuture<WebSocket> webSocket(WebSocket.Builder builder, URI target, WebSocket.Listener listener) {
        if (blocked(target)) return CompletableFuture.failedFuture(offlineError(target));
        return builder.buildAsync(target, listener);
    }
}
