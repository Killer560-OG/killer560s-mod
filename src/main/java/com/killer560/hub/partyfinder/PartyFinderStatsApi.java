package com.killer560.hub.partyfinder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import com.killer560.hub.profileviewer.data.LevelTables;
import com.killer560.hub.profileviewer.data.SbProfile;
import com.killer560.hub.relay.RelayEndpoint;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModNet;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Dungeon stats for the Party Finder tooltip, one player at a time, from the Profile Viewer's own data source.
 *
 * <p><b>Source.</b> Each name goes through {@link ProfileViewerApi#resolve} (Mojang name to UUID) and
 * {@link ProfileViewerApi#fetchProfiles} - the keyless SkyBlockPV backend ({@code skyblock-pv.thatgravyboat.tech},
 * which hands back Hypixel's {@code /v2/skyblock/profiles} response unchanged), or the player's own Hypixel key if
 * they set one in the Profile Viewer. No new host, no new HTTP client, no key in the mod. Until 2026-10-07 this class
 * asked {@code api.docilelm.top/v2/dungeons} (the endpoint Devonian uses), which now answers {@code {"result":{}}}
 * for every name - so every stat showed {@code ?}. That service is no longer called at all.
 *
 * <p><b>Relay first</b> (2026-10-07, "make this load as quickly as possible"). Before any of the above, the names
 * go in ONE batched {@code POST /pf/stats} to killer560's own relay ({@link RelayEndpoint}), which keeps a cache
 * shared by every user of the mod: a player anyone has looked at recently comes back {@code ok} on the first request,
 * an unknown one {@code pending} while the relay fetches it within the upstream's rate limit for everybody at once.
 * One daemon thread ({@code killer560smod-pf-relay}) sends the batch the moment new names appear and re-polls the
 * pending ones every 1-2 s (the relay's own {@code etaMs}, clamped) while the menu still asks for them, for at most
 * {@link #RELAY_PENDING_MAX_MS}. An entry the relay marks {@code stale} (older than its 15-minute refresh-on-view
 * window) is shown at once and asked for again a few seconds later, which is how a new PB arrives. A name the relay
 * expects to take longer than {@link #DIRECT_HELP_MS} is ALSO queued on the direct path below (this client's own rate
 * limit, separate from the relay's), so a cold menu fills from both ends. A per-name {@code error}, a pending name
 * past the bound, and every name while the relay is unreachable or answering errors ({@link #RELAY_DOWN_MS}) go to
 * the direct path, which is unchanged. A relay answer is kept {@link #RELAY_CACHE_MS}: the relay is the cache. The
 * relay's stats rules are a port of {@link #toStats} (relay repo {@code src/pfextract.ts}); testkit case 410 checks
 * the two give identical numbers for every fixture player.
 *
 * <p><b>Fields</b> (Hypixel's names, under {@code members.<uuid>.dungeons}), all from ONE profile,
 * {@link SbProfile#dungeonProfile} (the selected one, or the one with the most Catacombs XP when none is selected or
 * the selected one has no dungeon data): Catacombs level from {@code dungeon_types.catacombs.experience} through
 * {@link LevelTables#catacombsLevel} (capped at 50, as Hypixel shows it); secrets is {@code secrets} summed over every
 * profile, and the average is that over every floor's {@code tier_completions} (normal and Master Mode) on the same
 * profiles; the PB is that profile's {@code fastest_time_s_plus} / {@code fastest_time_s} / {@code fastest_time} (any
 * score) for the party's floor, milliseconds, shown {@code m:ss} - which one the overlay shows is its PB mode.
 *
 * <p><b>Requests.</b> {@link #request} only queues names - it is called from the client tick and never touches the
 * network. One daemon thread takes names off the queue and starts at most {@link #MAX_IN_FLIGHT} lookups at once. A
 * name is never queued twice, never queued while its lookup runs, and not asked again for {@link #CACHE_MS} after an
 * answer or {@link #FAILURE_RETRY_MS} after a failure.
 *
 * <p><b>Rate limit</b> (the cause of "the vast majority still do not have any times", killer560 2026-10-07). The
 * backend lets three or four profile requests through and then answers 429 "Retry-After: 10" (measured by hand on 25
 * real dungeon players the same morning: 4 answered, the next 19 were 429s within two seconds). This class used to
 * record each 429 as a failure and not ask again for three minutes, so a full menu showed stats for about four names.
 * Now a 429 is not an answer: the name goes back on the queue, and the worker starts nothing until the backend's
 * Retry-After has passed ({@link ProfileViewerApi#backendRetryAtMs}). Only after {@link #MAX_THROTTLES} throttles in a
 * row is the name shown as failed, and then retried after the backend's own wait (at least 30 s), not three minutes.
 *
 * <p>Every lookup outcome is one INFO line ({@code [PartyFinder] stats for X: ...} / {@code [PartyFinder] no stats
 * for X - ...}), and a backend pause one more; nothing per tick. Every callback swallows its own errors: nothing here
 * can throw into the tick or a packet handler.
 */
public final class PartyFinderStatsApi {

    private static final Logger LOGGER = ModLog.get("killer560smod-partyfinder");

    private static final long CACHE_MS = 10 * 60 * 1000L;
    /** The backend itself caches a failed player for 3 minutes; asking sooner only gets the same failure. */
    private static final long FAILURE_RETRY_MS = 3 * 60 * 1000L;
    /** After {@link #MAX_THROTTLES} 429s in a row for one name, the shortest wait before it is asked again. */
    private static final long THROTTLE_RETRY_MS = 30 * 1000L;
    private static final int MAX_THROTTLES = 5;
    private static final int MAX_IN_FLIGHT = 2;
    private static final long LOOKUP_TIMEOUT_S = 60;
    private static final int MAX_CACHED = 512;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    /** A relay answer is re-asked after this: the relay holds the real cache and refreshes it on view. */
    static final long RELAY_CACHE_MS = 2 * 60 * 1000L;
    /** A relay that could not be reached or answered an error is not asked again for this long. */
    static final long RELAY_DOWN_MS = 60 * 1000L;
    /** A name still pending at the relay after this goes to the direct path instead. */
    static final long RELAY_PENDING_MAX_MS = 120 * 1000L;
    /** A pending name the relay expects to take longer than this is also fetched directly. */
    static final long DIRECT_HELP_MS = 20 * 1000L;
    /** A stale answer is re-polled for at most this long, waiting for the relay's refresh to land. */
    private static final long STALE_FOLLOW_MS = 60 * 1000L;
    /** The menu asks every second; a name it stopped asking for this long ago is no longer polled. */
    private static final long WANT_IDLE_MS = 3 * 1000L;
    private static final int RELAY_BATCH = 30;
    private static final Duration RELAY_TIMEOUT = Duration.ofSeconds(6);

    /** {@code pbNormal}/{@code pbMaster}: {@code {"s": {"floor_7": "4:12"}, "s_plus": {...}, "any": {...}}}, may be
     *  null. {@code any} is the floor's fastest clear at any score. */
    public record PlayerStats(double level, int secrets, double averageSecrets, JsonObject pbNormal,
                              JsonObject pbMaster, long fetchedAtMs) {
    }

    private static final LinkedBlockingQueue<String> QUEUE = new LinkedBlockingQueue<>();
    /** Names queued or being looked up - the dedupe set. */
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    private static final Map<String, PlayerStats> CACHE = new ConcurrentHashMap<>();
    /** Name -> the time before which it is not asked again (a failure, or an answer with no dungeon data). */
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final Map<String, String> FAILURE_REASON = new ConcurrentHashMap<>();
    /** Name -> 429s in a row for it. */
    private static final Map<String, Integer> THROTTLES = new ConcurrentHashMap<>();
    private static final Semaphore SLOTS = new Semaphore(MAX_IN_FLIGHT);

    /** Test hooks (testkit 400/406-menu-partyfinder-stats): lookups started, the threads that started them, and the
     *  429s met. */
    static final AtomicInteger LOOKUPS_STARTED = new AtomicInteger();
    static final Set<String> LOOKUP_THREADS = ConcurrentHashMap.newKeySet();
    static final AtomicInteger THROTTLED = new AtomicInteger();

    /** Name -> expiry of its {@link #CACHE} entry when that is not fetchedAt + {@link #CACHE_MS} (relay answers). */
    private static final Map<String, Long> EXPIRES = new ConcurrentHashMap<>();

    /** A name the relay is being asked about. */
    private static final class Want {
        final long firstAt;
        volatile long lastAskedAt;
        volatile long nextPollAt;
        volatile boolean directQueued;
        /** When the relay first answered it stale: polling then only follows the refresh, for a bounded time. */
        volatile long staleSince;

        Want(long now) {
            firstAt = now;
            lastAskedAt = now;
            nextPollAt = now;
        }
    }

    private static final Map<String, Want> WANTS = new ConcurrentHashMap<>();
    private static final Semaphore RELAY_WAKE = new Semaphore(0);
    /** Before this the relay is not asked (the testkit's 410 clears it, so an earlier case's outage does not carry over). */
    static volatile long relayDownUntil = 0;
    private static Thread relayWorker;
    private static final HttpClient RELAY_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    /** Test hooks (410-menu-partyfinder-relay): relay requests sent, names handed from the relay flow to the direct
     *  path, and the last relay failure. */
    static final AtomicInteger RELAY_REQUESTS = new AtomicInteger();
    static final AtomicInteger RELAY_FALLBACKS = new AtomicInteger();
    static volatile String lastRelayError;
    /** For the per-batch log lines. */
    private static volatile long batchStartedAt;
    private static final AtomicInteger BATCH_SETTLED = new AtomicInteger();

    private static Thread worker;
    /** The backend pause last logged, so two lookups throttled together log it once. */
    private static volatile long loggedPauseUntil = 0;

    private PartyFinderStatsApi() {
    }

    public static PlayerStats get(String name) {
        return name == null ? null : CACHE.get(name.toLowerCase(Locale.ROOT));
    }

    /** True when the last lookup for {@code name} failed (no answer, or no SkyBlock dungeon data for it). */
    public static boolean hasFailed(String name) {
        return name != null && FAILED.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /** Why the last lookup for {@code name} failed, naming the source ("SkyBlockPV backend: ..."), or null. */
    public static String failureReason(String name) {
        return name == null ? null : FAILURE_REASON.get(name.toLowerCase(Locale.ROOT));
    }

    /** Ask for every name that has no fresh answer. Cheap and network-free: safe on the client tick. */
    public static void request(Collection<String> names) {
        try {
            long now = System.currentTimeMillis();
            boolean relayUp = now >= relayDownUntil && RelayEndpoint.isUsable(RelayEndpoint.DEFAULT_BASE_URL);
            boolean wake = false;
            boolean direct = false;
            for (String raw : names) {
                // Names come from item lore and end up in a URL path: real Minecraft usernames only.
                if (raw == null || !NAME.matcher(raw).matches()) {
                    continue;
                }
                String name = raw.toLowerCase(Locale.ROOT);
                Want want = WANTS.get(name);
                if (want != null) {
                    want.lastAskedAt = now;
                }
                if (isFresh(name, now)) {
                    continue;
                }
                Long retryAt = FAILED.get(name);
                if (retryAt != null && now < retryAt) {
                    continue;
                }
                if (PENDING.contains(name) || want != null) {
                    continue;
                }
                if (relayUp) {
                    WANTS.put(name, new Want(now));
                    wake = true;
                } else {
                    direct |= queueDirect(name);
                }
            }
            if (wake) {
                ensureRelayStarted();
                RELAY_WAKE.release();
            }
            if (direct) {
                ensureStarted();
            }
        } catch (Throwable t) {
            LOGGER.warn("[PartyFinder] could not queue stats lookups: {}", t.toString());
        }
    }

    private static boolean isFresh(String name, long now) {
        PlayerStats cached = CACHE.get(name);
        if (cached == null) {
            return false;
        }
        Long expires = EXPIRES.get(name);
        return now < (expires != null ? expires : cached.fetchedAtMs() + CACHE_MS);
    }

    /** Puts a name on the direct (SkyBlockPV / own key) queue; true when it was not already there. */
    private static boolean queueDirect(String name) {
        if (PENDING.add(name)) {
            QUEUE.add(name);
            return true;
        }
        return false;
    }

    private static synchronized void ensureStarted() {
        if (worker != null && worker.isAlive()) {
            return;
        }
        worker = new Thread(PartyFinderStatsApi::run, "killer560smod-partyfinder-stats");
        worker.setDaemon(true);
        worker.start();
    }

    private static void run() {
        while (true) {
            String name;
            try {
                name = QUEUE.take();
                if (isFresh(name, System.currentTimeMillis())) {
                    // The relay answered it while it waited here.
                    PENDING.remove(name);
                    continue;
                }
                waitForBackend();
                SLOTS.acquire();
                // A lookup still in flight may have been throttled while this one waited for its slot.
                waitForBackend();
                if (isFresh(name, System.currentTimeMillis())) {
                    SLOTS.release();
                    PENDING.remove(name);
                    continue;
                }
            } catch (InterruptedException e) {
                return;
            }
            try {
                lookup(name);
            } catch (Throwable t) {
                fail(name, name, "lookup error (" + t.getClass().getSimpleName() + ")", FAILURE_RETRY_MS);
                SLOTS.release();
            }
        }
    }

    /** Sleeps on the worker thread until the backend's last Retry-After has passed. */
    private static void waitForBackend() throws InterruptedException {
        while (true) {
            long wait = ProfileViewerApi.backendRetryAtMs() - System.currentTimeMillis();
            if (wait <= 0) {
                return;
            }
            Thread.sleep(Math.min(wait, 1000));
        }
    }

    /** Starts one player's lookup; the slot is released when it completes, however it completes. */
    private static void lookup(String name) {
        LOOKUPS_STARTED.incrementAndGet();
        LOOKUP_THREADS.add(Thread.currentThread().getName());
        AtomicReference<String> shown = new AtomicReference<>();
        CompletableFuture<ProfileViewerApi.ProfilesResult> f = ProfileViewerApi.resolve(name).thenCompose(player -> {
            shown.set(player.name() == null || player.name().isEmpty() ? name : player.name());
            LOOKUP_THREADS.add(Thread.currentThread().getName());
            return ProfileViewerApi.fetchProfiles(player.uuid(), false);
        });
        f.orTimeout(LOOKUP_TIMEOUT_S, TimeUnit.SECONDS).whenComplete((result, t) -> {
            try {
                String display = shown.get() != null ? shown.get() : name;
                if (t != null) {
                    Throwable cause = ProfileViewerApi.unwrap(t);
                    if (cause instanceof ProfileViewerApi.RateLimitedException r && shown.get() != null) {
                        throttled(name, display, r);
                    } else {
                        String why = ProfileViewerApi.messageFor(t);
                        fail(name, display, shown.get() != null ? why : "Mojang name lookup: " + why, FAILURE_RETRY_MS);
                    }
                } else {
                    answered(name, display, result);
                }
            } catch (Throwable ignored) {
                // Never let a callback throw into the HTTP client's threads.
                PENDING.remove(name);
            } finally {
                SLOTS.release();
            }
        });
    }

    /** A 429: back on the queue (still pending), unless this name has now been throttled too often in a row. */
    private static void throttled(String name, String display, ProfileViewerApi.RateLimitedException r) {
        THROTTLED.incrementAndGet();
        int n = THROTTLES.merge(name, 1, Integer::sum);
        long until = System.currentTimeMillis() + r.retryAfterMs;
        if (n > MAX_THROTTLES) {
            THROTTLES.remove(name);
            fail(name, display, "SkyBlockPV backend: rate limited (429) " + n + " times in a row",
                    Math.max(THROTTLE_RETRY_MS, r.retryAfterMs));
            return;
        }
        if (until > loggedPauseUntil + 1000) {
            loggedPauseUntil = until;
            LOGGER.info("[PartyFinder] SkyBlockPV backend rate limited (429): next lookup in {} s, {} name(s) waiting",
                    (r.retryAfterMs + 999) / 1000, QUEUE.size() + 1);
        }
        QUEUE.add(name);
    }

    private static void answered(String name, String display, ProfileViewerApi.ProfilesResult result) {
        THROTTLES.remove(name);
        List<SbProfile> profiles = result.profiles();
        SbProfile chosen = SbProfile.dungeonProfile(profiles);
        String source = result.source() == null ? "SkyBlockPV backend" : result.source();
        if (chosen == null) {
            fail(name, display, source + ": no SkyBlock profile", CACHE_MS);
            return;
        }
        if (chosen.dungeons.catacombsXp() <= 0 && chosen.dungeons.totalRuns() <= 0) {
            fail(name, display, source + ": no Catacombs data on any of " + profiles.size() + " profile(s)", CACHE_MS);
            return;
        }
        PlayerStats stats = toStats(profiles, System.currentTimeMillis());
        FAILED.remove(name);
        FAILURE_REASON.remove(name);
        if (CACHE.size() >= MAX_CACHED) {
            long now = System.currentTimeMillis();
            CACHE.entrySet().removeIf(e -> now - e.getValue().fetchedAtMs() >= CACHE_MS);
        }
        EXPIRES.remove(name);
        CACHE.put(name, stats);
        PENDING.remove(name);
        LOGGER.info("[PartyFinder] stats for {}: {}", display, describe(profiles, chosen, stats));
    }

    private static void fail(String name, String display, String reason, long retryAfterMs) {
        FAILED.put(name, System.currentTimeMillis() + retryAfterMs);
        FAILURE_REASON.put(name, reason);
        PENDING.remove(name);
        LOGGER.info("[PartyFinder] no stats for {} - {}; shown as ?, asked again in {} s", display,
                reason.replace('\n', ';'), retryAfterMs / 1000);
    }

    // ------------------------------------------------------------------ the relay

    private static synchronized void ensureRelayStarted() {
        if (relayWorker != null && relayWorker.isAlive()) {
            return;
        }
        relayWorker = new Thread(PartyFinderStatsApi::relayLoop, "killer560smod-pf-relay");
        relayWorker.setDaemon(true);
        relayWorker.start();
    }

    private static void relayLoop() {
        while (true) {
            try {
                long now = System.currentTimeMillis();
                List<String> due = new ArrayList<>();
                long nextAt = Long.MAX_VALUE;
                for (Iterator<Map.Entry<String, Want>> it = WANTS.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<String, Want> e = it.next();
                    Want w = e.getValue();
                    if (now - w.lastAskedAt > WANT_IDLE_MS) {
                        it.remove(); // the menu closed or the page changed
                        continue;
                    }
                    if (w.staleSince == 0 && isFresh(e.getKey(), now)) {
                        it.remove(); // the direct path answered it first
                        continue;
                    }
                    if (w.nextPollAt <= now) {
                        due.add(e.getKey());
                    } else {
                        nextAt = Math.min(nextAt, w.nextPollAt);
                    }
                }
                if (due.isEmpty()) {
                    if (WANTS.isEmpty() && batchStartedAt != 0) {
                        int settled = BATCH_SETTLED.getAndSet(0);
                        if (settled > 0) {
                            LOGGER.info("[PartyFinder] relay: {} more name(s) arrived, {} ms after the first request",
                                    settled, now - batchStartedAt);
                        }
                        batchStartedAt = 0;
                    }
                    long sleep = nextAt == Long.MAX_VALUE ? 5000 : Math.max(1, nextAt - now);
                    RELAY_WAKE.tryAcquire(sleep, TimeUnit.MILLISECONDS);
                    RELAY_WAKE.drainPermits();
                    continue;
                }
                for (int i = 0; i < due.size(); i += RELAY_BATCH) {
                    if (!askRelay(due.subList(i, Math.min(due.size(), i + RELAY_BATCH)))) {
                        break;
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                LOGGER.warn("[PartyFinder] relay worker: {}", t.toString());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** The relay's stats endpoint: service {@code pf-relay} (testkit fakes), on top of the {@code relay} override. */
    static String relayUrl() {
        return ModNet.url("pf-relay", RelayEndpoint.normalise(RelayEndpoint.DEFAULT_BASE_URL) + "/pf/stats");
    }

    private static String userAgent() {
        String version = FabricLoader.getInstance().getModContainer("killer560smod")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
        return "killer560s-mod/" + version + " (PartyFinder)";
    }

    /** One {@code POST /pf/stats}, on the relay thread; false when the relay is unusable (every name went direct). */
    private static boolean askRelay(List<String> names) {
        long now = System.currentTimeMillis();
        JsonObject body = new JsonObject();
        JsonArray arr = new JsonArray();
        names.forEach(arr::add);
        body.add("names", arr);
        RELAY_REQUESTS.incrementAndGet();
        HttpResponse<String> res;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(relayUrl()))
                    .timeout(RELAY_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", userAgent())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            res = ModNet.send(RELAY_HTTP, req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            relayDown(e.getClass().getSimpleName());
            return false;
        }
        if (res.statusCode() == 429) {
            // This address is asking too fast: slow down, but the relay itself is fine.
            for (String n : names) {
                Want w = WANTS.get(n);
                if (w != null) {
                    w.nextPollAt = now + 5000;
                }
            }
            return true;
        }
        JsonObject results = null;
        if (res.statusCode() == 200) {
            try {
                JsonElement root = JsonParser.parseString(res.body());
                if (root.isJsonObject() && root.getAsJsonObject().get("results") instanceof JsonObject o) {
                    results = o;
                }
            } catch (RuntimeException ignored) {
                // handled below
            }
        }
        if (results == null) {
            relayDown("HTTP " + res.statusCode());
            return false;
        }
        int ok = 0;
        int stale = 0;
        int pending = 0;
        int missing = 0;
        int error = 0;
        boolean newBatch = batchStartedAt == 0;
        if (newBatch) {
            batchStartedAt = now;
        }
        now = System.currentTimeMillis();
        for (String name : names) {
            Want w = WANTS.get(name);
            JsonObject r = results.get(name) instanceof JsonObject o ? o : null;
            String status = r != null && r.get("status") != null && r.get("status").isJsonPrimitive()
                    ? r.get("status").getAsString() : "error";
            long eta = r != null && r.get("etaMs") != null && r.get("etaMs").isJsonPrimitive()
                    ? (long) r.get("etaMs").getAsDouble() : 2000;
            try {
                switch (status) {
                    case "ok" -> {
                        PlayerStats stats = fromRelay(r.getAsJsonObject("stats"), now);
                        boolean isStale = r.get("stale") != null && r.get("stale").isJsonPrimitive()
                                && r.get("stale").getAsBoolean();
                        EXPIRES.put(name, now + RELAY_CACHE_MS);
                        CACHE.put(name, stats);
                        FAILED.remove(name);
                        FAILURE_REASON.remove(name);
                        ok++;
                        if (isStale && w != null) {
                            stale++;
                            if (w.staleSince == 0) {
                                w.staleSince = now;
                            }
                            if (now - w.staleSince > STALE_FOLLOW_MS) {
                                WANTS.remove(name);
                            } else {
                                w.nextPollAt = now + Math.max(1500, Math.min(10_000, eta));
                            }
                        } else if (WANTS.remove(name) != null && !newBatch) {
                            BATCH_SETTLED.incrementAndGet();
                        }
                    }
                    case "pending" -> {
                        pending++;
                        if (w == null) {
                            break;
                        }
                        if (now - w.firstAt > RELAY_PENDING_MAX_MS) {
                            WANTS.remove(name);
                            toDirect(name);
                        } else {
                            w.nextPollAt = now + Math.max(1000, Math.min(2000, eta));
                            if (eta > DIRECT_HELP_MS && !w.directQueued) {
                                w.directQueued = true;
                                toDirect(name);
                            }
                        }
                    }
                    case "missing" -> {
                        missing++;
                        if (WANTS.remove(name) != null && !newBatch) {
                            BATCH_SETTLED.incrementAndGet();
                        }
                        fail(name, name, clean(r.get("reason")), CACHE_MS);
                    }
                    default -> {
                        error++;
                        WANTS.remove(name);
                        toDirect(name);
                    }
                }
            } catch (RuntimeException e) {
                // A malformed answer for one name: that name goes direct, the rest stand.
                error++;
                WANTS.remove(name);
                toDirect(name);
            }
        }
        if (newBatch) {
            LOGGER.info("[PartyFinder] relay: {} name(s) -> {} cached{}, {} pending, {} missing, {} fetched directly ({} ms)",
                    names.size(), ok, stale > 0 ? " (" + stale + " refreshing)" : "", pending, missing, error,
                    System.currentTimeMillis() - batchStartedAt);
            if (WANTS.isEmpty() || pending == 0 && stale == 0) {
                batchStartedAt = 0;
                BATCH_SETTLED.set(0);
            }
        }
        return true;
    }

    private static void toDirect(String name) {
        RELAY_FALLBACKS.incrementAndGet();
        if (queueDirect(name)) {
            ensureStarted();
        }
    }

    /** The relay could not be used: every name it was asked about goes direct, and it is left alone for a minute. */
    private static void relayDown(String why) {
        lastRelayError = why;
        relayDownUntil = System.currentTimeMillis() + RELAY_DOWN_MS;
        List<String> moved = new ArrayList<>(WANTS.keySet());
        WANTS.clear();
        batchStartedAt = 0;
        BATCH_SETTLED.set(0);
        moved.forEach(PartyFinderStatsApi::toDirect);
        LOGGER.info("[PartyFinder] stats relay unavailable ({}); {} name(s) fetched directly, relay asked again in {} s",
                why, moved.size(), RELAY_DOWN_MS / 1000);
    }

    /** A relay reason, safe for the tooltip footer: printable ASCII, no colour codes, bounded. */
    private static String clean(JsonElement reason) {
        String s = reason != null && reason.isJsonPrimitive() ? reason.getAsString() : "relay: no data";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 120; i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c < 0x7F && c != '&') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** The relay's stats object (relay PROTOCOL.md "Party Finder stats") as the overlay's {@link PlayerStats}. The
     *  level is recomputed from {@code cataXp} with this mod's own table, so it cannot drift from the direct path. */
    static PlayerStats fromRelay(JsonObject s, long now) {
        double level = s.has("cataXp")
                ? LevelTables.catacombsLevel(s.get("cataXp").getAsLong(), false).fractional()
                : s.get("cata").getAsDouble();
        long secrets = s.get("secrets").getAsLong();
        double average = s.get("avgSecrets").getAsDouble();
        JsonObject pb = s.has("pb") && s.get("pb").isJsonObject() ? s.getAsJsonObject("pb") : null;
        return new PlayerStats(level, (int) Math.max(0, Math.min(Integer.MAX_VALUE, secrets)), average,
                relayPbs(pb == null ? null : pb.getAsJsonObject("normal")),
                relayPbs(pb == null ? null : pb.getAsJsonObject("master")), now);
    }

    /** {@code {"floor_7": {"splus": ms, "s": ms, "any": ms}}} -> the {@link #personalBests} shape. */
    private static JsonObject relayPbs(JsonObject floors) {
        JsonObject s = new JsonObject();
        JsonObject sPlus = new JsonObject();
        JsonObject any = new JsonObject();
        Map<Integer, JsonObject> sorted = new TreeMap<>();
        if (floors != null) {
            for (Map.Entry<String, JsonElement> e : floors.entrySet()) {
                if (e.getKey().matches("floor_[0-7]") && e.getValue().isJsonObject()) {
                    sorted.put(e.getKey().charAt(6) - '0', e.getValue().getAsJsonObject());
                }
            }
        }
        for (Map.Entry<Integer, JsonObject> e : sorted.entrySet()) {
            JsonObject f = e.getValue();
            String key = "floor_" + e.getKey();
            if (f.has("s") && f.get("s").getAsLong() > 0) {
                s.addProperty(key, time(f.get("s").getAsLong()));
            }
            if (f.has("splus") && f.get("splus").getAsLong() > 0) {
                sPlus.addProperty(key, time(f.get("splus").getAsLong()));
            }
            if (f.has("any") && f.get("any").getAsLong() > 0) {
                any.addProperty(key, time(f.get("any").getAsLong()));
            }
        }
        JsonObject out = new JsonObject();
        out.add("s", s);
        out.add("s_plus", sPlus);
        out.add("any", any);
        return out;
    }

    /** The stats the overlay shows, from a player's parsed profiles, or null for none. */
    static PlayerStats toStats(List<SbProfile> profiles, long now) {
        SbProfile chosen = SbProfile.dungeonProfile(profiles);
        if (chosen == null) {
            return null;
        }
        long secrets = 0;
        long runs = 0;
        for (SbProfile p : profiles) {
            secrets += p.dungeons.secrets();
            runs += p.dungeons.totalRuns();
        }
        double level = LevelTables.catacombsLevel(chosen.dungeons.catacombsXp(), false).fractional();
        double average = runs > 0 ? (double) secrets / runs : 0.0;
        return new PlayerStats(level, (int) Math.min(Integer.MAX_VALUE, secrets), average,
                personalBests(chosen.dungeons.normal()), personalBests(chosen.dungeons.master()), now);
    }

    /** {@code {"s": {"floor_N": "m:ss"}, "s_plus": {...}, "any": {...}}} from Hypixel's millisecond fastest times. */
    private static JsonObject personalBests(Map<Integer, SbProfile.Floor> floors) {
        JsonObject s = new JsonObject();
        JsonObject sPlus = new JsonObject();
        JsonObject any = new JsonObject();
        for (Map.Entry<Integer, SbProfile.Floor> e : floors.entrySet()) {
            SbProfile.Floor f = e.getValue();
            if (f.fastestS() > 0) {
                s.addProperty("floor_" + e.getKey(), time(f.fastestS()));
            }
            if (f.fastestSPlus() > 0) {
                sPlus.addProperty("floor_" + e.getKey(), time(f.fastestSPlus()));
            }
            if (f.fastest() > 0) {
                any.addProperty("floor_" + e.getKey(), time(f.fastest()));
            }
        }
        JsonObject out = new JsonObject();
        out.add("s", s);
        out.add("s_plus", sPlus);
        out.add("any", any);
        return out;
    }

    /** The log line's body: what was found, which profile it came from, and what was missing. */
    private static String describe(List<SbProfile> profiles, SbProfile chosen, PlayerStats stats) {
        int sPlus = 0;
        int sOnly = 0;
        int anyOnly = 0;
        for (Map<Integer, SbProfile.Floor> floors : List.of(chosen.dungeons.normal(), chosen.dungeons.master())) {
            for (SbProfile.Floor f : floors.values()) {
                if (f.fastestSPlus() > 0) {
                    sPlus++;
                } else if (f.fastestS() > 0) {
                    sOnly++;
                } else if (f.fastest() > 0) {
                    anyOnly++;
                }
            }
        }
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "Catacombs %.1f, %d secrets, %.1f per run; best times on %d floor(s) (S+ %d, S only %d, any score only %d); profile '%s' (%s of %d)",
                stats.level(), stats.secrets(), stats.averageSecrets(), sPlus + sOnly + anyOnly, sPlus, sOnly, anyOnly,
                chosen.cuteName, chosen.selected ? "selected" : "most Catacombs XP", profiles.size()));
        if (stats.secrets() <= 0) {
            sb.append("; no secrets field");
        }
        if (sPlus + sOnly + anyOnly == 0) {
            sb.append("; no fastest times");
        }
        return sb.toString();
    }

    static String time(long ms) {
        long sec = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60);
    }
}
