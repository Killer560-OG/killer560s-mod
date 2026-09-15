package com.killer560.hub.profileviewer.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.profileviewer.ProfileViewerConfig;
import com.killer560.hub.profileviewer.data.SbProfile;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.yggdrasil.ProfileResult;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * All network access for the Profile Viewer. Everything here runs on a small daemon executor / the JDK
 * HttpClient's async pool - never on the render thread - with connect + request timeouts.
 * <p>
 * <b>Data sources.</b> The modern reference mod, SkyBlock Profile Viewer (meowdding/skyblock-pv), does
 * not ask users for a Hypixel key: it talks to its own open-source backend
 * (meowdding/skyblock-pv-backend, {@code https://skyblock-pv.thatgravyboat.tech}) which proxies Hypixel's
 * {@code /v2/skyblock/profiles} response unchanged. Clients authenticate the same way a server login does:
 * the client calls Mojang's session {@code joinServer} with a random server id, then the backend checks
 * Mojang's {@code hasJoined} for that username + id and hands back a 24h token. The access token itself
 * only ever goes to Mojang. This mod identifies itself honestly in the User-Agent.
 * <p>
 * Fallback order ({@link ProfileViewerConfig.Source}):
 * <ul>
 *   <li>AUTO: your own key (if set) -> SkyBlockPV backend -> the mod's built-in key.</li>
 *   <li>HYPIXEL: your own key if set, else the mod's built-in key.</li>
 *   <li>BACKEND: SkyBlockPV backend only.</li>
 * </ul>
 * Every successful profile list is cached per player for 5 minutes (same as skyblock-pv's CACHE_TIME).
 */
public final class ProfileViewerApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-profileviewer");

    public static final long CACHE_MS = 5 * 60 * 1000L;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final String BACKEND = "https://skyblock-pv.thatgravyboat.tech";
    private static final String HYPIXEL_PROFILES = "https://api.hypixel.net/v2/skyblock/profiles?uuid=";
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    private static final AtomicInteger THREADS = new AtomicInteger();
    public static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "killer560smod-profileviewer-" + THREADS.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(EXECUTOR)
            .build();

    /** For requests carrying a secret header (Hypixel API-Key, backend token): never follow a redirect, since
     *  the JDK client re-sends user headers to the redirect target. */
    private static final HttpClient HTTP_NO_REDIRECT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .executor(EXECUTOR)
            .build();

    /** Hard caps so a long session viewing many players can't grow these without bound (each cached
     *  result holds the full profile JSON, including every inventory blob). */
    private static final int MAX_CACHED_PROFILES = 16;
    private static final int MAX_CACHED_NAMES = 256;

    /** A fetch failure whose message is safe to show on screen (never contains a key). */
    public static final class ApiException extends RuntimeException {
        public ApiException(String message) {
            super(message, null, false, false);
        }
    }

    public record ResolvedPlayer(UUID uuid, String name) {
    }

    public record ProfilesResult(UUID uuid, List<SbProfile> profiles, String source, long fetchedAt) {
    }

    private record Cached(ProfilesResult result, long at) {
    }

    private static final Map<UUID, Cached> CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, CompletableFuture<ProfilesResult>> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final Map<String, ResolvedPlayer> NAME_CACHE = new ConcurrentHashMap<>();

    private static volatile String backendToken;
    private static volatile long backendTokenAt;

    private ProfileViewerApi() {
    }

    // ------------------------------------------------------------------ helpers

    private static String userAgent() {
        String version = FabricLoader.getInstance().getModContainer("killer560smod")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
        return "killer560s-mod/" + version + " (ProfileViewer; Minecraft 26.1.2)";
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", userAgent())
                .header("Accept", "application/json");
    }

    public static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    public static String messageFor(Throwable t) {
        t = unwrap(t);
        if (t instanceof ApiException) {
            return t.getMessage();
        }
        if (t instanceof java.net.http.HttpTimeoutException) {
            return "Request timed out - check your connection.";
        }
        if (t instanceof java.net.ConnectException || t instanceof java.nio.channels.UnresolvedAddressException
                || t instanceof java.net.UnknownHostException) {
            return "Couldn't reach the server - are you offline?";
        }
        if (t instanceof java.io.IOException) {
            return "Network error - are you offline?";
        }
        return "Something went wrong (" + t.getClass().getSimpleName() + ").";
    }

    private static JsonObject parseObject(String body) {
        try {
            JsonElement el = JsonParser.parseString(body);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** GET a keyless public JSON resource; completes with null on any failure. */
    public static CompletableFuture<JsonObject> getKeylessJson(String url) {
        try {
            return HTTP.sendAsync(request(url).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(res -> res.statusCode() == 200 ? parseObject(res.body()) : null)
                    .exceptionally(t -> null);
        } catch (Exception e) {
            // e.g. URI.create rejecting a malformed url - callers can be on the render thread.
            return CompletableFuture.completedFuture(null);
        }
    }

    // ------------------------------------------------------------------ names / skins

    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** Mojang name -> UUID (api.minecraftservices.com, falling back to api.mojang.com). Accepts a raw
     *  UUID too. */
    public static CompletableFuture<ResolvedPlayer> resolve(String input) {
        String trimmed = input == null ? "" : input.trim();
        UUID direct = parseUuid(trimmed);
        if (direct != null) {
            return CompletableFuture.completedFuture(new ResolvedPlayer(direct, trimmed));
        }
        if (!isValidName(trimmed)) {
            return CompletableFuture.failedFuture(new ApiException("\"" + trimmed + "\" isn't a valid Minecraft name."));
        }
        String key = trimmed.toLowerCase(Locale.ROOT);
        ResolvedPlayer cached = NAME_CACHE.get(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return lookupName("https://api.minecraftservices.com/minecraft/profile/lookup/name/" + trimmed)
                .exceptionallyCompose(t -> {
                    Throwable cause = unwrap(t);
                    if (cause instanceof ApiException) {
                        return CompletableFuture.failedFuture(cause);
                    }
                    return lookupName("https://api.mojang.com/users/profiles/minecraft/" + trimmed);
                })
                .thenApply(p -> {
                    if (NAME_CACHE.size() >= MAX_CACHED_NAMES) {
                        NAME_CACHE.clear();
                    }
                    NAME_CACHE.put(key, p);
                    return p;
                });
    }

    private static CompletableFuture<ResolvedPlayer> lookupName(String url) {
        return HTTP.sendAsync(request(url).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(res -> {
                    if (res.statusCode() == 404 || res.statusCode() == 204) {
                        throw new ApiException("No Minecraft account with that name.");
                    }
                    if (res.statusCode() == 429) {
                        throw new CompletionException(new java.io.IOException("mojang rate limited"));
                    }
                    JsonObject obj = res.statusCode() == 200 ? parseObject(res.body()) : null;
                    UUID uuid = obj != null && obj.has("id") ? parseUuid(obj.get("id").getAsString()) : null;
                    if (uuid == null) {
                        throw new CompletionException(new java.io.IOException("mojang lookup failed " + res.statusCode()));
                    }
                    String name = obj.has("name") ? obj.get("name").getAsString() : "";
                    return new ResolvedPlayer(uuid, name);
                });
    }

    public static UUID parseUuid(String s) {
        if (s == null) {
            return null;
        }
        String hex = s.replace("-", "");
        if (hex.length() != 32 || !hex.matches("[0-9a-fA-F]{32}")) {
            return null;
        }
        try {
            return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16), Long.parseUnsignedLong(hex.substring(16), 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String dashless(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    /** Full profile (with the signed textures property) for the skin preview; null on failure. */
    public static CompletableFuture<GameProfile> fetchSkinProfile(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ProfileResult result = Minecraft.getInstance().services().sessionService().fetchProfile(uuid, false);
                return result == null ? null : result.profile();
            } catch (Exception e) {
                return null;
            }
        }, EXECUTOR);
    }

    // ------------------------------------------------------------------ profiles

    public static ProfilesResult getCached(UUID uuid) {
        Cached c = CACHE.get(uuid);
        return c != null && System.currentTimeMillis() - c.at() < CACHE_MS ? c.result() : null;
    }

    public static CompletableFuture<ProfilesResult> fetchProfiles(UUID uuid, boolean bypassCache) {
        if (!bypassCache) {
            ProfilesResult cached = getCached(uuid);
            if (cached != null) {
                return CompletableFuture.completedFuture(cached);
            }
        }
        CompletableFuture<ProfilesResult> promise = new CompletableFuture<>();
        CompletableFuture<ProfilesResult> existing = IN_FLIGHT.putIfAbsent(uuid, promise);
        if (existing != null) {
            return existing;
        }
        CompletableFuture<Raw> raw;
        try {
            raw = fetchWithFallback(uuid);
        } catch (Throwable t) {
            raw = CompletableFuture.failedFuture(t);
        }
        raw.thenApplyAsync(pair -> {
            List<SbProfile> profiles = SbProfile.parseAll(pair.json(), uuid);
            ProfilesResult result = new ProfilesResult(uuid, profiles, pair.source(), System.currentTimeMillis());
            putCache(uuid, new Cached(result, result.fetchedAt()));
            return result;
        }, EXECUTOR).whenComplete((r, t) -> {
            IN_FLIGHT.remove(uuid, promise);
            if (t != null) {
                promise.completeExceptionally(unwrap(t));
            } else {
                promise.complete(r);
            }
        });
        return promise;
    }

    /** Drops expired entries, then the oldest ones, so the cache never exceeds {@link #MAX_CACHED_PROFILES}. */
    private static synchronized void putCache(UUID uuid, Cached entry) {
        long now = System.currentTimeMillis();
        CACHE.entrySet().removeIf(e -> now - e.getValue().at() >= CACHE_MS);
        while (CACHE.size() >= MAX_CACHED_PROFILES && !CACHE.containsKey(uuid)) {
            UUID oldest = null;
            long oldestAt = Long.MAX_VALUE;
            for (Map.Entry<UUID, Cached> e : CACHE.entrySet()) {
                if (e.getValue().at() < oldestAt) {
                    oldestAt = e.getValue().at();
                    oldest = e.getKey();
                }
            }
            if (oldest == null) {
                break;
            }
            CACHE.remove(oldest);
        }
        CACHE.put(uuid, entry);
    }

    private record Raw(JsonObject json, String source) {
    }

    private enum Attempt {
        USER_KEY, BACKEND, BUILTIN_KEY
    }

    // ------------------------------------------------------------------ auxiliary endpoints

    /** Per-profile / per-player endpoints beyond the profile list. Same source fallback, same response
     *  shape from Hypixel and the SkyBlockPV backend (skyblock-pv's {@code CachedApis}). */
    public enum AuxKind {
        MUSEUM("https://api.hypixel.net/v2/skyblock/museum?profile=", "/museum/", "members"),
        GARDEN("https://api.hypixel.net/v2/skyblock/garden?profile=", "/garden/", "garden"),
        PLAYER("https://api.hypixel.net/v2/player?uuid=", "/player/", "player");

        final String hypixel;
        final String backend;
        final String field;

        AuxKind(String hypixel, String backend, String field) {
            this.hypixel = hypixel;
            this.backend = backend;
            this.field = field;
        }
    }

    /** {@code data} is null when the endpoint answered but has nothing for this id (e.g. no garden). */
    public record AuxResult(JsonObject data, String source, long fetchedAt) {
    }

    private static final Pattern AUX_ID = Pattern.compile("^[0-9a-fA-F-]{32,36}$");
    private static final int MAX_CACHED_AUX = 32;
    private static final Map<String, AuxResult> AUX_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<AuxResult>> AUX_IN_FLIGHT = new ConcurrentHashMap<>();

    public static AuxResult getCachedAux(AuxKind kind, String id) {
        AuxResult r = AUX_CACHE.get(kind.name() + ":" + id);
        return r != null && System.currentTimeMillis() - r.fetchedAt() < CACHE_MS ? r : null;
    }

    public static CompletableFuture<AuxResult> fetchAux(AuxKind kind, String rawId, boolean bypassCache) {
        if (rawId == null || !AUX_ID.matcher(rawId).matches() || parseUuid(rawId) == null) {
            return CompletableFuture.failedFuture(new ApiException("Invalid id."));
        }
        // Dashed form for both sources (the backend parses UUIDs; Hypixel accepts either).
        String id = parseUuid(rawId).toString();
        String key = kind.name() + ":" + id;
        if (!bypassCache) {
            AuxResult cached = getCachedAux(kind, id);
            if (cached != null) {
                return CompletableFuture.completedFuture(cached);
            }
        }
        CompletableFuture<AuxResult> promise = new CompletableFuture<>();
        CompletableFuture<AuxResult> existing = AUX_IN_FLIGHT.putIfAbsent(key, promise);
        if (existing != null) {
            return existing;
        }
        CompletableFuture<Raw> raw;
        try {
            raw = runAttempts(attemptOrder(), 0, new ArrayList<>(), attempt -> switch (attempt) {
                case USER_KEY -> hypixelGet(kind.hypixel + id, ProfileViewerConfig.getInstance().getApiKey(), "Hypixel API (your key)", true);
                case BUILTIN_KEY -> hypixelGet(kind.hypixel + id, builtinKey(), "Hypixel API (built-in key)", true);
                case BACKEND -> backendGet(kind.backend + id, true, true);
            });
        } catch (Throwable t) {
            raw = CompletableFuture.failedFuture(t);
        }
        raw.thenApply(r -> {
            JsonElement el = r.json() == null ? null : r.json().get(kind.field);
            JsonObject data = el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
            AuxResult result = new AuxResult(data, r.source(), System.currentTimeMillis());
            synchronized (AUX_CACHE) {
                if (AUX_CACHE.size() >= MAX_CACHED_AUX && !AUX_CACHE.containsKey(key)) {
                    AUX_CACHE.clear();
                }
                AUX_CACHE.put(key, result);
            }
            return result;
        }).whenComplete((r, t) -> {
            AUX_IN_FLIGHT.remove(key, promise);
            if (t != null) {
                promise.completeExceptionally(unwrap(t));
            } else {
                promise.complete(r);
            }
        });
        return promise;
    }

    private static List<Attempt> attemptOrder() {
        ProfileViewerConfig cfg = ProfileViewerConfig.getInstance();
        String userKey = cfg.getApiKey();
        List<Attempt> order = new ArrayList<>();
        switch (cfg.getSource()) {
            case HYPIXEL -> order.add(userKey.isEmpty() ? Attempt.BUILTIN_KEY : Attempt.USER_KEY);
            case BACKEND -> order.add(Attempt.BACKEND);
            default -> {
                if (!userKey.isEmpty()) {
                    order.add(Attempt.USER_KEY);
                }
                order.add(Attempt.BACKEND);
                order.add(Attempt.BUILTIN_KEY);
            }
        }
        return order;
    }

    private static CompletableFuture<Raw> runAttempts(List<Attempt> order, int index, List<String> errors,
                                                      java.util.function.Function<Attempt, CompletableFuture<Raw>> run) {
        if (index >= order.size()) {
            String msg = errors.isEmpty() ? "No data source available." : String.join("\n", errors);
            return CompletableFuture.failedFuture(new ApiException(msg));
        }
        Attempt attempt = order.get(index);
        CompletableFuture<Raw> f;
        try {
            f = run.apply(attempt);
        } catch (Throwable t) {
            f = CompletableFuture.failedFuture(t);
        }
        return f.exceptionallyCompose(t -> {
            String label = switch (attempt) {
                case USER_KEY -> "Your API key";
                case BUILTIN_KEY -> "Built-in key";
                case BACKEND -> "SkyBlockPV backend";
            };
            errors.add(label + ": " + messageFor(t));
            return runAttempts(order, index + 1, errors, run);
        });
    }

    private static CompletableFuture<Raw> fetchWithFallback(UUID uuid) {
        ProfileViewerConfig cfg = ProfileViewerConfig.getInstance();
        String userKey = cfg.getApiKey();
        List<Attempt> order = new ArrayList<>();
        switch (cfg.getSource()) {
            case HYPIXEL -> order.add(userKey.isEmpty() ? Attempt.BUILTIN_KEY : Attempt.USER_KEY);
            case BACKEND -> order.add(Attempt.BACKEND);
            default -> {
                if (!userKey.isEmpty()) {
                    order.add(Attempt.USER_KEY);
                }
                order.add(Attempt.BACKEND);
                order.add(Attempt.BUILTIN_KEY);
            }
        }
        return runAttempts(uuid, order, 0, new ArrayList<>());
    }

    private static CompletableFuture<Raw> runAttempts(UUID uuid, List<Attempt> order, int index, List<String> errors) {
        if (index >= order.size()) {
            String msg = errors.isEmpty() ? "No data source available." : String.join("\n", errors);
            return CompletableFuture.failedFuture(new ApiException(msg));
        }
        Attempt attempt = order.get(index);
        CompletableFuture<Raw> f = switch (attempt) {
            case USER_KEY -> hypixel(uuid, ProfileViewerConfig.getInstance().getApiKey(), "Hypixel API (your key)");
            case BUILTIN_KEY -> hypixel(uuid, builtinKey(), "Hypixel API (built-in key)");
            case BACKEND -> backend(uuid, true);
        };
        return f.exceptionallyCompose(t -> {
            String label = switch (attempt) {
                case USER_KEY -> "Your API key";
                case BUILTIN_KEY -> "Built-in key";
                case BACKEND -> "SkyBlockPV backend";
            };
            errors.add(label + ": " + messageFor(t));
            return runAttempts(uuid, order, index + 1, errors);
        });
    }

    private static String builtinKey() {
        try {
            return com.killer560.hub.rngmeter.HypixelApiKeyProvider.getKey();
        } catch (Throwable t) {
            return "";
        }
    }

    private static CompletableFuture<Raw> hypixel(UUID uuid, String key, String label) {
        return hypixelGet(HYPIXEL_PROFILES + dashless(uuid), key, label, false);
    }

    /** @param notFoundIsEmpty treat 404 as "no data" (garden/museum for a profile that never had one). */
    private static CompletableFuture<Raw> hypixelGet(String url, String key, String label, boolean notFoundIsEmpty) {
        if (key == null || key.isEmpty()) {
            return CompletableFuture.failedFuture(new ApiException("no key"));
        }
        HttpRequest req = request(url).header("API-Key", key).GET().build();
        return HTTP_NO_REDIRECT.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(res -> {
            JsonObject body = parseObject(res.body());
            if (notFoundIsEmpty && res.statusCode() == 404) {
                return new Raw(null, label);
            }
            switch (res.statusCode()) {
                case 200 -> {
                    if (body == null || (body.has("success") && !body.get("success").getAsBoolean())) {
                        throw new ApiException("Hypixel returned an unreadable response.");
                    }
                    return new Raw(body, label);
                }
                case 403 -> throw new ApiException("Hypixel rejected the API key (403). Check the key in settings.");
                case 429 -> {
                    Optional<String> reset = res.headers().firstValue("RateLimit-Reset")
                            .or(() -> res.headers().firstValue("Retry-After"));
                    throw new ApiException("Hypixel rate limit reached (429)"
                            + reset.map(s -> " - try again in " + s + "s.").orElse(" - try again shortly."));
                }
                default -> {
                    String cause = body != null && body.has("cause") && body.get("cause").isJsonPrimitive()
                            ? body.get("cause").getAsString() : "HTTP " + res.statusCode();
                    throw new ApiException("Hypixel API error: " + sanitizeCause(cause));
                }
            }
        });
    }

    /** Hypixel's "cause" strings never echo the key, but strip anything key-shaped just in case. */
    private static String sanitizeCause(String cause) {
        return cause.replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "<key>");
    }

    private static CompletableFuture<Raw> backend(UUID uuid, boolean allowReauth) {
        return backendGet("/profiles/" + uuid, allowReauth, false);
    }

    private static CompletableFuture<Raw> backendGet(String path, boolean allowReauth, boolean notFoundIsEmpty) {
        return backendToken(false).thenCompose(token -> {
            HttpRequest req = request(BACKEND + path)
                    .header("Authorization", token)
                    .header("X-Intent", "killer560s-mod profile viewer")
                    .GET().build();
            return HTTP_NO_REDIRECT.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }).thenCompose(res -> {
            if (res.statusCode() == 200) {
                JsonObject body = parseObject(res.body());
                if (body == null) {
                    return CompletableFuture.failedFuture(new ApiException("Backend returned an unreadable response."));
                }
                return CompletableFuture.completedFuture(new Raw(body, "SkyBlockPV backend"));
            }
            if (res.statusCode() == 401 && allowReauth) {
                backendToken = null;
                return backendGet(path, false, notFoundIsEmpty);
            }
            if (res.statusCode() == 404 && notFoundIsEmpty) {
                return CompletableFuture.completedFuture(new Raw(null, "SkyBlockPV backend"));
            }
            String msg = switch (res.statusCode()) {
                case 401 -> "authentication failed (401).";
                case 403 -> "access refused (403).";
                case 429 -> "rate limited (429) - try again shortly.";
                default -> "HTTP " + res.statusCode() + " (Hypixel may be down or the player has no data).";
            };
            return CompletableFuture.failedFuture(new ApiException(msg));
        });
    }

    /** Mojang session handshake -> 24h backend token (reused for 23h). */
    private static CompletableFuture<String> backendToken(boolean force) {
        String token = backendToken;
        if (!force && token != null && System.currentTimeMillis() - backendTokenAt < 23L * 60 * 60 * 1000) {
            return CompletableFuture.completedFuture(token);
        }
        return CompletableFuture.supplyAsync(() -> {
            Minecraft mc = Minecraft.getInstance();
            User user = mc.getUser();
            String serverId = UUID.randomUUID().toString().replace("-", "");
            try {
                mc.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId);
            } catch (Exception e) {
                // Never log the exception detail - some authlib messages include request context.
                throw new ApiException("Mojang session check failed (offline account or expired login).");
            }
            return new String[]{user.getName(), serverId};
        }, EXECUTOR).thenCompose(pair -> {
            HttpRequest req = request(BACKEND + "/authenticate")
                    .header("x-minecraft-username", pair[0])
                    .header("x-minecraft-server", pair[1])
                    .GET().build();
            return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }).thenApply(res -> {
            if (res.statusCode() == 403) {
                throw new ApiException("this account is blocked from the backend (403).");
            }
            String body = res.body() == null ? "" : res.body().trim();
            if (res.statusCode() != 200 || body.isEmpty()) {
                throw new ApiException("authentication failed (HTTP " + res.statusCode() + ").");
            }
            backendToken = body;
            backendTokenAt = System.currentTimeMillis();
            LOGGER.info("[ProfileViewer] authenticated with SkyBlockPV backend");
            return body;
        });
    }
}
