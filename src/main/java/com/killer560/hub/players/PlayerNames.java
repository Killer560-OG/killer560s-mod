package com.killer560.hub.players;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Mod-wide UUID&lt;-&gt;name resolver for killer560's standing rule: "anything that remembers a player keys on
 * their UUID, never their IGN, and shows the current IGN client-side by resolving it from the UUID - incase
 * they ever change their name." One small resolver, reused by every feature that stores a player by identity
 * ({@code dungeonclass.ClassOverrides}, {@code leapmenu.LeapMenuConfig}'s Leap Order, and the upcoming
 * /bestfriends tracker + custom friends list built in parallel against this class).
 * <p>
 * Lookup order, cheapest first:
 * <ol>
 *   <li>The persisted cache ({@link PlayerNameCache}, {@code config/killer560smod-playernames.json}).</li>
 *   <li>Whoever is in your current lobby right now: a {@code PlayerInfo} tab-list entry already carries a
 *       real UUID for free, so it's scanned and cached before any network call is ever considered.</li>
 *   <li>Mojang's public profile endpoints, no API key (verified current 2026-09-21, minecraft.wiki/w/Mojang_API):
 *       name -&gt; UUID via {@code api.minecraftservices.com/minecraft/profile/lookup/name/<name>}, falling back
 *       to the older {@code api.mojang.com/users/profiles/minecraft/<name>} (occasionally 403s) if that fails;
 *       UUID -&gt; current name via {@code sessionserver.mojang.com/session/minecraft/profile/<uuid>}.</li>
 * </ol>
 * Every network call runs on {@link #EXECUTOR}, a single daemon thread - never the render or tick thread -
 * throttled to {@link #MIN_REQUEST_GAP_MS} apart. A UUID's name is refreshed at most once every
 * {@link #REFRESH_MS} (a day). {@link #uuidFor} / {@link #nameFor} return whatever is already known
 * immediately (possibly null, possibly a stale name); {@link #resolveAsync} does the same synchronously
 * <i>and</i> kicks off a refresh when the cache is missing or stale, calling back again later - on the client
 * thread - if that refresh finds something new.
 * <p>
 * <b>Failure handling:</b> a lookup that fails (offline, typo, Mojang down) simply never delivers that "later"
 * callback. Callers must treat "no callback yet" as "still don't know", not "doesn't exist" - keep whatever
 * IGN/entry you already had rather than dropping it. See {@code ClassOverrides}/{@code LeapMenuConfig} for the
 * migrate-on-resolve pattern this exists to support.
 */
public final class PlayerNames {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-players");
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    /** A UUID's name is refreshed at most this often - killer560's spec: "no more than once a day per UUID". */
    private static final long REFRESH_MS = 24L * 60 * 60 * 1000L;
    /** A failed name-&gt;UUID lookup (typo, never-existed account) isn't retried for this long. */
    private static final long NAME_FAILURE_RETRY_MS = 5 * 60 * 1000L;
    /** Minimum gap between outgoing Mojang requests - a self-imposed limit well under Mojang's published
     *  session-server rate limit (~400 req/10s), since this executor may end up shared by several features. */
    private static final long MIN_REQUEST_GAP_MS = 600L;
    /** The local tab list is cheap to scan but still not rescanned on every single call. */
    private static final long LOCAL_SCAN_GAP_MS = 500L;

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String LOOKUP_NAME_PRIMARY = "https://api.minecraftservices.com/minecraft/profile/lookup/name/";
    private static final String LOOKUP_NAME_LEGACY = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String SESSION_PROFILE = "https://sessionserver.mojang.com/session/minecraft/profile/";

    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-players");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(EXECUTOR)
            .build();
    private static final AtomicLong LAST_REQUEST_AT = new AtomicLong();
    private static final AtomicBoolean SAVE_SCHEDULED = new AtomicBoolean(false);

    private static final Set<UUID> IN_FLIGHT_UUID = ConcurrentHashMap.newKeySet();
    private static final Set<String> IN_FLIGHT_NAME = ConcurrentHashMap.newKeySet();
    private static final java.util.Map<String, Long> NAME_FAILED_AT = new ConcurrentHashMap<>();

    private static volatile long lastLocalScanAt = 0L;

    private PlayerNames() {
    }

    // ---------------------------------------------------------------------------------------- immediate, cache-only

    /** The UUID for {@code name} from the tab list of anyone currently in your lobby, or the persisted cache -
     *  never touches the network. Null when nobody by that name has been seen or resolved yet; call
     *  {@link #resolveAsync(String, Consumer)} to actually look it up. */
    public static UUID uuidFor(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        scanLocalTabListIfDue();
        return PlayerNameCache.getInstance().uuidOf(name.trim());
    }

    /** The last-known name for {@code id} - possibly stale (see class doc), never null-but-blocking on the
     *  network. Null only when this UUID has never been seen or resolved. */
    public static String nameFor(UUID id) {
        if (id == null) {
            return null;
        }
        scanLocalTabListIfDue();
        return PlayerNameCache.getInstance().nameOf(id);
    }

    /** Feeds a UUID/name pair anyone already knows (a tab list, a packet carrying both) into the shared cache
     *  for free - no network call. Safe to call from any thread; the other agent building /bestfriends and the
     *  custom friends list can call this directly instead of re-parsing the tab list itself. */
    public static void noteKnown(UUID id, String name) {
        if (id == null || name == null || name.isBlank() || id.version() == 2) {
            // version 2 = Hypixel's fake tab-list/NPC entries (same heuristic NameChangerFeature uses) - never
            // a real player, never worth caching.
            return;
        }
        PlayerNameCache cache = PlayerNameCache.getInstance();
        cache.put(id, name, System.currentTimeMillis());
        if (cache.isDirty()) {
            scheduleSave();
        }
    }

    private static void scanLocalTabListIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastLocalScanAt < LOCAL_SCAN_GAP_MS) {
            return;
        }
        lastLocalScanAt = now;
        try {
            Minecraft client = Minecraft.getInstance();
            var connection = client == null ? null : client.getConnection();
            if (connection != null) {
                for (PlayerInfo info : connection.getListedOnlinePlayers()) {
                    GameProfile profile = info.getProfile();
                    if (profile != null) {
                        noteKnown(profile.id(), profile.name());
                    }
                }
            }
            if (client != null && client.player != null) {
                GameProfile self = client.player.getGameProfile();
                noteKnown(self.id(), self.name());
            }
        } catch (Exception ignored) {
            // no world / connection not ready yet
        }
    }

    private static void scheduleSave() {
        if (SAVE_SCHEDULED.compareAndSet(false, true)) {
            // Debounced: a tab-list scan can note a dozen players in one pass, and this should be one disk
            // write, not one per player.
            EXECUTOR.schedule(() -> {
                SAVE_SCHEDULED.set(false);
                PlayerNameCache.getInstance().save();
            }, 2, TimeUnit.SECONDS);
        }
    }

    // ---------------------------------------------------------------------------------------- async

    /** Resolves {@code name} to a UUID: calls {@code onResolved} immediately with whatever is already known
     *  (tab list / cache - possibly null), then again from a background lookup if nothing was known yet. See
     *  class doc for the thread/failure contract. */
    public static void resolveAsync(String name, Consumer<UUID> onResolved) {
        if (name == null || onResolved == null) {
            return;
        }
        String clean = name.trim();
        UUID known = uuidFor(clean);
        onResolved.accept(known);
        if (known != null || !VALID_NAME.matcher(clean).matches()) {
            return;
        }
        String key = clean.toLowerCase(Locale.ROOT);
        Long failedAt = NAME_FAILED_AT.get(key);
        if (failedAt != null && System.currentTimeMillis() - failedAt < NAME_FAILURE_RETRY_MS) {
            return;
        }
        if (!IN_FLIGHT_NAME.add(key)) {
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                UUID resolved = fetchUuidForName(clean);
                if (resolved != null) {
                    noteKnown(resolved, clean);
                    runOnClientThread(() -> onResolved.accept(resolved));
                } else {
                    NAME_FAILED_AT.put(key, System.currentTimeMillis());
                }
            } finally {
                IN_FLIGHT_NAME.remove(key);
            }
        });
    }

    /** Resolves/refreshes the current name for {@code id}: calls {@code onResolved} immediately with the
     *  last-known name (possibly null, possibly stale), then again later - only if it actually changed - once
     *  a background refresh completes. A refresh only runs when the cache is missing or older than a day. See
     *  class doc for the thread/failure contract. */
    public static void resolveAsync(UUID id, Consumer<String> onResolved) {
        if (id == null || onResolved == null) {
            return;
        }
        String known = nameFor(id);
        onResolved.accept(known);
        long updatedAt = PlayerNameCache.getInstance().updatedAtOf(id);
        if (System.currentTimeMillis() - updatedAt < REFRESH_MS) {
            return;
        }
        if (!IN_FLIGHT_UUID.add(id)) {
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                String resolved = fetchNameForUuid(id);
                if (resolved != null) {
                    noteKnown(id, resolved);
                    if (!resolved.equals(known)) {
                        runOnClientThread(() -> onResolved.accept(resolved));
                    }
                }
                // A failed refresh leaves updatedAtOf() unchanged, so the very next call (not "once a day")
                // tries again rather than a stale timestamp permanently suppressing retries - fine since
                // resolveAsync is already throttled above and sessionserver failures are rare.
            } finally {
                IN_FLIGHT_UUID.remove(id);
            }
        });
    }

    private static void runOnClientThread(Runnable task) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.isSameThread()) {
            task.run();
        } else {
            client.execute(task);
        }
    }

    // ---------------------------------------------------------------------------------------- network (EXECUTOR thread only)

    private static void throttle() {
        long wait;
        while ((wait = LAST_REQUEST_AT.get() + MIN_REQUEST_GAP_MS - System.currentTimeMillis()) > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        LAST_REQUEST_AT.set(System.currentTimeMillis());
    }

    private static UUID fetchUuidForName(String name) {
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);
        UUID id = fetchUuidFrom(LOOKUP_NAME_PRIMARY + encoded);
        if (id == null) {
            id = fetchUuidFrom(LOOKUP_NAME_LEGACY + encoded);
        }
        return id;
    }

    private static UUID fetchUuidFrom(String url) {
        try {
            throttle();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", "Killer560sMod-PlayerNames/1.0")
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                return null; // no such account - not an error worth logging
            }
            if (resp.statusCode() / 100 != 2) {
                LOGGER.info("[PlayerNames] name lookup failed: HTTP {} ({})", resp.statusCode(), url);
                return null;
            }
            JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
            String rawId = ConfigJson.getString(body, "id", null);
            return rawId == null ? null : dashUuid(rawId);
        } catch (Exception e) {
            LOGGER.info("[PlayerNames] name lookup error: {}", e.toString());
            return null;
        }
    }

    private static String fetchNameForUuid(UUID id) {
        try {
            throttle();
            HttpRequest req = HttpRequest.newBuilder(URI.create(SESSION_PROFILE + id.toString().replace("-", "")))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", "Killer560sMod-PlayerNames/1.0")
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                if (resp.statusCode() != 404) {
                    LOGGER.info("[PlayerNames] name refresh failed: HTTP {} ({})", resp.statusCode(), id);
                }
                return null;
            }
            JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
            String name = ConfigJson.getString(body, "name", null);
            return name == null || name.isBlank() ? null : name;
        } catch (Exception e) {
            LOGGER.info("[PlayerNames] name refresh error: {}", e.toString());
            return null;
        }
    }

    /** Mojang's lookup responses give the UUID with no dashes. */
    private static UUID dashUuid(String raw) {
        String clean = raw.replace("-", "");
        if (clean.length() != 32) {
            return null;
        }
        String dashed = clean.substring(0, 8) + "-" + clean.substring(8, 12) + "-" + clean.substring(12, 16) + "-"
                + clean.substring(16, 20) + "-" + clean.substring(20, 32);
        try {
            return UUID.fromString(dashed);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
